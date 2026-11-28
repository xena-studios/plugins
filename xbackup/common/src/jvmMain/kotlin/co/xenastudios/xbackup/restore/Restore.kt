package co.xenastudios.xbackup.restore

import co.xenastudios.xbackup.backup.Scanner
import co.xenastudios.xbackup.store.CorruptException
import co.xenastudios.xbackup.store.Hashes
import co.xenastudios.xbackup.store.LocalBackend
import co.xenastudios.xbackup.store.RegionLayout
import co.xenastudios.xbackup.store.Repository
import co.xenastudios.xbackup.store.Snapshot
import co.xenastudios.xbackup.store.Throttle
import co.xenastudios.xbackup.store.Tree
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.security.DigestOutputStream

/**
 * Getting files back out of a snapshot: finding a path in it, saying what restoring it would
 * change, and writing it out to a folder with every file checked against the hash it was saved
 * with. Putting the result in place is [Swap]'s job.
 */
public object Restore {
    /** A path in a snapshot and its entry. "" is the whole snapshot. */
    public class Item(public val path: String, public val node: Tree.Node)

    /** Finds [path] in [snapshot]; `null` if the snapshot doesn't have it. */
    @JvmStatic
    public fun find(repository: Repository, snapshot: Snapshot, path: String): Tree.Node? {
        var node = Tree.Node.dir("root", 0, -1, snapshot.tree)
        if (path.isEmpty()) return node
        for (part in path.split('/')) {
            if (node.kind != Tree.Kind.DIR) return null
            node = repository.tree(node.ref!!)[part] ?: return null
        }
        return node
    }

    /**
     * Splits the paths into what can be swapped in whole: a path that contains a protected one
     * (xBackup's own folder, its jar, the repository) is replaced by its children, and the
     * protected paths themselves are left out.
     */
    @JvmStatic
    public fun expand(repository: Repository, snapshot: Snapshot, paths: List<String>, protect: Set<String>): List<Item> {
        val items = ArrayList<Item>()
        val seen = HashSet<String>()
        for (path in paths) {
            val node = find(repository, snapshot, path) ?: throw NoSuchFileException("Snapshot ${snapshot.key.shortHex()} has no $path.")
            expand(repository, path, node, protect, items, seen)
        }
        return items
    }

    private fun expand(repository: Repository, path: String, node: Tree.Node, protect: Set<String>, items: MutableList<Item>, seen: MutableSet<String>) {
        // Compared ignoring case: on Windows and macOS, Plugins/XBACKUP is plugins/xBackup.
        val lower = path.lowercase()
        var containsProtected = path.isEmpty()
        for (p in protect) {
            val protectedPath = p.lowercase()
            if (protectedPath == lower) return
            if (protectedPath.startsWith("$lower/")) containsProtected = true
        }
        if (!containsProtected || node.kind != Tree.Kind.DIR) {
            if (seen.add(path)) items += Item(path, node)
            return
        }
        for (child in repository.tree(node.ref!!).nodes) expand(repository, join(path, child.name), child, protect, items, seen)
    }

    // ---- dry run ----

    /**
     * What a restore would change, compared with what's on disk now.
     *
     * @param changedChunks region chunks that differ, across all region files
     * @param examples a few of the changed paths
     */
    public class Plan(
        public val items: List<Item>,
        public val files: Long,
        public val bytes: Long,
        public val added: Int,
        public val changed: Int,
        public val removed: Int,
        public val changedChunks: Long,
        public val examples: List<String>,
    )

    /** Compares the items with the files under [root], reading only metadata and region headers. */
    @JvmStatic
    public fun dryRun(repository: Repository, items: List<Item>, root: Path): Plan {
        val counter = Counter()
        val base = root.toAbsolutePath().normalize()
        for (item in items) {
            var disk = base.resolve(item.path).normalize()
            if (!disk.startsWith(base)) throw java.io.IOException("${item.path} is outside $base.")
            // A folder kept elsewhere behind a link is restored where the link leads.
            if (item.node.kind == Tree.Kind.DIR && Files.isSymbolicLink(disk) && Files.isDirectory(disk)) disk = disk.toRealPath()
            compare(repository, item.path, item.node, disk, disk, counter)
        }
        return Plan(items, counter.files, counter.bytes, counter.added, counter.changed, counter.removed, counter.chunks, counter.examples.toList())
    }

    private class Counter {
        var files = 0L
        var bytes = 0L
        var added = 0
        var changed = 0
        var removed = 0
        var chunks = 0L
        val examples = ArrayList<String>()

        fun example(text: String) {
            if (examples.size < 6) examples += text
        }
    }

