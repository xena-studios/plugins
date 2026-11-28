package co.xenastudios.xbackup.store

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.FilterInputStream
import java.io.InputStream
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/** SHA-256 on the JVM: the JDK's, which is what keeps hashing a world fast. */
public object Hashes {
    public fun digest(): MessageDigest = MessageDigest.getInstance("SHA-256")

    public fun sha256(data: ByteArray, offset: Int = 0, length: Int = data.size): Hash {
        val digest = digest()
        digest.update(data, offset, length)
        return Hash.of(digest.digest())
    }

    public fun of(digest: MessageDigest): Hash = Hash.of(digest.digest())
}

/**
 * Blob compression: the JDK's deflate, in zlib format so each compressed blob also carries its own
 * checksum. A blob is kept as it is when deflate doesn't make it smaller, as with region chunks,
 * which Minecraft has already compressed.
 */
public object Compression {
    private val deflaters = ThreadLocal<Deflater>()
    private val inflaters = ThreadLocal<Inflater>()

    /** The bytes deflated at [level] (1 to 9), or `null` if that wouldn't save at least 1/32 of them. */
    public fun deflate(raw: ByteArray, offset: Int, length: Int, level: Int): ByteArray? {
        if (length < 64) return null
        val deflater = deflaters.get() ?: Deflater(6).also { deflaters.set(it) }
        deflater.reset()
        deflater.setLevel(level)
        deflater.setInput(raw, offset, length)
        deflater.finish()
        val out = ByteArray(length - length / 32)
        var size = 0
        while (!deflater.finished()) {
            if (size == out.size) return null
            size += deflater.deflate(out, size, out.size - size)
        }
        return out.copyOf(size)
    }

    /** Inflates a blob back to exactly [rawLength] bytes. */
    public fun inflate(stored: ByteArray, rawLength: Int): ByteArray {
        val inflater = inflaters.get() ?: Inflater().also { inflaters.set(it) }
        inflater.reset()
        inflater.setInput(stored)
        val out = ByteArray(rawLength)
        try {
            var size = 0
            while (size < rawLength) {
                val n = inflater.inflate(out, size, rawLength - size)
                if (n == 0 && (inflater.finished() || inflater.needsInput() || inflater.needsDictionary())) break
                size += n
            }
            if (size != rawLength || !inflater.finished()) throw CorruptException("a compressed blob doesn't inflate to its recorded size")
        } catch (e: DataFormatException) {
            throw CorruptException("a compressed blob is damaged: ${e.message}", e)
        }
        return out
    }

    /** An index file's bytes on disk: [IndexFormat.encode]'s, deflated as a whole. */
    public fun deflateIndex(raw: ByteArray): ByteArray {
        val bytes = ByteArrayOutputStream(raw.size / 2 + 64)
        DeflaterOutputStream(bytes).use { it.write(raw) }
        return bytes.toByteArray()
    }

    /** An index file inflated, refusing one that inflates far past what a real index would. */
    public fun inflateIndex(file: ByteArray, name: String): ByteArray {
        val limit = IndexFormat.inflateLimit(file.size)
        val raw = try {
            InflaterInputStream(ByteArrayInputStream(file)).use { readUpTo(it, limit + 1) }
        } catch (e: java.io.IOException) {
            throw CorruptException("$name is damaged: ${e.message}", e)
        }
        if (raw.size > limit) throw CorruptException("$name is damaged: it inflates to more than ${Sizes.format(limit.toLong())}")
        return raw
    }
}

/** Up to [limit] bytes of [input], Java 8's way (no `readNBytes`). */
public fun readUpTo(input: InputStream, limit: Int): ByteArray {
    val out = ByteArrayOutputStream(minOf(limit, 64 * 1024))
    val buffer = ByteArray(64 * 1024)
    var total = 0
    while (total < limit) {
        val n = input.read(buffer, 0, minOf(buffer.size, limit - total))
        if (n < 0) break
        out.write(buffer, 0, n)
        total += n
    }
    return out.toByteArray()
}

/**
 * A bandwidth limit: callers say how many bytes they're about to move and are slowed to keep the
 * average under the limit. Shared by every reader of one kind (disk, upload), so the limit holds
 * however many threads there are.
 *
 * @param bytesPerSecond 0 for no limit
 */
public class Throttle(public val bytesPerSecond: Long) {
    private var available = bytesPerSecond
    private var last = System.nanoTime()

