package co.xenastudios.xbackup.backup

import co.xenastudios.xbackup.store.CorruptException
import co.xenastudios.xbackup.store.Globs
import co.xenastudios.xbackup.store.Hash
import co.xenastudios.xbackup.store.RegionLayout
import co.xenastudios.xbackup.store.Repository
import co.xenastudios.xbackup.store.Tree
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Which paths below the server folder a backup takes: the sources it includes, minus the owner's
 * exclude patterns and xBackup's own folders. Paths are relative, with `/`.
 *
 * @param includes relative paths to take; "" for everything
 * @param excludes glob patterns matched against relative paths, such as `logs/` + `**`
 * @param skip absolute paths never taken: the repository, the restore folders
 */
internal class Selection(includes: List<String>, excludes: List<String>, skip: Set<Path>) {
    enum class Choice {
        /** Back it up, and everything in it. */
        INCLUDE,

        /** A folder on the way to something included: look inside, take only that. */
        DESCEND,
        SKIP,
    }

    private val includes = includes.toList()
    private val excludes = excludes.map { it.trim().replace('\\', '/') }.filter { it.isNotEmpty() }.map(Globs::regex)
    private val skip = HashSet<Path>()

    // The skipped paths' real places, to find them again through a source that is a link.
    private val realSkip = HashSet<Path>()

    init {
        for (path in skip) {
            val absolute = path.toAbsolutePath().normalize()
            this.skip.add(absolute)
            realSkip.add(
                try {
                    absolute.toRealPath()
                } catch (e: java.io.IOException) {
                    absolute
                },
            )
        }
    }

    /** Whether the path is one of the sources itself (not just inside one). */
    fun isSource(path: String): Boolean = path in includes

    /**
     * A source reached through a link: anything skipped whose real place is inside the link's
     * target is skipped under the link's path too, such as the repository inside a plugins folder
     * that is a link.
     */
    fun alias(link: Path, target: Path) {
        val logical = link.toAbsolutePath().normalize()
        for (real in realSkip) {
            if (real.startsWith(target)) skip.add(logical.resolve(target.relativize(real).toString()).normalize())
        }
    }

    fun choose(path: String, absolute: Path): Choice {
        if (absolute.toAbsolutePath().normalize() in skip || excluded(path)) return Choice.SKIP
        var choice = Choice.SKIP
        for (include in includes) {
            if (include.isEmpty() || path == include || path.startsWith("$include/")) return Choice.INCLUDE
            if (include.startsWith("$path/")) choice = Choice.DESCEND
        }
        return choice
    }

    fun excluded(path: String): Boolean = excludes.any { it.matches(path) }
}

/**
 * What an earlier read found at each path, so unchanged files and chunks are reused instead of
 * read again: the last snapshot, or the first pass of this backup.
 */
internal interface Reference {
    /** The earlier entry at [path], with when it was read; `null` if there's none. */
    fun find(path: String): Previous?

    /** A region layout an entry points to; `null` if it can't be read (a lost pack). */
    fun region(id: Hash): RegionLayout?

    /**
     * An earlier entry.
     *
     * @param readAt when that read started, in milliseconds: a file modified within a moment of it
     *     may have changed again without its size or time showing it
     */
    class Previous(val node: Tree.Node, val readAt: Long)

    object None : Reference {
        override fun find(path: String): Previous? = null

        override fun region(id: Hash): RegionLayout? = null
    }

    /** A snapshot's tree, walked as paths are asked for. */
    class Of(private val repository: Repository, private val root: Hash, private val readAt: Long) : Reference {
        private val folders = HashMap<String, Tree?>()

        override fun find(path: String): Previous? {
            val slash = path.lastIndexOf('/')
            val folder = folder(if (slash < 0) "" else path.substring(0, slash))
            val node = folder?.get(path.substring(slash + 1)) ?: return null
            return Previous(node, readAt)
        }

        private fun folder(path: String): Tree? {
            if (folders.containsKey(path)) return folders[path]
            val tree = if (path.isEmpty()) {
                read(root)
            } else {
                val slash = path.lastIndexOf('/')
                val parent = folder(if (slash < 0) "" else path.substring(0, slash))
                val node = parent?.get(path.substring(slash + 1))
                if (node != null && node.kind == Tree.Kind.DIR) read(node.ref!!) else null
            }
            // Folders are visited in order, so only the path being walked needs keeping.
            if (folders.size > 256) folders.clear()
            folders[path] = tree
            return tree
        }

