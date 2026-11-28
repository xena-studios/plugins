package co.xenastudios.xbackup.restore

import co.xenastudios.xbackup.store.RestorePaths
import java.io.StringReader
import java.io.StringWriter
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.DirectoryStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties
import java.util.concurrent.atomic.AtomicInteger

/**
 * Puts a staged restore in place: each restored path's current files are moved aside to
 * `replaced/<time>/`, then the staged copy is moved in. Runs from the command line with the server
 * stopped, or as the plugin loads on the next start, before any world loads. The journal and its
 * format are 1.x's, so a restore staged by either version is finished by the other.
 *
 * A journal (`pending`) lists the paths, and each finished path is appended to it, so a crash part
 * way through is finished on the next run rather than left half done. Every step can be repeated
 * safely: a path already moved aside isn't moved again.
 *
 * ```
 * restore/
 *   pending                 the journal: what to swap, and which are done
 *   staged/<path>           the restored files, waiting
 *   replaced/<time>/<path>  what was there before
 * ```
 */
public object Swap {
    public const val PENDING: String = "pending"
    public const val STAGED: String = "staged"
    public const val REPLACED: String = "replaced"

    /** The journal of a restore that failed and was undone, kept for reference. */
    public const val FAILED: String = "failed"
    private val DATABASE_FILES = listOf("-wal", "-shm", "-journal")

    /** What a move across disks leaves while it deletes the source; cleared on the next run. */
    private const val MOVED = ".xbackup-moved"

    /**
     * One path to swap.
     *
     * @param database a SQLite file restored on its own: its old journal files are set aside too,
     *     or SQLite would replay them over the restored copy
     */
    public data class Entry(val path: String, val database: Boolean)

    /** A staged restore; [done] is how many of the entries are already in place. */
    public class Pending(public val snapshot: String, public val created: String, public val entries: List<Entry>, public val done: Int)