    // Everything read stays inside the item's own folder (or file): names come from the backup.
    private fun compare(repository: Repository, path: String, node: Tree.Node, base: Path, at: Path, counter: Counter) {
        val disk = at.normalize()
        if (!disk.startsWith(base)) throw java.io.IOException("$path is outside $base.")
        val attributes = attributes(disk)
        if (node.kind == Tree.Kind.DIR) {
            val tree = repository.tree(node.ref!!)
            if (attributes == null || !attributes.isDirectory) {
                counter.added += countFiles(repository, tree, counter)
                counter.example("+ $path/ (comes back)")
                return
            }
            val names = HashSet<String>()
            for (child in tree.nodes) {
                names += child.name
                compare(repository, join(path, child.name), child, base, disk.resolve(child.name), counter)
            }
            Files.newDirectoryStream(disk).use { stream ->
                for (child in stream) {
                    val name = child.fileName.toString()
                    // The server makes a new session.lock; it's never backed up.
                    if (name !in names && name != "session.lock") {
                        counter.removed++
                        counter.example("- ${join(path, name)} (set aside)")
                    }
                }
            }
            return
        }
        counter.files++
        counter.bytes += node.contentSize
        if (attributes == null) {
            counter.added++
            counter.example("+ $path")
            return
        }
        if (node.kind == Tree.Kind.REGION && attributes.isRegularFile) {
            val differ = differingChunks(repository.region(node.ref!!), disk)
            if (differ != 0) {
                counter.changed++
                counter.chunks += maxOf(differ, 0)
                counter.example("~ $path" + if (differ > 0) " ($differ chunks)" else "")
            }
            return
        }
        if (node.kind == Tree.Kind.LINK || attributes.size() != node.size ||
            attributes.lastModifiedTime().toMillis() != node.mtime && !sameContent(node, disk, attributes)
        ) {
            counter.changed++
            counter.example("~ $path")
        }
    }

    // A file the server rewrote without changing (its time moved, its bytes didn't). Only small
    // files are hashed, to keep a dry run quick.
    private fun sameContent(node: Tree.Node, disk: Path, attributes: BasicFileAttributes): Boolean {
        if (node.kind != Tree.Kind.FILE || attributes.size() > (8 shl 20)) return false
        return try {
            Hashes.sha256(Files.readAllBytes(disk)) == node.contentHash
        } catch (e: java.io.IOException) {
            false
        }
    }

    private fun countFiles(repository: Repository, tree: Tree, counter: Counter): Int {
        var n = 0
        for (child in tree.nodes) {
            if (child.kind == Tree.Kind.DIR) {
                n += countFiles(repository, repository.tree(child.ref!!), counter)
            } else {
                n++
                counter.files++
                counter.bytes += child.contentSize
            }
        }
        return n
    }

    // Chunks whose place or timestamp differ from the layout; -1 if the file can't be read as a region.
    private fun differingChunks(layout: RegionLayout, file: Path): Int = try {
        FileChannel.open(file, StandardOpenOption.READ).use { channel ->
            if (channel.size() < RegionLayout.HEADER) return -1
            val header = LocalBackend.readFully(channel, 0, RegionLayout.HEADER, file.toString())
            var differ = 0
            for (i in 0 until RegionLayout.CHUNKS) {
                val location = co.xenastudios.xbackup.backup.RegionFiles.int(header, i * 4)
                val timestamp = co.xenastudios.xbackup.backup.RegionFiles.int(header, RegionLayout.SECTOR + i * 4)
                if (location != layout.location(i) || timestamp != layout.timestamp(i)) differ++
            }
            differ
        }
    } catch (e: java.io.IOException) {
        -1
    }

    // ---- writing ----

    /** What [write] wrote. */
    public class Written(public val files: Long, public val bytes: Long, public val chunks: Long)

    /**
     * Writes the items under [target] (each at its own path), checking every file against the hash
     * it was saved with and every region chunk against its blob's, for files that will be moved to
     * [home] afterwards. Nothing under [target] should exist yet.
     *
     * A repository or remote can't make a restore write anywhere else: every name is checked before
     * it's used, every path is checked to stay inside [target], nothing is written through a link,
     * and a link that would point outside [home] is refused (unless [allowLinksOutside], for owners
     * who keep links to other disks on purpose).
     */
    @JvmStatic
    @JvmOverloads
    public fun write(
        repository: Repository,
        items: List<Item>,
        target: Path,
        home: Path = target,
        allowLinksOutside: Boolean = false,
        throttle: Throttle = Throttle.NONE,
        cancelled: () -> Boolean = { false },
        progress: (String) -> Unit = {},
    ): Written {
        val base = target.toAbsolutePath().normalize()
        val writer = Writer(repository, base, home.toAbsolutePath().normalize(), allowLinksOutside, throttle, cancelled, progress)
        Files.createDirectories(base)
        for (item in items) {
            val out = if (item.path.isEmpty()) base else writer.inside(base.resolve(item.path), item.path)
            if (item.path.isNotEmpty()) writer.makeParents(base, item.path)
            writer.write(item.node, out, item.path)
        }
        writer.finishFolders()
        return Written(writer.files, writer.bytes, writer.chunks)
    }

