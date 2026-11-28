package co.xenastudios.xbackup.store

import java.io.BufferedOutputStream
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Clock
import kotlin.time.Instant

/** Reads a pack's blob list from its end, without reading its blobs. */
public fun readFooter(backend: Backend, name: String, fileSize: Long): List<PackEntry> {
    if (fileSize < PackFormat.TRAILER) throw CorruptException("$name is too short to be a pack")
    val footerLength = PackFormat.footerLength(backend.read(name, fileSize - PackFormat.TRAILER, PackFormat.TRAILER), fileSize, name)
    val footer = backend.read(name, fileSize - PackFormat.TRAILER - footerLength, footerLength)
    return PackFormat.parseFooter(footer, 0, footerLength, fileSize - PackFormat.TRAILER - footerLength, name)
}

/** Writes one pack to a temporary file. Not thread-safe; [Session] guards it. */
internal class PackWriter(private val file: Path) : AutoCloseable {
    private val digest: MessageDigest = Hashes.digest()
    private val out: OutputStream =
        DigestOutputStream(BufferedOutputStream(Channels.newOutputStream(FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)), 1 shl 16), digest)
    private val entries = ArrayList<PackEntry>()
    var size = 0L
        private set
    private var done = false

    val count: Int get() = entries.size

    fun add(id: Hash, type: BlobType, stored: ByteArray, rawLength: Int, compressed: Boolean): PackEntry {
        out.write(stored)
        val entry = PackEntry(id, type, compressed, size, stored.size, rawLength)
        entries += entry
        size += stored.size
        return entry
    }

    /** Writes the footer and flushes the file to disk. */
    fun finish(): Finished {
        val footer = PackFormat.footer(entries)
        out.write(footer)
        out.flush()
        done = true
        out.close()
        LocalBackend.sync(file)
        return Finished(file, Hashes.of(digest), entries.toList(), size + footer.size)
    }

    override fun close() {
        if (!done) {
            done = true
            try {
                out.close()
            } finally {
                Files.deleteIfExists(file)
            }
        }
    }

    /** A finished pack, waiting to be stored under its id. */
    class Finished(val file: Path, val id: Hash, val entries: List<PackEntry>, val size: Long)
}

/**
 * A backup repository: content-addressed blobs in immutable packs, index files saying which pack
 * holds which blob, and one small file per snapshot. Works on any [Backend]. The layout is 1.x's:
 *
 * ```
 * config                 what this is, and its id
 * data/ab/ab12...        packs
 * index/ab12...          index files
 * snapshots/ab12...      snapshots
 * keep/ab12...           snapshots pinned so retention never removes them
 * forgotten              snapshots removed on purpose, so remotes lose them too (never copied)
 * collected              packs garbage collection deleted on purpose, ditto (never copied)
 * lost                   packs the index listed that went missing: never removed from remotes
 * lock                   held while the repository is in use (folders only)
 * ```
 *
 * Nothing is ever changed in place. A backup writes packs, then an index file for them, then its
 * snapshot, each flushed to disk before the next, so a crash at any moment leaves at worst some
 * packs nothing points to, which garbage collection removes.
 */