    /** Writes the journal once everything is staged: from then on the swap will happen. */
    @JvmStatic
    public fun schedule(restoreFolder: Path, snapshot: String, entries: List<Entry>) {
        val properties = Properties()
        properties.setProperty("snapshot", snapshot)
        properties.setProperty("created", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")))
        properties.setProperty("count", entries.size.toString())
        for ((i, entry) in entries.withIndex()) {
            properties.setProperty("path.$i", entry.path)
            properties.setProperty("database.$i", entry.database.toString())
        }
        val writer = StringWriter()
        properties.store(writer, null)
        val text = "# A restore xBackup will put in place on the next start. Delete this\n# file (or run /backup restore cancel) to cancel it.\n$writer"
        val temp = restoreFolder.resolve("$PENDING.part")
        Files.write(temp, text.toByteArray(Charsets.UTF_8))
        force(temp)
        Files.move(temp, restoreFolder.resolve(PENDING), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        syncFolder(restoreFolder)
    }

    /** The staged restore waiting in the folder, or `null`. */
    @JvmStatic
    public fun pending(restoreFolder: Path): Pending? {
        val journal = Journal.read(restoreFolder) ?: return null
        return Pending(journal.snapshot, journal.created, journal.entries, journal.entries.indices.count { journal.done(it) })
    }

    // The journal: the properties, then a line per step taken: "aside <n>" once an entry's old
    // files are set aside (or there were none), "done <n>" once it's in place, "undone <n>" once a
    // failed restore put it back. The last line about an entry is its state.
    private const val NONE = 0
    private const val ASIDE = 1
    private const val DONE = 2

    private class Journal(val snapshot: String, val created: String, val entries: List<Entry>, val state: IntArray) {
        fun done(i: Int): Boolean = state[i] == DONE

        companion object {
            fun read(restoreFolder: Path): Journal? {
                val file = restoreFolder.resolve(PENDING)
                if (!Files.exists(file)) return null
                val text = String(Files.readAllBytes(file), Charsets.UTF_8)
                val head = StringBuilder()
                val steps = ArrayList<String>()
                for (line in text.split('\n')) {
                    if (line.startsWith("done ") || line.startsWith("undone ") || line.startsWith("aside ")) steps += line.trim() else head.append(line).append('\n')
                }
                val properties = Properties()
                StringReader(head.toString()).use { properties.load(it) }
                val count = properties.getProperty("count", "0").toIntOrNull() ?: throw java.io.IOException("The restore journal $file is damaged.")
                val entries = ArrayList<Entry>()
                for (i in 0 until count) {
                    val path = properties.getProperty("path.$i") ?: throw java.io.IOException("The restore journal $file is damaged.")
                    val clean = try {
                        RestorePaths.normalise(path)
                    } catch (e: IllegalArgumentException) {
                        throw java.io.IOException("The restore journal $file has a path outside the server folder: $path")
                    }
                    entries += Entry(clean, properties.getProperty("database.$i").toBoolean())
                }
                val state = IntArray(count)
                for (step in steps) {
                    // A cut-off last line (a crash while it was written) is ignored.
                    val parts = step.split(' ')
                    val index = parts.getOrNull(1)?.toIntOrNull() ?: continue
                    if (index in 0 until count) {
                        state[index] = when (parts[0]) {
                            "done" -> DONE
                            "aside" -> ASIDE
                            else -> NONE
                        }
                    }
                }
                return Journal(properties.getProperty("snapshot", "?"), properties.getProperty("created", "unknown"), entries, state)
            }
        }
    }

    /** Cancels a staged restore that hasn't started: deletes the journal and the staged files. */
    @JvmStatic
    public fun cancel(restoreFolder: Path): Boolean {
        val pending = pending(restoreFolder) ?: return false
        if (pending.done > 0) throw java.io.IOException("This restore is part way done; it has to finish. Restart the server.")
        Files.delete(restoreFolder.resolve(PENDING))
        deleteTree(restoreFolder.resolve(STAGED))
        return true
    }

    /** A restore that couldn't be put in place. What was there before is back where it was. */
    public class FailedException(message: String, cause: Throwable?) : java.io.IOException(message, cause)

    /**
     * A restore that failed part way, and then couldn't be undone either: the server folder is
     * half restored. Never start a server on it.
     */
    public class StuckException(message: String, cause: Throwable?) : java.io.IOException(message, cause)

    /** Where one entry's files live and go. */
    private class Places(val target: Path, val from: Path, val old: Path)

    private fun places(entry: Entry, root: Path, staged: Path, aside: Path, created: String): Places {
        var target = if (entry.path.isEmpty()) root else root.resolve(entry.path)
        val from = staged.resolve(entry.path)
        var old = aside.resolve(entry.path)
        // A folder the owner keeps elsewhere behind a link (a world on another disk) is restored
        // where the link leads, so it stays there; what was in it goes beside it, on the same
        // disk. The link itself is left as it is.
        if (entry.path.isNotEmpty() && Files.isSymbolicLink(target)) {
            val linked = target.toAbsolutePath().parent.resolve(Files.readSymbolicLink(target)).normalize()
            old = linked.resolveSibling(linked.fileName.toString() + ".replaced-" + created)
            target = linked
        }
        return Places(target, from, old)
    }

    /**
     * Puts the staged restore in place, all or nothing.
     *
     * Everything is checked first: if a staged copy is missing, nothing is touched (or, if an
     * earlier run was cut off part way, what it did is undone). If a step fails part way (a file
     * the system keeps locked, a disk that fills), what was put in place is moved back to the
     * staging folder and what was set aside is moved back, so the server folder is as it was; the
     * journal is then kept as [FAILED], the staged copy deleted, and [FailedException] thrown. If
     * even that can't be done, [StuckException]: stop, and sort it out by hand from the paths it
     * gives. A crash at any point is finished on the next run.
     *
     * @param root the server folder the journal's paths are relative to
     * @return where the old files went, or `null` if nothing was pending
     */
    @JvmStatic
    public fun apply(restoreFolder: Path, root: Path, log: (String) -> Unit): Path? {
        val journal = Journal.read(restoreFolder) ?: return null
        val file = restoreFolder.resolve(PENDING)
        val staged = restoreFolder.resolve(STAGED)
        val aside = restoreFolder.resolve(REPLACED).resolve(journal.created)
        val places = journal.entries.map { places(it, root, staged, aside, journal.created) }
        // Every entry an earlier run (cut off by a crash) started: journaled as set aside or done,
        // or with its old files already moved aside a moment before the journal said so. If this
        // run fails, they're undone along with what it started itself.
        val touched = ArrayList<Int>()
        for (i in places.indices) if (journal.state[i] != NONE || exists(places[i].old)) touched += i
        // Check before moving anything: every entry not done has its staged copy.
        var missing: String? = null
        for (i in places.indices) {
            if (missing != null) break
            if (journal.state[i] == NONE && !exists(places[i].from)) missing = display(journal.entries[i])
        }
        if (missing != null && touched.isEmpty()) {
            throw java.io.IOException("The staged copy of $missing is missing from $staged, so the restore can't be put in place. Nothing was changed. Stage it again, or delete $file to cancel it.")
        }
        var current = -1
        try {
            if (missing != null) throw java.io.IOException("the staged copy of $missing is missing from $staged")
            for (i in places.indices) {
                if (journal.done(i)) continue
                current = i
                val entry = journal.entries[i]
                val at = places[i]
                if (i !in touched) touched += i
                deleteTree(sibling(at.target, MOVED))
                if (journal.state[i] == ASIDE) {
                    // Started before a crash: set aside, and maybe put in place too.
                    if (!exists(at.from) && !exists(at.target)) throw java.io.IOException("the staged copy of ${display(entry)} disappeared part way")
                } else if (exists(at.target) && !exists(at.old)) {
                    move(at.target, at.old)
                }
                if (entry.database) {
                    for (suffix in DATABASE_FILES) {
                        val sib = sibling(at.target, suffix)
                        val oldSib = sibling(at.old, suffix)
                        if (exists(sib) && !exists(oldSib)) move(sib, oldSib)
                    }
                }
                if (journal.state[i] != ASIDE) step(file, "aside $i")
                if (exists(at.from)) {
                    // Set aside across disks before a crash, which left part of it behind.
                    if (exists(at.target)) deleteTree(at.target)
                    move(at.from, at.target)
                }
                step(file, "done $i")
                log("Restored ${display(entry)}.")
            }
        } catch (e: Exception) {
            val why = e.message ?: e.javaClass.simpleName
            val what = if (current < 0) "the restore" else display(journal.entries[current])
            try {
                undo(journal, places, touched, file)
                finishFailed(restoreFolder)
            } catch (undoing: Exception) {
                undoing.addSuppressed(e)
                throw StuckException(
                    "Putting $what in place failed ($why), and undoing the restore failed too (${undoing.message}). The server folder is part way restored: don't start the server on it. The journal is $file, the restored copies are in $staged, and what was there before is in $aside.",
                    undoing,
                )
            }
            throw FailedException("Putting $what in place failed ($why), so the restore was undone: everything is as it was before. The restore's journal is kept as ${restoreFolder.resolve(FAILED)}. Fix the cause, then restore again.", e)
        }
        Files.delete(file)
        deleteTree(staged)
        return aside
    }

    // Moves every entry started back, by this run or one a crash cut off: the restored copy to the
    // staging folder, then what was set aside back into place. Newest first, each step journaled.
    private fun undo(journal: Journal, places: List<Places>, touched: MutableList<Int>, file: Path) {
        touched.sort()
        for (t in touched.indices.reversed()) {
            val i = touched[t]
            val entry = journal.entries[i]
            val at = places[i]
            if (exists(at.from)) {
                // Not put in place. Anything at the target beside a set-aside copy is what a move
                // across disks left behind.
                if (exists(at.old) && exists(at.target)) deleteTree(at.target)
            } else if (exists(at.target)) {
                move(at.target, at.from)
            }
            if (exists(at.old) && !exists(at.target)) move(at.old, at.target)
            if (entry.database) {
                for (suffix in DATABASE_FILES) {
                    val sib = sibling(at.target, suffix)
                    val oldSib = sibling(at.old, suffix)
                    if (exists(oldSib) && !exists(sib)) move(oldSib, sib)
                }
            }
            step(file, "undone $i")
        }
    }

    private fun finishFailed(restoreFolder: Path) {
        Files.move(restoreFolder.resolve(PENDING), restoreFolder.resolve(FAILED), StandardCopyOption.REPLACE_EXISTING)
        deleteTree(restoreFolder.resolve(STAGED))
    }

    private fun sibling(path: Path, suffix: String): Path = path.resolveSibling(path.fileName.toString() + suffix)

    private fun display(entry: Entry): String = if (entry.path.isEmpty()) "the server folder" else entry.path

    /** For tests: called before every step that changes the disk, with the step's number. */
    @Volatile
    @JvmStatic
    public var beforeStep: ((Int) -> Unit)? = null
    private val steps = AtomicInteger()

    private fun hook() {
        beforeStep?.invoke(steps.getAndIncrement())
    }

    private fun step(journal: Path, line: String) {
        hook()
        Files.write(journal, "$line\n".toByteArray(Charsets.UTF_8), StandardOpenOption.APPEND)
        force(journal)
    }

    private fun exists(path: Path): Boolean = Files.exists(path, LinkOption.NOFOLLOW_LINKS)

    /**
     * Moves a file or folder. A rename when both ends are on one disk; otherwise a copy to a
     * temporary name beside the target, a rename into place, then deleting the source, so a crash
     * never leaves a half-copied target under the real name.
     */
    internal fun move(from: Path, to: Path) {
        hook()
        Files.createDirectories(to.parent)
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
            syncFolder(to.parent)
            syncFolder(from.parent)
            return
        } catch (e: AtomicMoveNotSupportedException) {
            // Different disks: copy instead.
        }
        val temp = sibling(to, ".xbackup-part")
        deleteTree(temp)
        try {
            copyTree(from, temp)
            Files.move(temp, to, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            deleteTree(temp)
            throw e
        }
        syncFolder(to.parent)
        // Renamed first, so a crash while deleting never leaves part of it under its name.
        val gone = sibling(from, MOVED)
        deleteTree(gone)
        Files.move(from, gone, StandardCopyOption.ATOMIC_MOVE)
        deleteTree(gone)
    }

    private fun copyTree(from: Path, to: Path) {
        Files.walkFileTree(from, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.createDirectories(to.resolve(from.relativize(dir).toString()))
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.copy(file, to.resolve(from.relativize(file).toString()), StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS)
                return FileVisitResult.CONTINUE
            }
        })
    }