    /** Waits until [bytes] more fit under the limit. */
    public fun acquire(bytes: Long) {
        if (bytesPerSecond <= 0 || bytes <= 0) return
        val sleepNanos: Long
        synchronized(this) {
            val now = System.nanoTime()
            available = refill(available, now - last, bytesPerSecond)
            last = now
            available -= bytes
            sleepNanos = if (available >= 0) 0 else -available * 1_000_000_000L / bytesPerSecond
        }
        if (sleepNanos > 0) {
            try {
                Thread.sleep(sleepNanos / 1_000_000, (sleepNanos % 1_000_000).toInt())
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("stopped")
            }
        }
    }

    /** The stream, slowed to this limit. */
    public fun wrap(input: InputStream): InputStream {
        if (bytesPerSecond <= 0) return input
        return object : FilterInputStream(input) {
            override fun read(): Int {
                acquire(1)
                return super.read()
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = super.read(b, off, minOf(len, 64 * 1024))
                acquire(n.toLong())
                return n
            }
        }
    }

    public companion object {
        /** No limit. */
        @JvmField
        public val NONE: Throttle = Throttle(0)

        /**
         * What's available after [elapsedNanos]: never more than one second's worth. The elapsed
         * time is capped at a second before multiplying, so a long idle can't overflow.
         */
        internal fun refill(available: Long, elapsedNanos: Long, bytesPerSecond: Long): Long {
            val elapsed = maxOf(0, minOf(elapsedNanos, 1_000_000_000L))
            // A limit past about 9 GB/s would overflow the product: worked out in doubles there.
            val added = if (bytesPerSecond <= Long.MAX_VALUE / 1_000_000_000L) elapsed * bytesPerSecond / 1_000_000_000L
            else (elapsed.toDouble() * bytesPerSecond / 1e9).toLong()
            return if (added >= bytesPerSecond - available) bytesPerSecond else available + added
        }
    }
}

/**
 * Where a repository's files live: a folder, a bucket, a server. Every file is written once and
 * never changed, so a backend only needs to put, get, list and delete whole files.
 *
 * Names are relative paths with `/` between parts, such as `data/ab/ab12...`. A put must be all
 * or nothing: a file is visible under its name only once all of it is there.
 */
public interface Backend : Closeable {
    /** For messages: "the folder /srv/backups", "S3 bucket backups at r2.example.com". */
    public fun describe(): String

    /** Stores [file]'s bytes under [name], replacing any file already there. */
    public fun put(name: String, file: Path)

    /** Stores [data] under [name], replacing any file already there. */
    public fun put(name: String, data: ByteArray)

    /** The file's bytes from the start. @throws NoSuchFileException if there's no such file */
    public fun get(name: String): InputStream

    /**
     * [length] bytes of the file from [offset].
     *
     * @throws NoSuchFileException if there's no such file
     * @throws EOFException if the file is shorter
     */
    public fun read(name: String, offset: Long, length: Int): ByteArray

    /** Every file whose name starts with [prefix], at any depth, in no particular order. */
    public fun list(prefix: String): List<FileInfo>

    /** Removes the file. A file that isn't there is fine. */
    public fun delete(name: String)

    /** The file on the local disk, if this backend keeps files there; otherwise `null`. */
    public fun localFile(name: String): Path? = null

    override fun close() {}

    /** A file and its size in bytes. */
    public data class FileInfo(val name: String, val size: Long)

    public companion object {
        /** Throws unless [name] is a plain relative path that can't escape the backend's root. */
        @JvmStatic
        public fun checkName(name: String): String {
            require(!(name.isEmpty() || name.startsWith("/") || name.endsWith("/") || "\\" in name || "//" in name)) { "not a repository file name: $name" }
            for (part in name.split('/')) require(part != "." && part != "..") { "not a repository file name: $name" }
            return name
        }
    }
}

/** The whole file, read and checked: [Backend.get]'s bytes. */
public fun Backend.readAll(name: String): ByteArray = get(name).use { it.readBytes() }

/**
 * A repository in a folder: on the server's disk, a second disk or a network mount. Files are
 * written to `tmp/`, flushed to disk, then renamed into place, so a crash leaves either the whole
 * file or none of it under its name.
 */
public class LocalBackend(root: Path) : Backend {
    public val root: Path = root.toAbsolutePath().normalize()

    override fun describe(): String = "the folder $root"

    /** A new, empty file name in the backend's own `tmp/`, on the same disk as its files. */
    public fun newTemp(suffix: String): Path {
        val tmp = root.resolve(TMP)
        Files.createDirectories(tmp)
        return tmp.resolve(UUID.randomUUID().toString() + suffix)
    }