public class Repository private constructor(
    public val backend: Backend,
    internal val temp: Path,
    public val id: String,
) : AutoCloseable {
    private var index: Index? = null
    private val indexFiles = HashSet<String>()
    private val metadata = object : LinkedHashMap<Hash, Any>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Hash, Any>?): Boolean = size > 2048
    }

    // ---- locking ----

    /**
     * Locks a folder repository against other processes: shared for reading (restores, checks),
     * exclusive for anything that writes or deletes. The operating system drops the lock if the
     * process dies, so a crash never leaves it locked. Remote backends have no lock.
     *
     * @throws LockedException if another process holds a lock that conflicts
     */
    public fun lock(exclusive: Boolean): Lock {
        val local = backend as? LocalBackend ?: return Lock {}
        Files.createDirectories(local.root)
        val channel = FileChannel.open(local.root.resolve("lock"), StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)
        val lock: FileLock? = try {
            channel.tryLock(0, Long.MAX_VALUE, !exclusive)
        } catch (e: OverlappingFileLockException) {
            null
        } catch (e: java.io.IOException) {
            channel.close()
            throw e
        }
        if (lock == null) {
            channel.close()
            throw LockedException("The repository in ${local.root} is in use: a backup or restore is running, here or in another process.")
        }
        return Lock {
            try {
                lock.release()
            } finally {
                channel.close()
            }
        }
    }

    /** A held lock; closing it lets go. */
    public fun interface Lock : AutoCloseable {
        override fun close()
    }

    // ---- index ----

    /** The index of every blob, read from the index files the first time it's needed. */
    @Synchronized
    public fun index(): Index {
        index?.let { return it }
        val loaded = Index()
        val names = HashSet<String>()
        for (file in backend.list("index/")) {
            val hex = file.name.substring("index/".length)
            if (!Hash.isHex(hex)) continue
            val bytes = readVerified(file.name, Hash.parse(hex))
            for ((pack, entries) in IndexFormat.decode(Compression.inflateIndex(bytes, file.name), file.name)) loaded.add(pack, entries)
            names += file.name
        }
        index = loaded
        indexFiles.clear()
        indexFiles += names
        return loaded
    }

    /** Lets go of the index and cached trees, to free memory between jobs. */
    @Synchronized
    public fun release() {
        index = null
        indexFiles.clear()
        synchronized(metadata) { metadata.clear() }
    }

    @Synchronized
    internal fun indexWritten(name: String) {
        indexFiles += name
    }

    /**
     * Writes a fresh index file listing exactly [packs], then deletes every other index file. The
     * new one is safely stored before any old one goes, so a crash in between only leaves the
     * blobs listed twice.
     */
    @Synchronized
    public fun rewriteIndex(packs: Map<Hash, List<PackEntry>>) {
        val old = HashSet(indexFiles)
        if (index == null) for (file in backend.list("index/")) old += file.name
        var fresh: String? = null
        if (packs.isNotEmpty()) {
            val bytes = Compression.deflateIndex(IndexFormat.encode(packs))
            fresh = IndexFormat.fileName(Hashes.sha256(bytes))
            backend.put(fresh, bytes)
        }
        for (name in old) if (name != fresh) backend.delete(name)
        val rebuilt = Index()
        for ((pack, entries) in packs) rebuilt.add(pack, entries)
        index = rebuilt
        indexFiles.clear()
        if (fresh != null) indexFiles += fresh
    }

    /**
     * Drops the index's entries for packs missing from the backend, so a backup stores those blobs
     * again (where the files still have them) instead of pointing at nothing. The packs are noted
     * as [LOST], so a copy never removes them from a remote, which may hold the only good copy.
     * Hold the exclusive lock.
     */
    @Synchronized
    public fun dropMissingPacks(): List<Hash> {
        val present = HashSet<Hash>()
        for (file in backend.list("data/")) PackFormat.idOf(file.name)?.let { present += it }
        val missing = keepPacks(present)
        if (missing.isNotEmpty()) noteLost(missing)
        return missing
    }

    /** Drops damaged packs (a check found them) from the index, noted as [LOST]. Hold the exclusive lock. */
    @Synchronized
    public fun dropDamagedPacks(damaged: Set<Hash>) {
        if (damaged.isEmpty()) return
        val keep = HashSet(index().packs())
        keep.removeAll(damaged)
        keepPacks(keep)
        noteLost(damaged)
    }

    /**
     * Rewrites the index to list only [present] packs, from every entry of every index file (not
     * just each blob's first place: a blob in a pack that's gone may also be in one that's there).
     *
     * @return the packs the index listed that aren't among them
     */
    @Synchronized
    public fun keepPacks(present: Set<Hash>): List<Hash> {
        val missing = index().packs().filter { it !in present }
        if (missing.isEmpty()) return missing
        val kept = LinkedHashMap<Hash, MutableList<PackEntry>>()
        for (file in backend.list("index/")) {
            val hex = file.name.substring("index/".length)
            if (!Hash.isHex(hex)) continue
            val raw = Compression.inflateIndex(readVerified(file.name, Hash.parse(hex)), file.name)
            for ((pack, entries) in IndexFormat.decode(raw, file.name)) {
                if (pack in present) kept.getOrPut(pack) { ArrayList() } += entries
            }
        }
        rewriteIndex(kept)
        return missing
    }

    // ---- reading ----

    /** Reads a whole repository file and checks its bytes hash to its name. */
    public fun readVerified(name: String, expected: Hash): ByteArray {
        val bytes = backend.readAll(name)
        if (Hashes.sha256(bytes) != expected) throw CorruptException("$name is damaged: its content doesn't match its name")
        return bytes
    }

    /** A blob's content, inflated and checked against its id. */
    public fun readBlob(blob: Hash): ByteArray {
        val location = index().find(blob) ?: throw CorruptException("blob ${blob.shortHex()} isn't in any pack")
        val entry = location.entry
        val stored = backend.read(PackFormat.fileName(location.pack), entry.offset, entry.storedLength)
        return unpack(entry, stored)
    }

    public fun tree(id: Hash): Tree {
        synchronized(metadata) { (metadata[id] as? Tree)?.let { return it } }
        val tree = Tree.decode(readBlob(id), "tree " + id.shortHex())
        synchronized(metadata) { metadata[id] = tree }
        return tree
    }

    public fun region(id: Hash): RegionLayout {
        synchronized(metadata) { (metadata[id] as? RegionLayout)?.let { return it } }
        val region = RegionLayout.decode(readBlob(id), "region layout " + id.shortHex())
        synchronized(metadata) { metadata[id] = region }
        return region
    }

    // ---- snapshots ----

    /** Every snapshot, oldest first. One that can't be read is left out and described in [problems]. */
    public fun snapshots(problems: MutableList<String>): List<Snapshot> {
        val snapshots = ArrayList<Snapshot>()
        for (file in backend.list("snapshots/")) {
            val hex = file.name.substring("snapshots/".length)
            if (!Hash.isHex(hex)) continue
            val snapshotId = Hash.parse(hex)
            try {
                snapshots += Snapshot.decode(snapshotId, readVerified(file.name, snapshotId))
            } catch (e: CorruptException) {
                problems += e.message.orEmpty()
            } catch (e: NoSuchFileException) {
                // Removed while listing.
            }
        }
        snapshots.sortWith(compareBy<Snapshot> { it.time }.thenBy { it.key })
        return snapshots
    }

    /** Writes a snapshot file, last, and gives it back with its id. */
    public fun writeSnapshot(snapshot: Snapshot): Snapshot {
        val bytes = snapshot.encode()
        val snapshotId = Hashes.sha256(bytes)
        backend.put(Snapshot.fileName(snapshotId), bytes)
        return snapshot.withId(snapshotId)
    }

    /**
     * Removes a snapshot. Its data stays until garbage collection finds nothing else uses it. It's
     * noted in [FORGOTTEN] first, so copies on remotes are removed too.
     */
    public fun deleteSnapshot(snapshot: Snapshot) {
        note(FORGOTTEN, listOf(snapshot.key), snapshot.time.toEpochMilliseconds(), true)
        backend.delete(Snapshot.fileName(snapshot.key))
        backend.delete("keep/" + snapshot.key.hex())
    }

    /** Snapshots this repository removed on purpose, with each one's time. */
    public fun forgotten(): Map<Hash, Long> = readNotes(FORGOTTEN).mapValues { it.value[0] }

    /** Notes packs garbage collection is about to delete, before it does, so copies on remotes go too. */
    internal fun noteCollected(packs: Collection<Hash>) {
        if (packs.isNotEmpty()) note(COLLECTED, packs, 0, true)
    }

    /** Packs garbage collection deleted on purpose (in about the last year). */
    public fun collected(): Set<Hash> = readNotes(COLLECTED).keys

    internal fun noteLost(packs: Collection<Hash>) {
        note(LOST, packs, 0, false)
    }

    /** Packs the index listed that went missing from this repository. */
    public fun lost(): Set<Hash> = readNotes(LOST).keys

    // Adds ids to a list file of "<id> <value> <when noted>" lines, in milliseconds, dropping
    // entries older than about a year if it expires them.
    @Synchronized
    private fun note(file: String, ids: Collection<Hash>, value: Long, expires: Boolean) {
        val now = System.currentTimeMillis()
        val adding = HashSet(ids)
        val text = StringBuilder()
        for ((id, entry) in readNotes(file)) {
            if ((!expires || now - entry[1] <= NOTE_KEEP_MILLIS) && id !in adding) {
                text.append(id.hex()).append(' ').append(entry[0]).append(' ').append(entry[1]).append('\n')
            }
        }
        for (id in ids) text.append(id.hex()).append(' ').append(value).append(' ').append(now).append('\n')
        backend.put(file, text.toString().toByteArray(Charsets.UTF_8))
    }

    private fun readNotes(file: String): Map<Hash, LongArray> {
        val found = LinkedHashMap<Hash, LongArray>()
        val bytes = try {
            backend.get(file).use { readUpTo(it, 64 shl 20) }
        } catch (e: NoSuchFileException) {
            return found
        }
        for (line in String(bytes, Charsets.UTF_8).split('\n')) {
            val parts = line.trim().split(' ')
            if (parts.size == 3 && Hash.isHex(parts[0])) {
                val v = parts[1].toLongOrNull()
                val at = parts[2].toLongOrNull()
                // A damaged line: that snapshot or pack just stays on remotes.
                if (v != null && at != null) found[Hash.parse(parts[0])] = longArrayOf(v, at)
            }
        }
        return found
    }

    /** Snapshots pinned with [pin], which retention never removes. */
    public fun pins(): Set<Hash> =
        backend.list("keep/").map { it.name.substring("keep/".length) }.filter(Hash::isHex).map(Hash::parse).toSet()

    public fun pin(snapshotId: Hash, pinned: Boolean) {
        if (pinned) backend.put("keep/" + snapshotId.hex(), ByteArray(0)) else backend.delete("keep/" + snapshotId.hex())
    }

    // ---- writing ----

    /** Starts writing new blobs. Hold the exclusive lock until it's closed. */
    public fun session(options: Session.Options): Session = Session(this, index(), options)

    override fun close() {
        release()
        backend.close()
    }

    /** Another process holds the repository. */
    public class LockedException(message: String) : IOException(message, null)

    public companion object {
        internal const val CONFIG = "config"
        internal const val VERSION = 1
        private const val CONFIG_HEADER = "xbackup-repository"

        /** Snapshots removed on purpose (retention, `/backup forget`), for copying the removal to remotes. Local only. */
        public const val FORGOTTEN: String = "forgotten"

        /** Packs garbage collection deleted on purpose: the only packs a copy removes from a remote. */
        public const val COLLECTED: String = "collected"

        /** Packs the index listed that went missing. Never removed from a remote; kept for good. */
        public const val LOST: String = "lost"
        private const val NOTE_KEEP_MILLIS = 400L * 24 * 3600 * 1000

        /**
         * Opens the repository, making it first if the backend is empty and [create] says to.
         *
         * @param temp a local folder for packs while they're written; a folder backend uses its own `tmp/`
         */
        @JvmStatic
        public fun open(backend: Backend, temp: Path, create: Boolean): Repository {
            val config = try {
                backend.get(CONFIG).use { readUpTo(it, 64 * 1024) }
            } catch (e: NoSuchFileException) {
                if (!create) throw NoSuchFileException("There's no backup repository in ${backend.describe()}.")
                val seed = ByteArray(16).also { SecureRandom().nextBytes(it) }
                val newId = seed.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
                val text = "$CONFIG_HEADER=$VERSION\nid=$newId\ncreated=${Snapshot.iso(Clock.System.now())}\n"
                backend.put(CONFIG, text.toByteArray(Charsets.UTF_8))
                return Repository(backend, temp, newId)
            }
            val values = LinkedHashMap<String, String>()
            for (line in String(config, Charsets.UTF_8).split('\n')) {
                val eq = line.indexOf('=')
                if (eq > 0) values[line.substring(0, eq)] = line.substring(eq + 1).trim()
            }
            val version = values[CONFIG_HEADER] ?: throw CorruptException("${backend.describe()} has a config file that isn't xBackup's.")
            if (version != VERSION.toString()) {
                throw CorruptException("${backend.describe()} is repository version $version; this xBackup reads version $VERSION. Update xBackup.")
            }
            return Repository(backend, temp, values["id"] ?: "unknown")
        }

        /** Whether the backend holds a repository. */
        @JvmStatic
        public fun exists(backend: Backend): Boolean = backend.list(CONFIG).isNotEmpty()

        /** Turns a blob's stored bytes back into its content, checking it against its id. */
        @JvmStatic
        public fun unpack(entry: PackEntry, stored: ByteArray): ByteArray {
            val raw = if (entry.compressed) Compression.inflate(stored, entry.rawLength) else stored
            if (raw.size != entry.rawLength) throw CorruptException("blob ${entry.id.shortHex()} has the wrong length")
            if (Hashes.sha256(raw) != entry.id) throw CorruptException("blob ${entry.id.shortHex()} is damaged")
            return raw
        }
    }
}

