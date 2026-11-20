package co.xenastudios.xbackup.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Writes new blobs into packs, one backup's worth. Blobs the repository already has are
 * skipped, so storing the same chunk twice costs one hash. Safe to call from several
 * threads: hashing and compression run on the caller's thread, appending under a lock.
 *
 * <p>Packs are stored as each fills; {@link #flush} stores the last ones and an index
 * file listing them; {@link #commit} then writes the snapshot. Closing a session that
 * wasn't committed still indexes the packs already stored, so the next backup can reuse
 * their blobs instead of reading everything again.
 */
public final class Session implements AutoCloseable {

    /**
     * How a session writes.
     *
     * @param level deflate level, 1 to 9; 0 stores everything uncompressed
     * @param packSize start a new pack once one reaches this many bytes
     * @param minFreeBytes stop with {@link NoSpaceException} rather than leave less free space than this
     */
    public record Options(int level, long packSize, long minFreeBytes) {
        public static final Options DEFAULTS = new Options(6, 16L << 20, 512L << 20);
    }

    private final Repository repository;
    private final Index index;
    private final Options options;
    private final Set<Hash> claimed = ConcurrentHashMap.newKeySet();
    private final Object lock = new Object();
    private Pack.Writer data;
    private Pack.Writer metadata;
    private final Map<Hash, List<PackEntry>> unindexed = new LinkedHashMap<>();
    private final Map<Hash, List<PackEntry>> written = new LinkedHashMap<>();
    private final AtomicLong addedBytes = new AtomicLong();
    private final AtomicLong addedBlobs = new AtomicLong();
    private boolean closed;

    /** How much free space must stay on the repository's disk. */
    public long minFreeBytes() {
        return options.minFreeBytes();
    }

    Session(Repository repository, Index index, Options options) {
        this.repository = repository;
        this.index = index;
        this.options = options;
    }

    /**
     * Stores a blob unless the repository has it already.
     *
     * @param compress false for content that's already compressed, like region chunks
     * @return its id: the SHA-256 of {@code raw}
     */
    public Hash store(BlobType type, byte[] raw, boolean compress) throws IOException {
        Hash id = Hash.sha256(raw);
        store(id, type, raw, compress);
        return id;
    }

    /** Stores a blob whose id the caller has already worked out. */
    void store(Hash id, BlobType type, byte[] raw, boolean compress) throws IOException {
        if (raw.length > Pack.MAX_BLOB) {
            throw new IllegalArgumentException("a blob of " + raw.length + " bytes is too big");
        }
        if (index.has(id) || !claimed.add(id)) {
            return;
        }
        byte[] stored = null;
        if (compress && options.level() > 0) {
            stored = Compression.deflate(raw, 0, raw.length, options.level());
        }
        boolean compressed = stored != null;
        append(id, type, compressed ? stored : raw, raw.length, compressed);
    }

    /**
     * Writes a blob's stored bytes into a new pack even though the index has it elsewhere:
     * for garbage collection, moving live blobs out of mostly dead packs. The bytes must
     * already have been checked with {@link Repository#unpack}.
     */
    void copy(PackEntry entry, byte[] stored) throws IOException {
        if (!claimed.add(entry.id())) {
            return;
        }
        append(entry.id(), entry.type(), stored, entry.rawLength(), entry.compressed());
    }

    private void append(Hash id, BlobType type, byte[] stored, int rawLength, boolean compressed) throws IOException {
        synchronized (lock) {
            if (closed) {
                throw new IOException("the backup was stopped");
            }
            Pack.Writer writer = type.isMetadata() ? metadata : data;
            if (writer == null) {
                checkSpace();
                writer = new Pack.Writer(newTemp());
                if (type.isMetadata()) {
                    metadata = writer;
                } else {
                    data = writer;
                }
            }
            writer.add(id, type, stored, rawLength, compressed);
            addedBytes.addAndGet(stored.length);
            addedBlobs.incrementAndGet();
            long target = type.isMetadata() ? Math.max(1L << 20, options.packSize() / 4) : options.packSize();
            if (writer.size() >= target) {
                finishPack(writer);
                if (type.isMetadata()) {
                    metadata = null;
                } else {
                    data = null;
                }
            }
        }
    }

    public Hash storeTree(Tree tree) throws IOException {
        return store(BlobType.TREE, tree.encode(), true);
    }

    public Hash storeRegion(RegionLayout region) throws IOException {
        return store(BlobType.REGION, region.encode(), true);
    }

    /** Whether the repository or this session already has the blob. */
    public boolean has(Hash id) {
        return index.has(id) || claimed.contains(id);
    }

    private Path newTemp() throws IOException {
        if (repository.backend() instanceof LocalBackend local) {
            return local.newTemp(".pack");
        }
        Files.createDirectories(repository.temp());
        return repository.temp().resolve(UUID.randomUUID() + ".pack");
    }

    private void checkSpace() throws IOException {
        Path where = repository.backend() instanceof LocalBackend local ? local.root() : repository.temp();
        Files.createDirectories(where);
        long free = Files.getFileStore(where).getUsableSpace();
        if (free - options.packSize() * 2 < options.minFreeBytes()) {
            throw new NoSpaceException("Stopped: only " + Sizes.format(free) + " free in " + where
                + ", and at least " + Sizes.format(options.minFreeBytes()) + " must stay free.");
        }
    }

    // Under the lock.
    private void finishPack(Pack.Writer writer) throws IOException {
        Pack.Finished pack = writer.finish();
        String name = Pack.fileName(pack.id());
        try {
            repository.backend().put(name, pack.file());
        } finally {
            Files.deleteIfExists(pack.file());
        }
        unindexed.put(pack.id(), pack.entries());
        written.put(pack.id(), pack.entries());
        index.add(pack.id(), pack.entries());
    }

    /** Stores any partly filled packs, then an index file for every pack not yet indexed. */
    public void flush() throws IOException {
        synchronized (lock) {
            if (data != null) {
                Pack.Writer writer = data;
                data = null;
                if (writer.count() > 0) {
                    finishPack(writer);
                } else {
                    writer.close();
                }
            }
            if (metadata != null) {
                Pack.Writer writer = metadata;
                metadata = null;
                if (writer.count() > 0) {
                    finishPack(writer);
                } else {
                    writer.close();
                }
            }
            writeIndex();
        }
    }

    // Under the lock.
    private void writeIndex() throws IOException {
        if (unindexed.isEmpty()) {
            return;
        }
        byte[] bytes = Index.encode(unindexed);
        String name = Index.fileName(Hash.sha256(bytes));
        repository.backend().put(name, bytes);
        repository.indexWritten(name);
        unindexed.clear();
    }

    /** Flushes, then writes the snapshot: the moment the backup exists. */
    public Snapshot commit(Snapshot snapshot) throws IOException {
        flush();
        return repository.writeSnapshot(snapshot.withId(null));
    }

    /** New bytes stored so far, after compression. */
    public long addedBytes() {
        return addedBytes.get();
    }

    public long addedBlobs() {
        return addedBlobs.get();
    }

    /** The packs this session stored, for checking them straight after. */
    public List<Hash> packsWritten() {
        synchronized (lock) {
            return List.copyOf(written.keySet());
        }
    }

    /** The packs this session stored, with their blobs. */
    Map<Hash, List<PackEntry>> writtenEntries() {
        synchronized (lock) {
            return new LinkedHashMap<>(written);
        }
    }

    /**
     * Stops the session. Packs still being written are thrown away; packs already stored
     * get their index file, so their blobs can be reused.
     */
    @Override
    public void close() throws IOException {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            try {
                if (data != null) {
                    data.close();
                }
                if (metadata != null) {
                    metadata.close();
                }
            } finally {
                data = null;
                metadata = null;
                writeIndex();
            }
        }
    }

    /** Not enough free disk space to carry on. */
    public static final class NoSpaceException extends IOException {
        public NoSpaceException(String message) {
            super(message);
        }
    }
}