    override fun put(name: String, file: Path) {
        val target = resolve(name)
        var source = file.toAbsolutePath().normalize()
        var copy: Path? = null
        if (!source.startsWith(root.resolve(TMP))) {
            // Copy onto this disk first, so the final step is a rename.
            copy = newTemp(".part")
            source = copy
        }
        try {
            if (copy != null) Files.copy(file.toAbsolutePath().normalize(), copy, StandardCopyOption.REPLACE_EXISTING)
            sync(source)
            makeFolders(target.parent)
            move(source, target)
        } catch (e: Exception) {
            // A disk that filled up mid-copy mustn't keep the partial copy, or it stays full.
            if (copy != null) {
                try {
                    Files.deleteIfExists(copy)
                } catch (ignored: java.io.IOException) {
                    // The next tidy of tmp/ takes it.
                }
            }
            throw e
        }
        syncDirectory(target.parent)
    }

    override fun put(name: String, data: ByteArray) {
        val temp = newTemp(".part")
        try {
            FileChannel.open(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(data)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
        } catch (e: java.io.IOException) {
            Files.deleteIfExists(temp)
            throw e
        }
        val target = resolve(name)
        makeFolders(target.parent)
        move(temp, target)
        syncDirectory(target.parent)
    }

    // Makes the folders a file goes in, and flushes each new one's entry in its parent, so after
    // a power cut a new data/ab/ is there along with the file renamed into it.
    private fun makeFolders(folder: Path) {
        if (Files.isDirectory(folder)) return
        val made = ArrayList<Path>()
        var at: Path? = folder
        while (at != null && !Files.isDirectory(at)) {
            made.add(at)
            at = at.parent
        }
        Files.createDirectories(folder)
        for (path in made) path.parent?.let { syncDirectory(it) }
    }

    override fun get(name: String): InputStream = Files.newInputStream(resolve(name))

    override fun read(name: String, offset: Long, length: Int): ByteArray =
        FileChannel.open(resolve(name), StandardOpenOption.READ).use { readFully(it, offset, length, name) }

    override fun list(prefix: String): List<Backend.FileInfo> {
        val files = ArrayList<Backend.FileInfo>()
        // List from the deepest folder the prefix names, then filter.
        val slash = prefix.lastIndexOf('/')
        val start = if (slash < 0) root else root.resolve(prefix.substring(0, slash))
        if (!Files.isDirectory(start)) return files
        val tmp = root.resolve(TMP)
        Files.walk(start).use { walk ->
            for (path in walk) {
                if (path.startsWith(tmp) || !Files.isRegularFile(path)) continue
                val name = root.relativize(path).toString().replace('\\', '/')
                if (name.startsWith(prefix)) {
                    try {
                        files += Backend.FileInfo(name, Files.size(path))
                    } catch (gone: NoSuchFileException) {
                        // Deleted while listing.
                    }
                }
            }
        }
        return files
    }

    override fun delete(name: String) {
        Files.deleteIfExists(resolve(name))
    }

    override fun localFile(name: String): Path = resolve(name)

    /** Clears what crashed writes left in `tmp/`. Only while nothing is writing. */
    public fun clearTemp() {
        val tmp = root.resolve(TMP)
        if (!Files.isDirectory(tmp)) return
        Files.list(tmp).use { files -> for (file in files) Files.deleteIfExists(file) }
    }

    private fun resolve(name: String): Path = root.resolve(Backend.checkName(name))

    public companion object {
        public const val TMP: String = "tmp"

        private fun move(source: Path, target: Path) {
            try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                // Some network mounts can't replace in one step. Content-named files are the same
                // bytes whoever wrote them, so a plain replace is still safe.
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: FileAlreadyExistsException) {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
            }
        }

        /** Flushes a file's bytes to the disk. */
        @JvmStatic
        public fun sync(file: Path) {
            FileChannel.open(file, StandardOpenOption.WRITE).use { it.force(true) }
        }

        /** Flushes a folder's entries, so a rename survives a power cut. Not every system can. */
        @JvmStatic
        public fun syncDirectory(directory: Path) {
            try {
                FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
            } catch (e: java.io.IOException) {
                // Windows can't open a folder this way; its renames are durable on their own.
            } catch (e: UnsupportedOperationException) {
                // Likewise.
            }
        }

        /** [length] bytes from [position], or [EOFException] if the file is shorter. */
        @JvmStatic
        public fun readFully(channel: FileChannel, position: Long, length: Int, what: String): ByteArray {
            val buffer = ByteBuffer.allocate(length)
            var at = position
            while (buffer.hasRemaining()) {
                val n = channel.read(buffer, at)
                if (n < 0) throw EOFException("$what is shorter than ${position + length} bytes")
                at += n
            }
            return buffer.array()
        }
    }
}