/**
 * Writes new blobs into packs, one backup's worth. Blobs the repository already has are skipped,
 * so storing the same chunk twice costs one hash. Safe to call from several threads: hashing and
 * compression run on the caller's thread, appending under a lock.
 *
 * Packs are stored as each fills; [flush] stores the last ones and an index file listing them;
 * [commit] then writes the snapshot. Closing a session that wasn't committed still indexes the
 * packs already stored, so the next backup can reuse their blobs instead of reading everything again.
 */
public class Session internal constructor(private val repository: Repository, private val index: Index, private val options: Options) : AutoCloseable {
    /**
     * How a session writes.
     *
     * @param level deflate level, 1 to 9; 0 stores everything uncompressed
     * @param packSize start a new pack once one reaches this many bytes
     * @param minFreeBytes stop with [NoSpaceException] rather than leave less free space than this
     */
    public data class Options(val level: Int, val packSize: Long, val minFreeBytes: Long) {
        public companion object {
            @JvmField
            public val DEFAULTS: Options = Options(6, 16L shl 20, 512L shl 20)
        }
    }

    private val claimed: MutableSet<Hash> = ConcurrentHashMap.newKeySet()
    private val lock = Any()
    private var data: PackWriter? = null
    private var metadata: PackWriter? = null
    private val unindexed = LinkedHashMap<Hash, List<PackEntry>>()
    private val written = LinkedHashMap<Hash, List<PackEntry>>()
    private val added = AtomicLong()
    private val blobs = AtomicLong()
    private var closed = false

