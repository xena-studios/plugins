package co.xenastudios.xbackup.backup

import co.xenastudios.xbackup.store.BlobType
import co.xenastudios.xbackup.store.Chunker
import co.xenastudios.xbackup.store.Hash
import co.xenastudios.xbackup.store.Hashes
import co.xenastudios.xbackup.store.RegionLayout
import co.xenastudios.xbackup.store.Session
import co.xenastudios.xbackup.store.Throttle
import co.xenastudios.xbackup.store.Tree
import java.nio.channels.FileChannel
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.security.DigestInputStream
import java.util.UUID

/**
 * Walks folders and stores what changed: whole files cut into pieces, region files chunk by
 * chunk, SQLite databases through a consistent copy. Anything unchanged since the [Reference] is
 * reused by id without being read.
 *
 * Two modes. **Strict** is the real read: trees and region layouts are stored, and the result is
 * a snapshot's tree. **Loose** is a first pass while the server is still saving: it stores only
 * content, and remembers anything it could read consistently, so the strict pass during the
 * autosave pause only has to read what changed after it.
 *
 * @param ready entries already worked out, by path, used instead of reading the path (the worlds,
 *     read during the pause)
 * @param databases how to copy databases; `null` to leave them out with a warning
 * @param known databases by real path that are SQLite whatever their name (the family's)
 * @param temp where database copies go while they're stored
 */