    /** A restore that would write outside its folder, or through a link: refused. */
    public class UnsafeException(message: String) : java.io.IOException(message)

    private val WINDOWS = java.io.File.separatorChar == '\\'
    private val RESERVED = Regex("(?i)(con|prn|aux|nul|conin\\$|conout\\$|com[0-9¹²³]|lpt[0-9¹²³])( *\\..*)?")
    private val SHORT_NAME = Regex("~[0-9]")

    /**
     * Throws unless [name] is one plain file name on this system: no control characters, and on
     * Windows no `\` or `:` (a folder separator, a drive or a stream), no trailing dot or space, no
     * device name and no short name.
     */
    internal fun checkName(name: String, path: String, windows: Boolean) {
        var plain = true
        for (c in name) {
            if (c.code < 0x20 || c.code == 0x7F || windows && (c == '\\' || c == ':' || "<>\"|?*".indexOf(c) >= 0)) plain = false
        }
        if (plain && windows && (name.endsWith(".") || name.endsWith(" ") || RESERVED.matches(name) || SHORT_NAME.containsMatchIn(name))) plain = false
        if (!plain) {
            throw UnsafeException("The backup has an entry named ${printable(name)} in ${display(path)}, which isn't a plain file name here. It wasn't restored; the backup may have been tampered with.")
        }
    }

    private fun printable(name: String): String {
        val out = StringBuilder("\"")
        for (c in name) out.append(if (c.code < 0x20 || c.code == 0x7F) "\\u" + c.code.toString(16).padStart(4, '0') else c.toString())
        return out.append('"').toString()
    }

    private fun display(path: String): String = if (path.isEmpty()) "the top folder" else path