        // A folder whose listing went with a lost pack is read again, not reused.
        private fun read(id: Hash): Tree? = try {
            repository.tree(id)
        } catch (e: CorruptException) {
            null
        }

        override fun region(id: Hash): RegionLayout? = try {
            repository.region(id)
        } catch (e: CorruptException) {
            null
        }
    }

    /** What the first pass read, on top of another reference for everything it didn't. */
    class Overlay(private val nodes: Map<String, Tree.Node>, private val regions: Map<Hash, RegionLayout>, private val readAt: Long, private val under: Reference) : Reference {
        override fun find(path: String): Previous? = nodes[path]?.let { Previous(it, readAt) } ?: under.find(path)

        override fun region(id: Hash): RegionLayout? = regions[id] ?: under.region(id)
    }
}

/**
 * Reads Minecraft's region files (`.mca`): an 8 KiB header of 1,024 locations and 1,024
 * timestamps, then chunks in 4 KiB sectors, each a 4-byte length, a compression byte and the
 * payload. Used for chunk, entity and POI regions alike.
 */
internal object RegionFiles {
    /** The header: locations, then timestamps. */
    class Header(val location: IntArray, val timestamp: IntArray)

    fun header(channel: FileChannel): Header {
        val bytes = co.xenastudios.xbackup.store.LocalBackend.readFully(channel, 0, RegionLayout.HEADER, "the region file")
        val location = IntArray(RegionLayout.CHUNKS)
        val timestamp = IntArray(RegionLayout.CHUNKS)
        for (i in 0 until RegionLayout.CHUNKS) {
            location[i] = int(bytes, i * 4)
            timestamp[i] = int(bytes, RegionLayout.SECTOR + i * 4)
        }
        return Header(location, timestamp)
    }

    /**
     * One chunk's record: its 4-byte length, compression byte and payload, as on disk.
     *
     * @throws java.io.IOException if the length doesn't fit the chunk's sectors or the file
     */
    fun record(channel: FileChannel, location: Int, fileLength: Long): ByteArray {
        val start = RegionLayout.sectorOffset(location) * RegionLayout.SECTOR
        val sectors = RegionLayout.sectorCount(location)
        val length = int(co.xenastudios.xbackup.store.LocalBackend.readFully(channel, start, 4, "the region file"), 0)
        if (length <= 0 || length + 4L > sectors.toLong() * RegionLayout.SECTOR || start + 4 + length > fileLength) {
            throw java.io.IOException("a chunk's length ($length) doesn't fit its $sectors sectors")
        }
        return co.xenastudios.xbackup.store.LocalBackend.readFully(channel, start, length + 4, "the region file")
    }

    /** One chunk's location and timestamp, read again from the header. */
    fun entry(channel: FileChannel, chunk: Int): Long {
        val location = int(co.xenastudios.xbackup.store.LocalBackend.readFully(channel, chunk * 4L, 4, "the region file"), 0)
        val timestamp = int(co.xenastudios.xbackup.store.LocalBackend.readFully(channel, RegionLayout.SECTOR + chunk * 4L, 4, "the region file"), 0)
        return (location.toLong() shl 32) or (timestamp.toLong() and 0xFFFFFFFFL)
    }

    /** Whether the record's payload is uncompressed (compression type 3), so worth deflating. */
    fun uncompressed(record: ByteArray): Boolean = record.size > 4 && (record[4].toInt() and 0x7F) == 3

    fun int(bytes: ByteArray, at: Int): Int = (bytes[at].toInt() and 0xFF shl 24) or (bytes[at + 1].toInt() and 0xFF shl 16) or
        (bytes[at + 2].toInt() and 0xFF shl 8) or (bytes[at + 3].toInt() and 0xFF)
}

/** The path relative to [root] with `/`, "" for the root itself, or `null` if it's outside. */
public fun relative(root: Path, path: Path): String? {
    val absolute = path.toAbsolutePath().normalize()
    val base = root.toAbsolutePath().normalize()
    if (!absolute.startsWith(base)) return null
    return base.relativize(absolute).toString().replace('\\', '/')
}

/** [text] as a path on this machine. */
internal fun path(text: String): Path = Paths.get(text)