    /** How much free space must stay on the repository's disk. */
    public val minFreeBytes: Long get() = options.minFreeBytes

    /**
     * Stores a blob unless the repository has it already.
     *
     * @param compress false for content that's already compressed, like region chunks
     * @return its id: the SHA-256 of [raw]
     */
    public fun store(type: BlobType, raw: ByteArray, compress: Boolean): Hash {
        val id = Hashes.sha256(raw)
        store(id, type, raw, compress)
        return id
    }

    /** Stores a blob whose id the caller has already worked out. */
    internal fun store(id: Hash, type: BlobType, raw: ByteArray, compress: Boolean) {
        require(raw.size <= PackFormat.MAX_BLOB) { "a blob of ${raw.size} bytes is too big" }
        if (index.has(id) || !claimed.add(id)) return
        val stored = if (compress && options.level > 0) Compression.deflate(raw, 0, raw.size, options.level) else null
        append(id, type, stored ?: raw, raw.size, stored != null)
    }

    /**
     * Writes a blob's stored bytes into a new pack even though the index has it elsewhere: for
     * garbage collection, moving live blobs out of mostly dead packs. The bytes must already have
     * been checked with [Repository.unpack].
     */
    internal fun copy(entry: PackEntry, stored: ByteArray) {
        if (!claimed.add(entry.id)) return
        append(entry.id, entry.type, stored, entry.rawLength, entry.compressed)
    }