    /** Deletes a file or a folder and everything in it. Missing is fine. */
    @JvmStatic
    public fun deleteTree(path: Path) {
        if (!exists(path)) return
        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                if (exc != null) throw exc
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    /** Flushes a folder's entries, so a rename into it survives a power cut. Not every system can. */
    internal fun syncFolder(folder: Path?) {
        if (folder == null) return
        co.xenastudios.xbackup.store.LocalBackend.syncDirectory(folder)
    }

    private fun force(file: Path) {
        FileChannel.open(file, StandardOpenOption.WRITE).use { it.force(true) }
    }
}

/**
 * Whether a server is running, for the command-line restore, which must never swap folders under
 * a live server. Two signs, either is enough:
 * - xBackup's own [MARKER], locked by the operating system from the moment the server starts (as
 *   the plugin loads) until the process exits, wherever the worlds are. The command-line restore
 *   holds it too while it works, so a server started meanwhile stops before loading any world;
 * - a world's `session.lock`, which Minecraft locks while the world is loaded: looked for in the
 *   folders being restored, a few folders deep in the server folder, and in `bukkit.yml`'s
 *   `world-container`.
 *
 * The operating system drops both locks if the server dies, so a crashed server never looks like
 * it's still running.
 */
public object ServerCheck {
    /** In xBackup's folder: locked while the server runs. */
    public const val MARKER: String = "running.lock"
    private const val DEPTH = 3
    private val WORLD_CONTAINER = Regex("(?m)^\\s*world-container:\\s*['\"]?([^'\"#\\r\\n]+?)['\"]?\\s*(#.*)?$")

