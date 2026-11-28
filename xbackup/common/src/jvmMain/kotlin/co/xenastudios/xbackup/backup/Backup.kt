package co.xenastudios.xbackup.backup

import co.xenastudios.xbackup.store.Hash
import co.xenastudios.xbackup.store.Maintenance
import co.xenastudios.xbackup.store.Repository
import co.xenastudios.xbackup.store.Session
import co.xenastudios.xbackup.store.Snapshot
import co.xenastudios.xbackup.store.Throttle
import co.xenastudios.xbackup.store.Tree
import co.xenastudios.xbackup.store.now
import java.nio.file.Files
import java.nio.file.Path
import java.util.Random
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * One backup, start to finish. The server's part (pausing autosave and flushing the worlds) comes
 * in through [Pause].
 *
 * 1. First pass over the worlds while the server runs normally: store whatever changed since the
 *    last snapshot and could be read consistently.
 * 2. Pause: autosave off, worlds flushed to disk ([Pause.pause]).
 * 3. Second pass over the worlds, strict: only what changed since the first pass is read.
 * 4. Autosave back on ([Pause.resume]), always, even on failure.
 * 5. Everything else (plugins, server files, databases, each copied consistently), then the snapshot.
 * 6. The packs just written are read back and checked before the snapshot is written.
 */
public object Backup {

    /** The server's part of a backup. */
    public interface Pause {
        /**
         * Stops autosave and flushes the worlds, so their files hold one moment and nothing writes
         * them until [resume].
         *
         * @return how long the server's threads spent on it, in milliseconds
         */
        public fun pause(): Long

        /** Turns autosave back on. Called once after every [pause], even a failed one. */
        public fun resume()

        public object None : Pause {
            override fun pause(): Long = 0

            override fun resume() {}
        }
    }

    /** Pauses another plugin's writes while its folder is read. */
    public interface Quiet {
        public fun pause()

        public fun resume()
    }

    /** How long a [Quiet.pause] may take before its folder is read anyway. */
    @Volatile
    @JvmStatic
    public var quietTimeoutMillis: Long = 30_000