    private fun append(id: Hash, type: BlobType, stored: ByteArray, rawLength: Int, compressed: Boolean) {
        synchronized(lock) {
            if (closed) throw IOException("the backup was stopped", null)
            var writer = if (type.isMetadata) metadata else data
            if (writer == null) {
                checkSpace()
                writer = PackWriter(newTemp())
                if (type.isMetadata) metadata = writer else data = writer
            }
            writer.add(id, type, stored, rawLength, compressed)
            added.addAndGet(stored.size.toLong())
            blobs.incrementAndGet()
            val target = if (type.isMetadata) maxOf(1L shl 20, options.packSize / 4) else options.packSize
            if (writer.size >= target) {
                finishPack(writer)
                if (type.isMetadata) metadata = null else data = null
            }
        }
    }

    public fun storeTree(tree: Tree): Hash = store(BlobType.TREE, tree.encode(), true)

    public fun storeRegion(region: RegionLayout): Hash = store(BlobType.REGION, region.encode(), true)

    /** Whether the repository or this session already has the blob. */
    public fun has(id: Hash): Boolean = index.has(id) || id in claimed

    private fun newTemp(): Path {
        (repository.backend as? LocalBackend)?.let { return it.newTemp(".pack") }
        Files.createDirectories(repository.temp)
        return repository.temp.resolve(UUID.randomUUID().toString() + ".pack")
    }