    private var heldChannel: FileChannel? = null
    private var held: FileLock? = null

    /** What [hold] found. */
    public enum class Hold {
        /** This process holds the marker. */
        HELD,

        /** Another process holds it: a command-line restore, or another server on this folder. */
        BUSY,

        /** It couldn't be locked at all (a file system without locks, or no permission). */
        UNAVAILABLE,
    }

    /**
     * Locks the marker in [home] for as long as this server runs: the operating system lets go
     * when the process exits, after the worlds are saved, so it's never released on disable. Safe
     * to call more than once.
     */
    @JvmStatic
    @Synchronized
    public fun hold(home: Path): Hold {
        if (held?.isValid == true) return Hold.HELD
        return try {
            Files.createDirectories(home)
            val channel = FileChannel.open(home.resolve(MARKER), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            val lock = try {
                channel.tryLock()
            } catch (e: OverlappingFileLockException) {
                null
            } catch (e: java.io.IOException) {
                channel.close()
                return Hold.UNAVAILABLE
            }
            if (lock == null) {
                channel.close()
                return Hold.BUSY
            }
            heldChannel = channel
            held = lock
            Hold.HELD
        } catch (e: java.io.IOException) {
            // Only a hint for the command line; session.lock still says the server is up.
            Hold.UNAVAILABLE
        }
    }

    /** The marker, held by the command-line restore while it works; closing lets go. */
    public fun interface Claim : AutoCloseable {
        override fun close()
    }

    /**
     * Takes the marker for the command-line restore, for as long as it runs, so a server started
     * meanwhile stops instead of loading worlds being swapped.
     *
     * @return the held marker, or `null` if a server (or another restore) holds it
     * @throws java.io.IOException if it can't be locked at all, so there's no telling
     */
    @JvmStatic
    public fun claim(home: Path): Claim? {
        Files.createDirectories(home)
        val channel = FileChannel.open(home.resolve(MARKER), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        val lock = try {
            channel.tryLock()
        } catch (e: OverlappingFileLockException) {
            null
        } catch (e: java.io.IOException) {
            channel.close()
            throw java.io.IOException("can't lock ${home.resolve(MARKER)} (${e.message}), so there's no telling whether the server is running. Restore into an empty folder with --to instead.", e)
        }
        if (lock == null) {
            channel.close()
            return null
        }
        return Claim {
            try {
                lock.release()
            } finally {
                channel.close()
            }
        }
    }

    /**
     * Why the server in [server] looks like it's running, or `null` if it doesn't.
     *
     * @param home xBackup's folder there, holding [MARKER]
     * @param paths the paths about to be restored, relative to the server folder
     */
    @JvmStatic
    public fun running(server: Path, home: Path, paths: List<String>): String? {
        if (!Files.isDirectory(server)) return null
        val marker = home.resolve(MARKER)
        if (Files.isRegularFile(marker) && locked(marker)) return "xBackup holds " + (if (marker.startsWith(server)) server.relativize(marker) else marker)
        return worldOpen(server, paths)
    }

    /**
     * Which world's `session.lock` is locked, or `null`: the check that needs no marker, for the
     * command-line restore once it holds the marker itself.
     */
    @JvmStatic
    public fun worldOpen(server: Path, paths: List<String>): String? {
        if (!Files.isDirectory(server)) return null
        val folders = LinkedHashSet<Path>()
        folders.add(server)
        for (path in paths) folders.add(if (path.isEmpty()) server else server.resolve(path))
        worldContainer(server)?.let { folders.add(it) }
        val locks = ArrayList<Path>()
        for (folder in folders) find(folder, DEPTH, locks)
        for (lock in locks) {
            if (locked(lock)) return (if (lock.startsWith(server)) server.relativize(lock) else lock).toString() + " is locked"
        }
        return null
    }

    // Every session.lock in the folder and the folders below it, down to the depth given.
    private fun find(folder: Path, depth: Int, locks: MutableList<Path>) {
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(folder)) return
        val lock = folder.resolve("session.lock")
        if (Files.isRegularFile(lock) && lock !in locks) locks.add(lock)
        if (depth == 0) return
        try {
            Files.newDirectoryStream(folder, DirectoryStream.Filter<Path> { Files.isDirectory(it) }).use { stream ->
                for (child in stream) find(child, depth - 1, locks)
            }
        } catch (e: java.io.IOException) {
            // A folder we can't read can't be a world the server has open, as far as we can tell.
        }
    }

    // bukkit.yml's settings.world-container, if it's set.
    internal fun worldContainer(server: Path): Path? {
        val bukkit = server.resolve("bukkit.yml")
        if (!Files.isRegularFile(bukkit)) return null
        return try {
            val match = WORLD_CONTAINER.find(String(Files.readAllBytes(bukkit), Charsets.UTF_8)) ?: return null
            val value = match.groupValues[1].trim()
            if (value.isEmpty()) null else server.resolve(value).normalize()
        } catch (e: java.io.IOException) {
            null
        } catch (e: java.nio.file.InvalidPathException) {
            null
        }
    }

    // Whether another process holds the file locked.
    private fun locked(file: Path): Boolean = try {
        FileChannel.open(file, StandardOpenOption.WRITE).use { channel ->
            val lock = channel.tryLock()
            if (lock == null) {
                true
            } else {
                lock.release()
                false
            }
        }
    } catch (e: OverlappingFileLockException) {
        true
    } catch (e: java.io.IOException) {
        // Windows refuses to open a locked file at all.
        true
    }

    /** Why `--to` can't be used, or `null`: it must be new or empty, and not hold the server. */
    @JvmStatic
    public fun notEmpty(to: Path, server: Path): String? {
        if (server.startsWith(to)) return "--to $to holds the server folder; give a new or empty folder."
        if (!Files.exists(to, LinkOption.NOFOLLOW_LINKS)) return null
        if (!Files.isDirectory(to, LinkOption.NOFOLLOW_LINKS)) return "--to $to isn't a folder; give a new or empty folder."
        Files.newDirectoryStream(to).use { stream ->
            if (stream.iterator().hasNext()) return "--to $to isn't empty, and a restore there could mix with what's in it. Give a new or empty folder."
        }
        return null
    }
}