    /**
     * Reads a folder with its plugin's writes paused: a pause that throws or takes longer than
     * [quietTimeoutMillis] only adds a warning and the folder is read anyway, and resume always
     * follows pause, one after the other. A pause that never returns gets its resume once it
     * does, on its own thread; the backup doesn't wait for it.
     */
    private fun quietly(quiet: Quiet, path: String, warnings: MutableList<String>, read: () -> Tree.Node?): Tree.Node? {
        val paused = CompletableFuture<Unit>()
        val thread = Thread({
            try {
                quiet.pause()
                paused.complete(Unit)
            } catch (e: Throwable) {
                paused.completeExceptionally(e)
            }
        }, "xBackup quiet moment")
        thread.isDaemon = true
        thread.start()
        var returned = true
        try {
            paused.get(quietTimeoutMillis, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            returned = false
            warnings += "$path: its plugin didn't pause its writes within ${quietTimeoutMillis / 1000} s, so it was read while it may have been writing."
        } catch (e: ExecutionException) {
            val cause = e.cause ?: e
            warnings += "$path: its plugin couldn't pause its writes (${cause.message ?: cause.javaClass.simpleName}), so it was read anyway."
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            returned = false
        }
        try {
            return read()
        } finally {
            if (returned) resume(quiet, path, warnings) else paused.whenComplete { _, _ -> resume(quiet, path, null) }
        }
    }

    private fun resume(quiet: Quiet, path: String, warnings: MutableList<String>?) {
        try {
            quiet.resume()
        } catch (e: RuntimeException) {
            warnings?.add("$path: its plugin's resume failed: ${e.message}")
        }
    }

    /**
     * What to back up and how.
     *
     * @param root the server folder; every path is relative to it
     * @param worlds world save folders, read during the pause
     * @param others everything else to take
     * @param excludes glob patterns of paths to leave out
     * @param skip absolute paths never taken (the repository itself)
     * @param quiet folders to read with their plugin's writes paused
     * @param known SQLite files the family has open, by real path: databases whatever their name
     * @param external what the backup can't hold, recorded in the snapshot (the family's MySQL)
     * @param temp where database copies go while they're stored
     */
    public class Plan(
        public val root: Path,
        public val worlds: List<String>,
        public val others: List<String>,
        public val excludes: List<String>,
        public val skip: Set<Path>,
        public val quiet: Map<String, Quiet>,
        public val reason: String,
        public val source: String,
        public val temp: Path,
        public val throttle: Throttle,
        public val options: Session.Options,
        public val known: Set<String> = emptySet(),
        public val external: List<String> = emptyList(),
    )

    /**
     * A finished backup and what it took.
     *
     * @param notes files left out because they vanished or kept changing while read (a download
     *     finishing, say): not warnings, since the next backup reads them again
     */
    public class Result(
        public val snapshot: Snapshot,
        public val chunksRead: Long,
        public val chunksReused: Long,
        public val pass1Millis: Long,
        public val notes: List<String> = emptyList(),
    )

    /**
     * Runs a backup. Hold the repository's exclusive lock.
     *
     * @param databases copies live SQLite databases; `null` leaves them out with a warning
     */
    @JvmStatic
    public fun run(repository: Repository, plan: Plan, pause: Pause, databases: Databases?, cancelled: () -> Boolean, progress: (String) -> Unit): Result {
        val start = now()
        val lost = ArrayList<String>()
        val missing = repository.dropMissingPacks()
        if (missing.isNotEmpty()) {
            lost += "${missing.size} pack" + (if (missing.size == 1) " the index listed was" else "s the index listed were") +
                " missing (${missing[0].shortHex()}" + (if (missing.size > 1) ", ..." else "") + "). This backup stored" +
                " their data again where the files still have it; older backups that used them are damaged. /backup verify full says which."
        }
        val problems = ArrayList<String>()
        val snapshots = repository.snapshots(problems)
        val parent = snapshots.lastOrNull()
        val previous: Reference = if (parent == null) Reference.None else Reference.Of(repository, parent.tree, parent.time.toEpochMilliseconds())
        val root = plan.root.toAbsolutePath().normalize()
        val worldSelection = Selection(plan.worlds, plan.excludes, plan.skip)

        try {
            repository.session(plan.options).use { session ->
                // 1. First pass, while the server saves as usual.
                progress("reading the worlds")
                val pass1Start = System.currentTimeMillis()
                val first = Scanner(session, previous, false, root, worldSelection, emptyMap(), plan.throttle, null, plan.known, plan.temp, cancelled)
                for (world in plan.worlds) first.scan(root.resolve(world), world)
                val pass1Millis = System.currentTimeMillis() - pass1Start

                // 2-4. The pause: flush, read what changed since the first pass, resume.
                progress("saving the worlds")
                val worlds = LinkedHashMap<String, Tree.Node>()
                val pauseMillis: Long
                val offStart = System.nanoTime()
                val offMillis: Long
                val second: Scanner
                try {
                    pauseMillis = pause.pause()
                    progress("reading what changed during the pause")
                    val overlay = Reference.Overlay(first.nodes, first.regions, pass1Start, previous)
                    // Databases in a world folder are copied here, the strict pass, as the rest are later.
                    second = Scanner(session, overlay, true, root, worldSelection, emptyMap(), plan.throttle, databases, plan.known, plan.temp, cancelled)
                    for (world in plan.worlds) second.scan(root.resolve(world), world)?.let { worlds[world] = it }
                } finally {
                    pause.resume()
                    offMillis = (System.nanoTime() - offStart) / 1_000_000
                }

                // 5. Everything else, with plugins that asked for it kept quiet while read.
                progress("reading plugins and server files")
                val selection = Selection(plan.worlds + plan.others, plan.excludes, plan.skip)
                val ready = HashMap<String, Tree.Node>(worlds)
                val quietWarnings = ArrayList<String>()
                val rest = Scanner(session, previous, true, root, selection, ready, plan.throttle, databases, plan.known, plan.temp, cancelled)
                for ((path, quiet) in plan.quiet) {
                    if (selection.choose(path, root.resolve(path)) != Selection.Choice.INCLUDE || path in ready) continue
                    quietly(quiet, path, quietWarnings) { rest.scan(root.resolve(path), path) }?.let { ready[path] = it }
                }
                val top = rest.scanRoot(root) ?: throw java.io.IOException("Couldn't read the server folder $root.")

                // 6. Store the last packs, read them back, and only then write the snapshot.
                progress("checking what was written")
                session.flush()
                val written: Set<Hash> = session.packsWritten().toHashSet()
                if (written.isNotEmpty()) {
                    val report = Maintenance(repository, cancelled).check(0.0, written, Random())
                    if (!report.ok) throw java.io.IOException("What was just written doesn't read back correctly: ${report.problems[0]} The backup was not saved.")
                }
                val warnings = lost + second.warnings + quietWarnings + rest.warnings
                val snapshot = session.commit(
                    Snapshot(
                        null, start, now(), top.ref!!, parent?.key, plan.reason, second.files + rest.files, second.bytes + rest.bytes,
                        session.addedBytes, pauseMillis, offMillis, plan.source, warnings, plan.external,
                    ),
                )
                return Result(snapshot, second.chunksRead + first.chunksRead, second.chunksReused, pass1Millis, second.notes + rest.notes)
            }
        } finally {
            if (Files.isDirectory(plan.temp)) {
                Files.list(plan.temp).use { files -> for (file in files) if (file.fileName.toString().startsWith("db-")) Files.deleteIfExists(file) }
            }
        }
    }
}