    private fun checkSpace() {
        val where = (repository.backend as? LocalBackend)?.root ?: repository.temp
        Files.createDirectories(where)
        val free = Files.getFileStore(where).usableSpace
        if (free - options.packSize * 2 < options.minFreeBytes) {
            throw NoSpaceException("Stopped: only ${Sizes.format(free)} free in $where, and at least ${Sizes.format(options.minFreeBytes)} must stay free.")
        }
    }

    // Under the lock.
    private fun finishPack(writer: PackWriter) {
        val pack = writer.finish()
        val name = PackFormat.fileName(pack.id)
        try {
            repository.backend.put(name, pack.file)
        } finally {
            Files.deleteIfExists(pack.file)
        }
        unindexed[pack.id] = pack.entries
        written[pack.id] = pack.entries
        index.add(pack.id, pack.entries)
    }

    /** Stores any partly filled packs, then an index file for every pack not yet indexed. */
    public fun flush() {
        synchronized(lock) {
            data?.let { writer ->
                data = null
                if (writer.count > 0) finishPack(writer) else writer.close()
            }
            metadata?.let { writer ->
                metadata = null
                if (writer.count > 0) finishPack(writer) else writer.close()
            }
            writeIndex()
        }
    }

    // Under the lock.
    private fun writeIndex() {
        if (unindexed.isEmpty()) return
        val bytes = Compression.deflateIndex(IndexFormat.encode(unindexed))
        val name = IndexFormat.fileName(Hashes.sha256(bytes))
        repository.backend.put(name, bytes)
        repository.indexWritten(name)
        unindexed.clear()
    }

    /** Flushes, then writes the snapshot: the moment the backup exists. */
    public fun commit(snapshot: Snapshot): Snapshot {
        flush()
        return repository.writeSnapshot(snapshot.withId(null))
    }

    /** New bytes stored so far, after compression. */
    public val addedBytes: Long get() = added.get()

    public val addedBlobs: Long get() = blobs.get()

    /** The packs this session stored, for checking them straight after. */
    public fun packsWritten(): List<Hash> = synchronized(lock) { written.keys.toList() }

    /** The packs this session stored, with their blobs. */
    internal fun writtenEntries(): Map<Hash, List<PackEntry>> = synchronized(lock) { LinkedHashMap(written) }

    /** Stops the session: packs still being written are thrown away; packs already stored get their index file. */
    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            try {
                data?.close()
                metadata?.close()
            } finally {
                data = null
                metadata = null
                writeIndex()
            }
        }
    }
}

/** When a snapshot was taken, for the times in its file: `kotlin.time`'s, from the system clock. */
internal fun now(): Instant = Clock.System.now()