    private class Writer(
        private val repository: Repository,
        private val base: Path,
        private val home: Path,
        private val allowLinksOutside: Boolean,
        private val throttle: Throttle,
        private val cancelled: () -> Boolean,
        private val progress: (String) -> Unit,
    ) {
        private val folders = ArrayList<Pair<Path, Tree.Node>>()
        var files = 0L
        var bytes = 0L
        var chunks = 0L

        // The path, if it's inside the folder being written; throws if it isn't.
        fun inside(out: Path, path: String): Path {
            val clean = out.toAbsolutePath().normalize()
            if (!clean.startsWith(base) || clean == base && path.isNotEmpty()) {
                throw UnsafeException("${display(path)} would be written outside $base. Nothing more was written; the backup may have been tampered with.")
            }
            return clean
        }

        // The folders above an item, made one by one, none of them a link.
        fun makeParents(base: Path, path: String) {
            val parts = path.split('/')
            var at = base
            val sofar = StringBuilder()
            for (i in 0 until parts.size - 1) {
                checkName(parts[i], sofar.toString(), WINDOWS)
                at = inside(at.resolve(parts[i]), join(sofar.toString(), parts[i]))
                sofar.append(if (sofar.isEmpty()) "" else "/").append(parts[i])
                makeFolder(at, sofar.toString())
            }
            checkName(parts[parts.size - 1], sofar.toString(), WINDOWS)
        }

        // A new folder, or one this restore already made; never a link to somewhere else.
        private fun makeFolder(out: Path, path: String) {
            try {
                Files.createDirectory(out)
            } catch (e: FileAlreadyExistsException) {
                val attributes = attributes(out)
                if (attributes == null || !attributes.isDirectory) {
                    throw UnsafeException(
                        "${display(path)} is already there as " + (if (attributes != null && attributes.isSymbolicLink) "a link" else "a file") +
                            ", so nothing is written into it. Two entries in the backup may have names that differ only in case, which this disk can't keep apart.",
                    )
                }
            }
        }

        fun write(node: Tree.Node, out: Path, path: String) {
            if (cancelled()) throw java.io.IOException("The restore was stopped.")
            when (node.kind) {
                Tree.Kind.DIR -> {
                    if (out == base) Files.createDirectories(out) else makeFolder(out, path)
                    for (child in repository.tree(node.ref!!).nodes) {
                        checkName(child.name, path, WINDOWS)
                        val childPath = join(path, child.name)
                        write(child, inside(out.resolve(child.name), childPath), childPath)
                    }
                    folders += out to node
                }
                Tree.Kind.FILE, Tree.Kind.SQLITE -> {
                    writeFile(node, out, path)
                    finish(out, node)
                }
                Tree.Kind.REGION -> {
                    writeRegion(repository.region(node.ref!!), out, path)
                    finish(out, node)
                }
                Tree.Kind.LINK -> writeLink(node, out, path)
            }
        }

        // A link is put back only if it points inside the folder the files end up in.
        private fun writeLink(node: Tree.Node, out: Path, path: String) {
            val target = node.target!!
            if (target.isEmpty() || '\u0000' in target) throw UnsafeException("${display(path)} is a link with no usable target in the backup.")
            val link = try {
                Paths.get(if (WINDOWS) target else target.replace('\\', '/'))
            } catch (e: InvalidPathException) {
                throw UnsafeException("${display(path)} is a link to ${printable(target)}, which isn't a path here.")
            }
            val placed = home.resolve(base.relativize(out).toString())
            val leads = (if (link.isAbsolute) link else placed.parent.resolve(link)).normalize()
            if (!allowLinksOutside && !(leads.startsWith(home) && resolved(leads).startsWith(resolved(home)))) {
                throw UnsafeException("${display(path)} is a link to $target, outside $home, so it isn't restored. If you made that link yourself, restore it from the command line with --allow-links-outside, or leave it out of the restore.")
            }
            Files.createSymbolicLink(out, link)
        }

        private fun writeFile(node: Tree.Node, out: Path, path: String) {
            val digest = Hashes.digest()
            var size = 0L
            DigestOutputStream(Files.newOutputStream(out, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS), digest).use { stream ->
                for (blob in node.blobs) {
                    val data = repository.readBlob(blob)
                    throttle.acquire(data.size.toLong())
                    stream.write(data)
                    size += data.size
                }
            }
            if (size != node.contentSize || Hashes.of(digest) != node.contentHash) throw CorruptException("$path didn't come back as it was saved.")
            LocalBackend.sync(out)
            files++
            bytes += size
            if (files % 500 == 0L) progress("$files files written")
        }

        private fun writeRegion(layout: RegionLayout, out: Path, path: String) {
            FileChannel.open(out, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
                writeAt(channel, ByteBuffer.wrap(layout.header()), 0)
                for (i in 0 until RegionLayout.CHUNKS) {
                    if (!layout.present(i)) continue
                    val record = repository.readBlob(layout.blob(i)!!)
                    if (record.size != layout.recordLength(i)) throw CorruptException("$path: chunk $i has the wrong length.")
                    throttle.acquire(record.size.toLong())
                    writeAt(channel, ByteBuffer.wrap(record), RegionLayout.sectorOffset(layout.location(i)) * RegionLayout.SECTOR)
                    chunks++
                }
                // Zero-fill to the saved length, sector padding included.
                if (channel.size() < layout.fileLength) writeAt(channel, ByteBuffer.allocate(1), layout.fileLength - 1)
                if (channel.size() != layout.fileLength) throw CorruptException("$path came back ${channel.size()} bytes long, not ${layout.fileLength}.")
                channel.force(true)
            }
            files++
            bytes += layout.fileLength
        }

        private fun finish(out: Path, node: Tree.Node) {
            Scanner.setMode(out, node.mode)
            Files.setLastModifiedTime(out, FileTime.fromMillis(node.mtime))
        }

        // Folder times last, since writing into a folder changes its time.
        fun finishFolders() {
            for (i in folders.indices.reversed()) {
                val (out, node) = folders[i]
                Scanner.setMode(out, node.mode)
                Files.setLastModifiedTime(out, FileTime.fromMillis(node.mtime))
            }
        }
    }

    // Where a path really leads through the links already on disk: its longest existing part
    // resolved, the rest added back. A link into a folder that's itself a link out of the server
    // folder leads outside it.
    private fun resolved(path: Path): Path {
        var existing: Path? = path
        var rest = path.fileSystem.getPath("")
        while (existing != null && !Files.exists(existing)) {
            val name = existing.fileName
            rest = if (name == null) rest else name.resolve(rest)
            existing = existing.parent
        }
        if (existing == null) return path
        return try {
            existing.toRealPath().resolve(rest).normalize()
        } catch (e: java.io.IOException) {
            path
        }
    }

    private fun writeAt(channel: FileChannel, buffer: ByteBuffer, position: Long) {
        var at = position
        while (buffer.hasRemaining()) at += channel.write(buffer, at)
    }

    internal fun attributes(file: Path): BasicFileAttributes? = try {
        Files.readAttributes(file, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (e: java.io.IOException) {
        null
    }

    @JvmStatic
    public fun join(path: String, name: String): String = if (path.isEmpty()) name else "$path/$name"
}
