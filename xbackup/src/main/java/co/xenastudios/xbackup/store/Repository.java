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
import java.util.Collection;
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
 * forgotten              snapshots removed on purpose, so remotes lose them too (never copied)
 * collected              packs garbage collection deleted on purpose, ditto (never copied)
 * lost                   packs the index listed that went missing: never removed from remotes
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
    public Lock lock(boolean exclusive) throws IOException {
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

    /** A held lock; closing it lets go. */
    public interface Lock extends AutoCloseable {
        @Override
        void close() throws IOException;
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

    /**
     * Drops the index's entries for packs missing from the backend, so a backup stores those
     * blobs again (where the files still have them) instead of pointing at nothing. The packs
     * are noted as {@value #LOST}, so a copy never removes them from a remote, which may hold
     * the only good copy. Hold the exclusive lock.
     *
     * @return the missing packs
     */
    public synchronized List<Hash> dropMissingPacks() throws IOException {
        Set<Hash> present = new HashSet<>();
        for (Backend.FileInfo file : backend.list("data/")) {
            Hash pack = Pack.idOf(file.name());
            if (pack != null) {
                present.add(pack);
            }
        }
        List<Hash> missing = keepPacks(present);
        if (!missing.isEmpty()) {
            noteLost(missing);
        }
        return missing;
    }

    /**
     * Drops damaged packs from the index (a check found them), so a backup stores their blobs
     * again from the live files instead of reusing what can't be read back. Noted as
     * {@value #LOST}, so a copy never removes them from a remote, which may hold them whole.
     * Hold the exclusive lock.
     */
    public synchronized void dropDamagedPacks(Set<Hash> damaged) throws IOException {
        if (damaged.isEmpty()) {
            return;
        }
        Set<Hash> keep = new HashSet<>(index().packs());
        keep.removeAll(damaged);
        keepPacks(keep);
        noteLost(damaged);
    }

    /**
     * Rewrites the index to list only these packs, from every entry of every index file (not
     * just each blob's first place: a blob in a pack that's gone may also be in one that's
     * there).
     *
     * @return the packs the index listed that aren't among them
     */
    public synchronized List<Hash> keepPacks(Set<Hash> present) throws IOException {
        List<Hash> missing = new ArrayList<>();
        for (Hash pack : index().packs()) {
            if (!present.contains(pack)) {
                missing.add(pack);
            }
        }
        if (missing.isEmpty()) {
            return missing;
        }
        Map<Hash, List<PackEntry>> kept = new LinkedHashMap<>();
        for (Backend.FileInfo file : backend.list("index/")) {
            String hex = file.name().substring("index/".length());
            if (!Hash.isHex(hex)) {
                continue;
            }
            for (Map.Entry<Hash, List<PackEntry>> pack : Index.decode(readVerified(file.name(), Hash.parse(hex)),
                file.name()).entrySet()) {
                if (present.contains(pack.getKey())) {
                    kept.computeIfAbsent(pack.getKey(), key -> new ArrayList<>()).addAll(pack.getValue());
                }
            }
        }
        rewriteIndex(kept);
        return missing;
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

    /**
     * Removes a snapshot. Its data stays until garbage collection finds nothing else uses it.
     * It's noted in {@value #FORGOTTEN} first, so copies on remotes are removed too: a remote
     * only loses a snapshot this repository chose to forget, never one it merely lacks.
     */
    public void deleteSnapshot(Snapshot snapshot) throws IOException {
        note(FORGOTTEN, List.of(snapshot.id()), snapshot.time().toEpochMilli(), true);
        backend.delete(Snapshot.fileName(snapshot.id()));
        backend.delete("keep/" + snapshot.id().hex());
    }

    /**
     * Snapshots removed on purpose (retention, {@code /backup forget}), noted for copying the
     * removal to remotes. Local only, never copied. Entries go after about a year.
     */
    static final String FORGOTTEN = "forgotten";
    /** Packs garbage collection deleted on purpose: the only packs a copy removes from a remote. */
    static final String COLLECTED = "collected";
    /** Packs the index listed that went missing. Never removed from a remote; kept for good. */
    static final String LOST = "lost";
    private static final long NOTE_KEEP_MILLIS = 400L * 24 * 3600 * 1000;

    /** Snapshots this repository removed on purpose, with each one's time. */
    public Map<Hash, Long> forgotten() throws IOException {
        Map<Hash, Long> times = new LinkedHashMap<>();
        readNotes(FORGOTTEN).forEach((id, entry) -> times.put(id, entry[0]));
        return times;
    }

    /**
     * Notes packs garbage collection is about to delete, before it does, so copies on remotes
     * are removed too. A remote never loses a pack this repository merely lacks.
     */
    void noteCollected(Collection<Hash> packs) throws IOException {
        if (!packs.isEmpty()) {
            note(COLLECTED, packs, 0, true);
        }
    }

    /** Packs garbage collection deleted on purpose (in about the last year). */
    public Set<Hash> collected() throws IOException {
        return readNotes(COLLECTED).keySet();
    }

    void noteLost(Collection<Hash> packs) throws IOException {
        note(LOST, packs, 0, false);
    }

    /** Packs the index listed that went missing from this repository. */
    public Set<Hash> lost() throws IOException {
        return readNotes(LOST).keySet();
    }

    // Adds ids to a list file of "<id> <value> <when noted>" lines, in milliseconds, dropping
    // entries older than about a year if it expires them.
    private synchronized void note(String file, Collection<Hash> ids, long value, boolean expires) throws IOException {
        long now = System.currentTimeMillis();
        Set<Hash> adding = new HashSet<>(ids);
        StringBuilder text = new StringBuilder();
        for (Map.Entry<Hash, long[]> entry : readNotes(file).entrySet()) {
            if ((!expires || now - entry.getValue()[1] <= NOTE_KEEP_MILLIS) && !adding.contains(entry.getKey())) {
                text.append(entry.getKey().hex()).append(' ').append(entry.getValue()[0]).append(' ')
                    .append(entry.getValue()[1]).append('\n');
            }
        }
        for (Hash id : ids) {
            text.append(id.hex()).append(' ').append(value).append(' ').append(now).append('\n');
        }
        backend.put(file, text.toString().getBytes(StandardCharsets.UTF_8));
    }

    private Map<Hash, long[]> readNotes(String file) throws IOException {
        Map<Hash, long[]> found = new LinkedHashMap<>();
        byte[] bytes;
        try (InputStream in = backend.get(file)) {
            bytes = in.readNBytes(64 << 20);
        } catch (NoSuchFileException e) {
            return found;
        }
        for (String line : new String(bytes, StandardCharsets.UTF_8).split("\n")) {
            String[] parts = line.strip().split(" ");
            if (parts.length == 3 && Hash.isHex(parts[0])) {
                try {
                    found.put(Hash.parse(parts[0]), new long[] {Long.parseLong(parts[1]), Long.parseLong(parts[2])});
                } catch (NumberFormatException e) {
                    // A damaged line: that snapshot or pack just stays on remotes.
                }
            }
        }
        return found;
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
