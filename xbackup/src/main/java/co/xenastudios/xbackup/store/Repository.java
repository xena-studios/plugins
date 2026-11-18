package co.xenastudios.xbackup.store;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A backup repository: content-addressed blobs in immutable packs, index files saying which
 * pack holds which blob, and one small file per snapshot. Works on any {@link Backend}.
 *
 * <pre>
 * config                 what this is, and its id
 * data/ab/ab12...        packs
 * index/ab12...          index files
 * snapshots/ab12...      snapshots
 * keep/ab12...           snapshots pinned so retention never removes them
 * lock                   held while the repository is in use (folders only)
 * </pre>
 *
 * <p>Nothing is ever changed in place. A backup writes packs, then an index file for them,
 * then its snapshot, each flushed to disk before the next, so a crash at any moment leaves
 * at worst some packs nothing points to, which garbage collection removes.
 */
public final class Repository implements AutoCloseable {

    static final String CONFIG = "config";
    static final int VERSION = 1;
    private static final String CONFIG_HEADER = "xbackup-repository";

    private final Backend backend;
    private final Path temp;
    private final String id;
    private Index index;
    private final Set<String> indexFiles = new HashSet<>();
    private final Map<Hash, Object> metadata = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Hash, Object> eldest) {
            return size() > 2048;
        }
    };

    private Repository(Backend backend, Path temp, String id) {
        this.backend = backend;
        this.temp = temp;
        this.id = id;
    }

    /**
     * Opens the repository, making it first if the backend is empty.
     *
     * @param temp a local folder for packs while they're written; for a folder backend, use
     *     its own {@code tmp/} ({@link LocalBackend#newTemp}) so packs are renamed, not copied
     */
    public static Repository open(Backend backend, Path temp, boolean create) throws IOException {
        byte[] config;
        try (InputStream in = backend.get(CONFIG)) {
            config = in.readNBytes(64 * 1024);
        } catch (NoSuchFileException e) {
            if (!create) {
                throw new NoSuchFileException("There's no backup repository in " + backend.describe() + ".");
            }
            String newId = HexFormat.of().formatHex(SecureRandom.getSeed(16));
            String text = CONFIG_HEADER + "=" + VERSION + "\nid=" + newId + "\ncreated=" + Instant.now() + "\n";
            backend.put(CONFIG, text.getBytes(StandardCharsets.UTF_8));
            return new Repository(backend, temp, newId);
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : new String(config, StandardCharsets.UTF_8).split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                values.put(line.substring(0, eq), line.substring(eq + 1).strip());
            }
        }
        String version = values.get(CONFIG_HEADER);
        if (version == null) {
            throw new CorruptException(backend.describe() + " has a config file that isn't xBackup's.");
        }
        if (!version.equals(Integer.toString(VERSION))) {
            throw new CorruptException(backend.describe() + " is repository version " + version
                + "; this xBackup reads version " + VERSION + ". Update xBackup.");
        }
        return new Repository(backend, temp, values.getOrDefault("id", "unknown"));
    }

    /** Whether the backend holds a repository. */
    public static boolean exists(Backend backend) throws IOException {
        return !backend.list(CONFIG).isEmpty();
    }

    public Backend backend() {
        return backend;
    }

    public String id() {
        return id;
    }

    Path temp() {
        return temp;
    }

    // ---------------------------------------------------------------- locking

    /**
     * Locks a folder repository against other processes: shared for reading (restores,
     * checks), exclusive for anything that writes or deletes. The operating system drops
     * the lock if the process dies, so a crash never leaves it locked. Remote backends have
     * no lock; each server should use its own folder or prefix.
     *
     * @throws LockedException if another process holds a lock that conflicts
     */
    public AutoCloseable lock(boolean exclusive) throws IOException {
        if (!(backend instanceof LocalBackend local)) {
            return () -> {};
        }
        Files.createDirectories(local.root());
        FileChannel channel = FileChannel.open(local.root().resolve("lock"), StandardOpenOption.CREATE,
            StandardOpenOption.READ, StandardOpenOption.WRITE);
        FileLock lock;
        try {
            lock = channel.tryLock(0, Long.MAX_VALUE, !exclusive);
        } catch (OverlappingFileLockException e) {
            lock = null;
        } catch (IOException e) {
            channel.close();
            throw e;
        }
        if (lock == null) {
            channel.close();
            throw new LockedException("The repository in " + local.root()
                + " is in use: a backup or restore is running, here or in another process.");
        }
        FileLock held = lock;
        return () -> {
            try {
                held.release();
            } finally {
                channel.close();
            }
        };
    }

    /** Another process holds the repository. */
    public static final class LockedException extends IOException {
        public LockedException(String message) {
            super(message);
        }
    }

    // ---------------------------------------------------------------- index

    /** The index of every blob, read from the index files the first time it's needed. */
    public synchronized Index index() throws IOException {
        if (index == null) {
            Index loaded = new Index();
            Set<String> names = new HashSet<>();
            for (Backend.FileInfo file : backend.list("index/")) {
                String hex = file.name().substring("index/".length());
                if (!Hash.isHex(hex)) {
                    continue;
                }
                byte[] bytes = readVerified(file.name(), Hash.parse(hex));
                for (Map.Entry<Hash, List<PackEntry>> pack : Index.decode(bytes, file.name()).entrySet()) {
                    loaded.add(pack.getKey(), pack.getValue());
                }
                names.add(file.name());
            }
            index = loaded;
            indexFiles.clear();
            indexFiles.addAll(names);
        }
        return index;
    }

    /** The index files read into {@link #index()}. */
    synchronized Set<String> indexFiles() {
        return Set.copyOf(indexFiles);
    }

    /** Lets go of the index and cached trees, to free memory between jobs. */
    public synchronized void release() {
        index = null;
        indexFiles.clear();
        metadata.clear();
    }

    synchronized void indexWritten(String name) {
        indexFiles.add(name);
    }

    synchronized void indexDeleted(String name) {
        indexFiles.remove(name);
    }

    /**
     * Writes a fresh index file listing exactly these packs, then deletes every other index
     * file. The new one is safely stored before any old one goes, so a crash in between only
     * leaves the blobs listed twice.
     */
    public synchronized void rewriteIndex(Map<Hash, List<PackEntry>> packs) throws IOException {
        Set<String> old = new HashSet<>(indexFiles);
        if (index == null) {
            for (Backend.FileInfo file : backend.list("index/")) {
                old.add(file.name());
            }
        }
        String fresh = null;
        if (!packs.isEmpty()) {
            byte[] bytes = Index.encode(packs);
            fresh = Index.fileName(Hash.sha256(bytes));
            backend.put(fresh, bytes);
        }
        for (String name : old) {
            if (!name.equals(fresh)) {
                backend.delete(name);
            }
        }
        Index rebuilt = new Index();
        packs.forEach(rebuilt::add);
        index = rebuilt;
        indexFiles.clear();
        if (fresh != null) {
            indexFiles.add(fresh);
        }
    }

    // ---------------------------------------------------------------- reading

    /** Reads a whole repository file and checks its bytes hash to its name. */
    byte[] readVerified(String name, Hash expected) throws IOException {
        byte[] bytes;
        try (InputStream in = backend.get(name)) {
            bytes = in.readAllBytes();
        }
        Hash actual = Hash.sha256(bytes);
        if (!actual.equals(expected)) {
            throw new CorruptException(name + " is damaged: its content doesn't match its name");
        }
        return bytes;
    }

    /** A blob's content, inflated and checked against its id. */
    public byte[] readBlob(Hash blob) throws IOException {
        Index.Location location = index().find(blob);
        if (location == null) {
            throw new CorruptException("blob " + blob.shortHex() + " isn't in any pack");
        }
        PackEntry entry = location.entry();
        byte[] stored = backend.read(Pack.fileName(location.pack()), entry.offset(), entry.storedLength());
        return unpack(entry, stored);
    }

    /** Turns a blob's stored bytes back into its content, checking it against its id. */
    public static byte[] unpack(PackEntry entry, byte[] stored) throws CorruptException {
        byte[] raw = entry.compressed() ? Compression.inflate(stored, entry.rawLength()) : stored;
        if (raw.length != entry.rawLength()) {
            throw new CorruptException("blob " + entry.id().shortHex() + " has the wrong length");
        }
        if (!Hash.sha256(raw).equals(entry.id())) {
            throw new CorruptException("blob " + entry.id().shortHex() + " is damaged");
        }
        return raw;
    }

    public Tree tree(Hash id) throws IOException {
        synchronized (metadata) {
            if (metadata.get(id) instanceof Tree tree) {
                return tree;
            }
        }
        Tree tree = Tree.decode(readBlob(id), "tree " + id.shortHex());
        synchronized (metadata) {
            metadata.put(id, tree);
        }
        return tree;
    }

    public RegionLayout region(Hash id) throws IOException {
        synchronized (metadata) {
            if (metadata.get(id) instanceof RegionLayout region) {
                return region;
            }
        }
        RegionLayout region = RegionLayout.decode(readBlob(id), "region layout " + id.shortHex());
        synchronized (metadata) {
            metadata.put(id, region);
        }
        return region;
    }

    // ---------------------------------------------------------------- snapshots

    /**
     * Every snapshot, oldest first. A snapshot file that can't be read is left out and
     * described in {@code problems}.
     */
    public List<Snapshot> snapshots(List<String> problems) throws IOException {
        List<Snapshot> snapshots = new ArrayList<>();
        for (Backend.FileInfo file : backend.list("snapshots/")) {
            String hex = file.name().substring("snapshots/".length());
            if (!Hash.isHex(hex)) {
                continue;
            }
            Hash snapshotId = Hash.parse(hex);
            try {
                snapshots.add(Snapshot.decode(snapshotId, readVerified(file.name(), snapshotId)));
            } catch (CorruptException e) {
                problems.add(e.getMessage());
            } catch (NoSuchFileException e) {
                // Removed while listing.
            }
        }
        snapshots.sort(Comparator.comparing(Snapshot::time).thenComparing(Snapshot::id));
        return snapshots;
    }

    /** Writes a snapshot file, last, and gives it back with its id. */
    public Snapshot writeSnapshot(Snapshot snapshot) throws IOException {
        byte[] bytes = snapshot.encode();
        Hash snapshotId = Hash.sha256(bytes);
        backend.put(Snapshot.fileName(snapshotId), bytes);
        return snapshot.withId(snapshotId);
    }

    /** Removes a snapshot. Its data stays until garbage collection finds nothing else uses it. */
    public void deleteSnapshot(Hash snapshotId) throws IOException {
        backend.delete(Snapshot.fileName(snapshotId));
        backend.delete("keep/" + snapshotId.hex());
    }

    /** Snapshots pinned with {@link #pin}, which retention never removes. */
    public Set<Hash> pins() throws IOException {
        Set<Hash> pins = new HashSet<>();
        for (Backend.FileInfo file : backend.list("keep/")) {
            String hex = file.name().substring("keep/".length());
            if (Hash.isHex(hex)) {
                pins.add(Hash.parse(hex));
            }
        }
        return pins;
    }

    public void pin(Hash snapshotId, boolean pinned) throws IOException {
        if (pinned) {
            backend.put("keep/" + snapshotId.hex(), new byte[0]);
        } else {
            backend.delete("keep/" + snapshotId.hex());
        }
    }

    // ---------------------------------------------------------------- writing

    /** Starts writing new blobs. Hold the exclusive lock until it's closed. */
    public Session session(Session.Options options) throws IOException {
        return new Session(this, index(), options);
    }

    @Override
    public void close() throws IOException {
        release();
        backend.close();
    }
}