internal class Scanner(
    private val session: Session,
    private val reference: Reference,
    private val strict: Boolean,
    root: Path,
    private val selection: Selection,
    private val ready: Map<String, Tree.Node>,
    private val throttle: Throttle,
    private val databases: Databases?,
    private val known: Set<String>,
    private val temp: Path,
    private val cancelled: () -> Boolean,
) {
    private val realRoot: Path = try {
        root.toRealPath()
    } catch (e: java.io.IOException) {
        root
    }

    // Loose mode's findings.
    val nodes = HashMap<String, Tree.Node>()
    val regions = HashMap<Hash, RegionLayout>()

    var files = 0L
    var bytes = 0L
    var chunksRead = 0L
    var chunksReused = 0L
    val warnings = ArrayList<String>()

    /** The stopped signal: the backup was cancelled. */
    class Cancelled : java.io.IOException("The backup was stopped.")

    private fun checkCancelled() {
        if (cancelled()) throw Cancelled()
    }

    private fun warn(warning: String) {
        if (warnings.size < MAX_WARNINGS) warnings += warning else if (warnings.size == MAX_WARNINGS) warnings += "and more; the console has the rest."
    }

    /** Scans the root folder; strict mode returns its entry, loose mode `null`. */
    fun scanRoot(root: Path): Tree.Node? = folder(root, "", "root", Files.readAttributes(root, BasicFileAttributes::class.java))

    /**
     * Scans one source below the root; strict mode returns its entry, loose mode `null`. A source
     * that is a link is followed, so a world kept on another disk behind a link is backed up as
     * the folder it is.
     */
    fun scan(file: Path, path: String): Tree.Node? {
        val attributes = sourceAttributes(file, path) ?: return null
        return entry(file, path, path.substring(path.lastIndexOf('/') + 1), attributes, emptySet())
    }

    private fun sourceAttributes(file: Path, path: String): BasicFileAttributes? {
        val attributes = try {
            Files.readAttributes(file, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (e: NoSuchFileException) {
            return null
        }
        if (!attributes.isSymbolicLink) return attributes
        return try {
            val followed = Files.readAttributes(file, BasicFileAttributes::class.java)
            if (followed.isDirectory) selection.alias(file, file.toRealPath())
            followed
        } catch (e: NoSuchFileException) {
            warn("${display(path)} is a link to ${Files.readSymbolicLink(file)}, which doesn't exist; it was left out.")
            null
        }
    }

    private fun entry(file: Path, path: String, name: String, attributes: BasicFileAttributes, siblings: Set<String>): Tree.Node? {
        ready[path]?.let { return it.named(name) }
        if (attributes.isSymbolicLink) {
            if (strict) warnIfLeadsOutside(file, path)
            return Tree.Node.link(name, attributes.lastModifiedTime().toMillis(), Files.readSymbolicLink(file).toString().replace('\\', '/'))
        }
        if (attributes.isDirectory) return folder(file, path, name, attributes)
        if (!attributes.isRegularFile) return null
        if (name.endsWith(".mca") && attributes.size() >= RegionLayout.HEADER) return region(file, path, name, attributes)
        if (isDatabase(file, name, attributes, siblings)) return database(file, path, name, attributes)
        return file(file, path, name, attributes)
    }

    // Only the link is kept, never what it points to: say so when that's a folder outside the
    // server folder, whose files then aren't in the backup.
    private fun warnIfLeadsOutside(link: Path, path: String) {
        try {
            val target = link.toRealPath()
            if (Files.isDirectory(target) && !target.startsWith(realRoot)) {
                warn("${display(path)} is a link to $target, outside the server folder: only the link is backed up, not what's in it. Add that folder as a source, or keep it inside the server folder.")
            }
        } catch (e: java.io.IOException) {
            // A dangling link: there's nothing it points to that could be missed.
        }
    }

    // ---- folders ----

    private fun folder(dir: Path, path: String, name: String, attributes: BasicFileAttributes): Tree.Node? {
        checkCancelled()
        val children = ArrayList<Path>()
        try {
            Files.newDirectoryStream(dir).use { stream -> for (child in stream) children.add(child) }
        } catch (e: NoSuchFileException) {
            return null
        } catch (e: AccessDeniedException) {
            warn("Couldn't read the folder ${display(path)}: access denied.")
            return null
        }
        val names = children.map { it.fileName.toString() }.toHashSet()
        // A database's journal files are part of its copy, not files of their own.
        val skip = HashSet<String>()
        for (child in names) {
            if (isDatabaseName(child, names) || databaseByPath(dir.resolve(child))) {
                skip += "$child-wal"
                skip += "$child-shm"
                skip += "$child-journal"
            }
        }
        children.sortWith { x, y -> Tree.compareNames(x.toString(), y.toString()) }
        val entries = ArrayList<Tree.Node>()
        for (child in children) {
            val childName = child.fileName.toString()
            val childPath = if (path.isEmpty()) childName else "$path/$childName"
            val choice = selection.choose(childPath, child)
            if (choice == Selection.Choice.SKIP || childName in skip && stripJournal(childName) in names) continue
            val childAttributes = if (choice == Selection.Choice.DESCEND || selection.isSource(childPath)) {
                // A source, or a folder on the way to one: followed if it's a link.
                sourceAttributes(child, childPath) ?: continue
            } else {
                try {
                    Files.readAttributes(child, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                } catch (e: NoSuchFileException) {
                    continue
                }
            }
            if (choice == Selection.Choice.DESCEND && !childAttributes.isDirectory && childPath !in ready) continue
            val node = if (choice == Selection.Choice.DESCEND && childAttributes.isDirectory && childPath !in ready) {
                folder(child, childPath, childName, childAttributes)
            } else {
                entry(child, childPath, childName, childAttributes, names)
            }
            if (node != null) entries += node
        }
        if (!strict) return null
        val tree = session.storeTree(Tree(entries))
        return Tree.Node.dir(name, attributes.lastModifiedTime().toMillis(), mode(dir), tree)
    }

    private fun stripJournal(name: String): String {
        for (suffix in JOURNALS) if (name.endsWith(suffix)) return name.substring(0, name.length - suffix.length)
        return name
    }

    // ---- files ----

    private fun file(file: Path, path: String, name: String, attributes: BasicFileAttributes): Tree.Node? {
        checkCancelled()
        val size = attributes.size()
        var mtime = attributes.lastModifiedTime().toMillis()
        val previous = reference.find(path)
        if (previous != null && unchanged(previous, Tree.Kind.FILE, size, mtime) && stored(previous.node.blobs)) {
            return previous.node.named(name).also(::count)
        }
        var attempt = 0
        while (true) {
            val content = try {
                store(file)
            } catch (e: NoSuchFileException) {
                return null
            } catch (e: AccessDeniedException) {
                warn("Couldn't read ${display(path)}: access denied.")
                return null
            }
            val after = try {
                Files.readAttributes(file, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (e: NoSuchFileException) {
                return null
            }
            val steady = after.size() == content.size && after.lastModifiedTime().toMillis() == mtime
            // Changing as we read: the strict pass will read it again.
            if (!steady && !strict) return null
            if (steady || attempt >= 2) {
                if (!steady) warn("${display(path)} kept changing while it was read; its copy may be mid-write.")
                val node = Tree.Node.file(name, mtime, content.size, mode(file), content.blobs, content.hash)
                remember(path, node, previous)
                count(node)
                return node
            }
            mtime = after.lastModifiedTime().toMillis()
            attempt++
        }
    }

    private class Content(val blobs: List<Hash>, val size: Long, val hash: Hash)

    private fun store(file: Path): Content {
        val digest = Hashes.digest()
        val blobs = ArrayList<Hash>()
        var size = 0L
        DigestInputStream(throttle.wrap(Files.newInputStream(file)), digest).use { input ->
            val chunker = Chunker { b, off, len -> input.read(b, off, len) }
            while (true) {
                val piece = chunker.next() ?: break
                checkCancelled()
                blobs += session.store(BlobType.DATA, piece, true)
                size += piece.size
            }
        }
        return Content(blobs, size, Hashes.of(digest))
    }

    private fun unchanged(previous: Reference.Previous, kind: Tree.Kind, size: Long, mtime: Long): Boolean {
        val node = previous.node
        return node.kind == kind && node.size == size && node.mtime == mtime && mtime < previous.readAt - RACY_MILLIS
    }

    // Reused ids must still be in the repository: a repaired repository may have lost some.
    private fun stored(blobs: List<Hash>): Boolean = blobs.all { session.has(it) }

    private fun remember(path: String, node: Tree.Node, previous: Reference.Previous?) {
        if (!strict && (previous == null || previous.node != node)) nodes[path] = node
    }

    private fun count(node: Tree.Node) {
        files++
        bytes += node.size
    }

    // ---- regions ----

    private fun region(file: Path, path: String, name: String, attributes: BasicFileAttributes): Tree.Node? {
        checkCancelled()
        val previous = reference.find(path)
        val before = if (previous != null && previous.node.kind == Tree.Kind.REGION) reference.region(previous.node.ref!!) else null
        // Chunk timestamps are whole seconds, and the server alternates a chunk between two sector
        // slots, so a chunk written again in the same second as the earlier read can have the same
        // place and time: only chunks stamped before that second are reused.
        val readAtSecond = if (previous == null) 0 else Math.floorDiv(previous.readAt, 1000L)
        for (attempt in 0 until 3) {
            val layout = try {
                FileChannel.open(file, StandardOpenOption.READ).use { readRegion(it, before, readAtSecond) }
            } catch (e: NoSuchFileException) {
                return null
            } catch (e: AccessDeniedException) {
                warn("Couldn't read ${display(path)}: access denied.")
                return null
            }
            if (layout === UNREADABLE) break
            // Changed under us in strict mode: read it again.
            if (layout == null) continue
            val mtime = attributes.lastModifiedTime().toMillis()
            if (!strict) {
                if (layout != before) {
                    val id = Hashes.sha256(layout.overlayKey())
                    regions[id] = layout
                    nodes[path] = Tree.Node.region(name, mtime, layout.fileLength, mode(file), id)
                }
                return null
            }
            val id = session.storeRegion(layout)
            return Tree.Node.region(name, mtime, layout.fileLength, mode(file), id).also(::count)
        }
        if (!strict) return null
        // A header that doesn't add up, or a file that kept changing: keep its exact bytes.
        return file(file, path, name, attributes)
    }

    /**
     * Reads a region's layout, storing chunks that changed. Returns [UNREADABLE] for a file to
     * keep whole, or `null` in strict mode if a chunk moved while it was read. In loose mode a
     * chunk that moved is left out (as if absent), for the strict pass to read.
     */
    private fun readRegion(channel: FileChannel, before: RegionLayout?, readAtSecond: Long): RegionLayout? {
        val fileLength = channel.size()
        if (fileLength < RegionLayout.HEADER) return UNREADABLE
        val header = RegionFiles.header(channel)
        if (RegionLayout.problem(header.location, fileLength) != null) return UNREADABLE
        val location = header.location.copyOf()
        val timestamp = header.timestamp.copyOf()
        val recordLength = IntArray(RegionLayout.CHUNKS)
        val blob = arrayOfNulls<Hash>(RegionLayout.CHUNKS)
        for (i in 0 until RegionLayout.CHUNKS) {
            if (location[i] == 0) continue
            val old = before?.blob(i)
            if (before != null && old != null && before.location(i) == location[i] && before.timestamp(i) == timestamp[i] &&
                (timestamp[i].toLong() and 0xFFFFFFFFL) < readAtSecond && session.has(old)
            ) {
                blob[i] = old
                recordLength[i] = before.recordLength(i)
                chunksReused++
                continue
            }
            if (i and 63 == 0) checkCancelled()
            val record = try {
                RegionFiles.record(channel, location[i], fileLength)
            } catch (e: java.io.IOException) {
                if (!strict) {
                    location[i] = 0
                    timestamp[i] = 0
                    continue
                }
                return UNREADABLE
            }
            throttle.acquire(record.size.toLong())
            // The server writes a moved chunk to new sectors before pointing the header at them,
            // and never writes into sectors the header points at. So if the entry is the same
            // after the read as before, the bytes read are that entry's.
            val entry = RegionFiles.entry(channel, i)
            if (entry != ((location[i].toLong() shl 32) or (timestamp[i].toLong() and 0xFFFFFFFFL))) {
                if (strict) return null
                location[i] = 0
                timestamp[i] = 0
                continue
            }
            blob[i] = session.store(BlobType.DATA, record, RegionFiles.uncompressed(record))
            recordLength[i] = record.size
            chunksRead++
        }
        return RegionLayout(fileLength, location, timestamp, recordLength, blob)
    }

    // ---- databases ----

    private fun databaseByPath(file: Path): Boolean = known.isNotEmpty() && Databases.Family.realPath(file) in known

    private fun isDatabase(file: Path, name: String, attributes: BasicFileAttributes, siblings: Set<String>): Boolean {
        if (attributes.size() < 512 || !(isDatabaseName(name, siblings) || databaseByPath(file))) return false
        return try {
            Files.newInputStream(file).use { input ->
                val head = ByteArray(SQLITE_MAGIC.size)
                var read = 0
                while (read < head.size) {
                    val n = input.read(head, read, head.size - read)
                    if (n < 0) break
                    read += n
                }
                read == head.size && head.contentEquals(SQLITE_MAGIC)
            }
        } catch (e: java.io.IOException) {
            false
        }
    }

    private fun database(file: Path, path: String, name: String, attributes: BasicFileAttributes): Tree.Node? {
        checkCancelled()
        val size = attributes.size()
        val mtime = attributes.lastModifiedTime().toMillis()
        val wal = file.resolveSibling("$name-wal")
        var walSize = -1L
        var walMtime = -1L
        try {
            val walAttributes = Files.readAttributes(wal, BasicFileAttributes::class.java)
            walSize = walAttributes.size()
            walMtime = walAttributes.lastModifiedTime().toMillis()
        } catch (e: NoSuchFileException) {
            // Not in WAL mode, or checkpointed and closed.
        }
        val previous = reference.find(path)
        if (previous != null && previous.node.kind == Tree.Kind.SQLITE && previous.node.size == size && previous.node.mtime == mtime &&
            previous.node.walSize == walSize && previous.node.walMtime == walMtime && maxOf(mtime, walMtime) < previous.readAt - RACY_MILLIS &&
            stored(previous.node.blobs)
        ) {
            return previous.node.named(name).also(::count)
        }
        // Databases are copied once, in the strict pass.
        if (!strict) return null
        var reason: String
        if (databases == null) {
            reason = "SQLite isn't available to copy it safely"
        } else if (!roomFor(size + maxOf(walSize, 0))) {
            reason = "there isn't room on the disk to copy it and keep retention.min-free-space free"
        } else {
            Files.createDirectories(temp)
            val copy = temp.resolve("db-" + UUID.randomUUID() + ".db")
            try {
                databases.copy(file, copy)
                val content = store(copy)
                return Tree.Node.database(name, mtime, size, mode(file), content.blobs, content.size, content.hash, walSize, walMtime).also(::count)
            } catch (e: Cancelled) {
                throw e
            } catch (e: java.io.IOException) {
                reason = e.message ?: e.toString()
            } finally {
                Files.deleteIfExists(copy)
            }
        }
        // Never copy a live database's raw bytes: it could be mid-write. Keep the last good copy
        // if there is one.
        if (previous != null && previous.node.kind == Tree.Kind.SQLITE && stored(previous.node.blobs)) {
            warn("${display(path)}: kept the previous copy, because $reason.")
            return previous.node.named(name).also(::count)
        }
        warn("${display(path)} was left out, because $reason.")
        return null
    }

    // A database's copy is about its size, and goes in the temp folder: it mustn't take the disk
    // under the free space the server needs, which is often the disk the world is on.
    private fun roomFor(bytes: Long): Boolean = try {
        val at = if (Files.exists(temp)) temp else temp.parent
        Files.getFileStore(at).usableSpace - bytes >= session.minFreeBytes
    } catch (e: java.io.IOException) {
        true // Can't tell: the copy itself fails cleanly if the disk fills.
    }

    companion object {
        /** A file modified this close to an earlier read may have changed again unseen. */
        const val RACY_MILLIS: Long = 2_000
        private val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
        private val DATABASE_ENDINGS = listOf(".db", ".sqlite", ".sqlite3", ".db3")
        private val JOURNALS = listOf("-wal", "-shm", "-journal")
        private const val MAX_WARNINGS = 20

        // Marks a layout that can't be kept chunk by chunk.
        private val UNREADABLE = RegionLayout(0, IntArray(RegionLayout.CHUNKS), IntArray(RegionLayout.CHUNKS), IntArray(RegionLayout.CHUNKS), arrayOfNulls(RegionLayout.CHUNKS))

        fun isDatabaseName(name: String, siblings: Set<String>): Boolean {
            val lower = name.lowercase()
            return DATABASE_ENDINGS.any { lower.endsWith(it) } || "$name-wal" in siblings || "$name-journal" in siblings
        }

        fun mode(file: Path): Int = try {
            var mode = 0
            for (permission in Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS)) mode = mode or (1 shl (8 - permission.ordinal))
            mode
        } catch (e: UnsupportedOperationException) {
            -1
        } catch (e: java.io.IOException) {
            -1
        }

        fun setMode(file: Path, mode: Int) {
            if (mode < 0) return
            val permissions = java.util.EnumSet.noneOf(PosixFilePermission::class.java)
            for (permission in PosixFilePermission.values()) if (mode and (1 shl (8 - permission.ordinal)) != 0) permissions += permission
            try {
                Files.setPosixFilePermissions(file, permissions)
            } catch (e: UnsupportedOperationException) {
                // Not a POSIX system.
            } catch (e: java.io.IOException) {
                // Not ours to change.
            }
        }

        fun display(path: String): String = if (path.isEmpty()) "the server folder" else path
    }
}
