package co.xenastudios.xbackup

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.newPending
import co.xenastudios.core.api.reject
import co.xenastudios.core.api.resolve
import co.xenastudios.core.common.family.FamilyBoard
import co.xenastudios.core.common.scheduler.Task
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.xbackup.backup.Backup
import co.xenastudios.xbackup.backup.Databases
import co.xenastudios.xbackup.backup.relative
import co.xenastudios.xbackup.remote.Mirror
import co.xenastudios.xbackup.remote.OAuth
import co.xenastudios.xbackup.remote.RemoteConfig
import co.xenastudios.xbackup.remote.Remotes
import co.xenastudios.xbackup.remote.RestoreSettings
import co.xenastudios.xbackup.restore.Restore
import co.xenastudios.xbackup.restore.ServerCheck
import co.xenastudios.xbackup.restore.Swap
import co.xenastudios.xbackup.store.Hash
import co.xenastudios.xbackup.store.LocalBackend
import co.xenastudios.xbackup.store.Maintenance
import co.xenastudios.xbackup.store.NoSpaceException
import co.xenastudios.xbackup.store.Repository
import co.xenastudios.xbackup.store.SecretText
import co.xenastudios.xbackup.store.Session
import co.xenastudios.xbackup.store.Sizes
import co.xenastudios.xbackup.store.Snapshot
import co.xenastudios.xbackup.store.Throttle
import co.xenastudios.xbackup.store.Tree
import co.xenastudios.xbackup.store.ZoneCalendar
import java.io.StringReader
import java.io.StringWriter
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Instant

/** What the engine needs from a JVM server: its worlds, their autosave, and where things are. */
public interface JvmServer {
    /** Every loaded world's save folder (the one with `level.dat`). Called off the game threads: it hops to the global thread itself. */
    public fun worldFolders(): List<Path>

    /** The server's part of a backup: autosave off and the worlds flushed, back on after. */
    public fun pause(maxPauseMillis: Long, onWatchdog: () -> Unit): Backup.Pause

    /** The plugins folder on Bukkit, the mods folder on Fabric: `sources.plugins`. */
    public val pluginsFolder: Path

    /** Whether the plugin called [owner] is still enabled, for what it registered through the API. */
    public fun enabled(owner: String): Boolean
}

/**
 * The engine on the JVM: everything xBackup does, one job at a time, on its own low-priority
 * thread: backups, checks, clean-ups, copies to remotes and restore staging. Commands and the API
 * only queue jobs and read the catalogue, which this keeps in memory so they answer at once.
 * 1.x's engine, decisions/2026-11-18-xbackup.md.
 *
 * The only timer is the schedule: one delayed task, set again after each backup for the next one.
 * Checks and clean-ups ride along after a scheduled backup when they're due. The repository isn't
 * read as the server starts: the schedule runs from the last backup's time in `state.properties`,
 * and the list of backups is read the first time something needs it, off the server's threads.
 *
 * @param root the server folder, which every path is relative to
 * @param home xBackup's own folder: `plugins/xBackup/` on Bukkit, `config/xbackup/` on Fabric
 * @param defaultRepository where the repository goes when `repository` is left at its default
 * @param board the family's board, where the Xena plugins' databases are (decisions/2026-11-27-the-familys-backup-seam.md)
 * @param sqlite copies the databases no Xena plugin has open (SQLite's own copy), or `null` if this server has no SQLite
 */
public class JvmEngine(
    private val plugin: XBackupPlugin,
    private val server: JvmServer,
    public val root: Path,
    public val home: Path,
    private val defaultRepository: Path,
    private val board: FamilyBoard?,
    private val sqlite: () -> Databases?,
    private val ownJar: Path?,
) : Engine {
    private val log get() = plugin.log
    private val stopping = AtomicBoolean()
    private val worker = Executors.newSingleThreadExecutor(Worker())

    private class Worker : ThreadFactory {
        override fun newThread(task: Runnable): Thread = Thread(task, "xBackup worker").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }

    @Volatile
    private var settings: Settings? = null

    @Volatile
    private var remoteSettings: Map<String, RemoteConfig> = emptyMap()

    // Worker thread only.
    private var repository: Repository? = null
    private var repositoryAt: Path? = null
    private val state = Properties()
    private var started = false

    // The catalogue: written by the worker, read from anywhere.
    @Volatile
    override var snapshots: List<Snapshot> = emptyList()
        private set

    @Volatile
    private var pins: Set<Hash> = emptySet()

    @Volatile
    override var topLevel: List<String> = emptyList()
        private set

    @Volatile
    override var repositoryBytes: Long = 0
        private set

    @Volatile
    override var activity: String? = null
        private set

    @Volatile
    override var problem: String? = null
        private set

    @Volatile
    override var nextBackup: Instant? = null
        private set

    @Volatile
    override var lastChunksRead: Long = 0
        private set

    @Volatile
    private var lastAdded: Long = 0

    @Volatile
    override var ready: Boolean = false
        private set

    private val readied = newPending<Unit>()
    override val remoteStatus: MutableMap<String, String> = ConcurrentHashMap()

    // True at start: what changed before a restart still needs backing up.
    @Volatile
    private var playersSeen = true

    // The last scheduled run that didn't make a backup (skipped or failed), so the next is counted
    // from it, not retried every minute.
    @Volatile
    private var lastAttempt: Long? = null

    @Volatile
    private var timer: Task? = null

    @Volatile
    private var pause: Backup.Pause? = null
    private val abandoned = AtomicBoolean()

    public val restoreFolder: Path get() = home.resolve("restore")
    private val temp: Path get() = home.resolve("temp")
    private val stateFile: Path get() = home.resolve("state.properties")

    override val repositoryPath: String get() = (repositoryAt ?: settings?.let(::repositoryOf) ?: defaultRepository).toString()

    override val replacedFolder: String get() = (relative(root, restoreFolder.resolve(Swap.REPLACED)) ?: restoreFolder.resolve(Swap.REPLACED).toString()) + "/"

    private fun repositoryOf(now: Settings): Path {
        val configured = now.repository
        val folder = if (configured == "plugins/xBackup/repository") defaultRepository else root.resolve(configured)
        return folder.toAbsolutePath().normalize()
    }

    override val family: List<String>
        get() = board?.let { Databases.Family.sqliteFiles(it).values.distinct().sorted() }.orEmpty()

    override val external: List<String>
        get() = board?.let { Databases.Family.external(it) }.orEmpty()

    override val lastCheck: Pair<Instant, String>?
        get() = state.getProperty("last-verify")?.toLongOrNull()?.let { Instant.fromEpochMilliseconds(it) to state.getProperty("last-verify-result", "?") }

    // ---- config ----

    override fun configure(settings: Settings) {
        val previous = this.settings
        this.settings = settings
        val secrets = Secrets.read(home, log::warn)
        remoteSettings = settings.remotes.mapValues { (name, remote) ->
            val secret = secrets[name].orEmpty()
            RemoteConfig(
                remote.type, remote.enabled, remote.path, remote.url, remote.region, remote.bucket, remote.pathStyle, remote.username,
                secret["password"] ?: "", remote.host, remote.port, remote.privateKey, remote.hostKey, remote.security, remote.clientId, secret["client-secret"] ?: "",
            )
        }
        if (previous == null) loadState()
        val remotes = remoteSettings
        submit(null) {
            if (!started) {
                started = true
                Files.createDirectories(temp)
                Swap.deleteTree(temp)
            }
            // A changed repository is opened now; otherwise not until something needs it.
            if (repository != null && repositoryOf(settings) != repositoryAt) openRepository(repositoryOf(settings))
            try {
                RestoreSettings.write(home, repositoryOf(settings), remotes)
            } catch (e: java.io.IOException) {
                log.warn("Couldn't write ${RestoreSettings.NAME} for the command-line restore: ${e.message}")
            }
        }.whenComplete { _, _ -> arm() }
    }

    private fun openRepository(path: Path) {
        repository?.close()
        repository = null
        Files.createDirectories(path)
        val backend = LocalBackend(path)
        val opened = Repository.open(backend, temp, true)
        try {
            opened.lock(true).use { backend.clearTemp() }
        } catch (e: Repository.LockedException) {
            // Something else has it right now (a restore from the command line?); tidy up later.
        }
        repository = opened
        repositoryAt = path
        refresh()
        log.info("Repository: $path (${snapshots.size} backups, ${Sizes.format(repositoryBytes)}).")
    }

    // Re-reads the catalogue. Worker thread.
    private fun refresh() {
        val repo = repository ?: return
        val problems = ArrayList<String>()
        val list = repo.snapshots(problems)
        problems.forEach(log::warn)
        snapshots = list.toList()
        pins = repo.pins().toSet()
        repositoryBytes = repo.backend.list("").sumOf { it.size }
        topLevel = try {
            list.lastOrNull()?.let { repo.tree(it.tree).nodes.map(Tree.Node::name) }.orEmpty()
        } catch (e: java.io.IOException) {
            emptyList() // Completions only.
        }
        list.lastOrNull()?.let {
            if (state.getProperty("last-backup") != it.time.toEpochMilliseconds().toString()) {
                state.setProperty("last-backup", it.time.toEpochMilliseconds().toString())
                saveState()
            }
        }
        ready = true
        readied.resolve(Unit)
    }

    override fun whenReady(): Pending<Unit> {
        if (!ready) submit(null) { repository() }
        return readied
    }

    // ---- jobs ----

    private fun <T> submit(what: String?, job: () -> T): Pending<T> {
        val future = newPending<T>()
        if (stopping.get()) {
            future.reject(JobFailed("xBackup is stopping."))
            return future
        }
        try {
            worker.execute {
                if (what != null) activity = what
                try {
                    future.resolve(job())
                } catch (e: Skipped) {
                    future.reject(e)
                } catch (e: Throwable) {
                    future.reject(if (e is JobFailed) e else JobFailed(describe(e), e))
                } finally {
                    activity = null
                    repository?.release()
                }
            }
        } catch (e: RejectedExecutionException) {
            future.reject(JobFailed("xBackup is stopping."))
        }
        return future
    }

    private fun repository(): Repository {
        repository?.let { return it }
        openRepository(repositoryOf(settings!!))
        return repository!!
    }

    private fun progress(job: String, step: String) {
        activity = "$job: $step"
    }

    // ---- backups ----

    override fun backup(reason: String, scheduled: Boolean): Pending<Snapshot> {
        val result = submit("backing up") { runBackup(reason, scheduled) }
        if (scheduled) result.whenComplete { _, _ -> arm() }
        return result
    }

    private fun runBackup(reason: String, scheduled: Boolean): Snapshot {
        val repo = repository()
        val now = settings!!
        if (scheduled) {
            lastAttempt = System.currentTimeMillis()
            val list = snapshots
            // A backup made by hand since the timer was set moves the next one along.
            val every = now.every
            if (every != null && list.isNotEmpty() && list.last().time + every > plugin.now() + 1.minutes) throw Skipped("a backup ran since this one was scheduled")
            if (now.skipWhenIdle && !playersSeen && !plugin.anyoneOnline && list.isNotEmpty()) throw Skipped("nobody has been online since the last backup")
        }
        val allowed = try {
            plugin.starting(reason).get(30, TimeUnit.SECONDS)
        } catch (e: Exception) {
            true
        }
        if (!allowed) throw Skipped("a plugin cancelled it")
        playersSeen = plugin.anyoneOnline
        plugin.broadcast(null)
        val started = System.nanoTime()
        try {
            repo.lock(true).use {
                dropDamaged(repo)
                ensureSpace(repo, now)
                val plan = plan(now, reason)
                abandoned.set(false)
                val worldPause = server.pause(now.maxPause.inWholeMilliseconds) { abandoned.set(true) }
                pause = worldPause
                val result = try {
                    Backup.run(repo, plan, worldPause, databases(), { stopping.get() || abandoned.get() }) { progress("backing up", it) }
                } finally {
                    worldPause.resume()
                    pause = null
                }
                lastChunksRead = result.chunksRead
                lastAdded = result.snapshot.added
                val snapshot = result.snapshot
                problem = null
                log.info(
                    "Backed up ${snapshot.files} files (${Sizes.format(snapshot.bytes)}) in ${seconds(snapshot.durationMillis)}: ${Sizes.format(snapshot.added)} new. The server paused ${snapshot.pauseMillis} ms.",
                )
                for (warning in snapshot.warnings) log.warn("Backup ${snapshot.key.shortHex()}: $warning")
                try {
                    upkeep(repo, now, scheduled)
                } catch (e: java.io.IOException) {
                    // The backup itself is saved: tidying failing (an unreadable old backup, say)
                    // mustn't call it failed, or stop it reaching the remotes.
                    problem = "Backed up, but tidying old backups failed: ${describe(e)}"
                    log.warn(problem!!)
                }
                refresh()
                plugin.finished(snapshot)
                plugin.broadcast((System.nanoTime() - started).nanoseconds)
            }
        } catch (e: Skipped) {
            throw e
        } catch (e: Exception) {
            val message = if (abandoned.get()) "Reading the worlds took longer than performance.max-pause; autosave went back on and this backup was abandoned." else describe(e)
            problem = message
            log.warn("Backup failed: $message", if (e is java.io.IOException) null else e)
            plugin.failed(reason, message)
            try {
                refresh()
            } catch (ignored: java.io.IOException) {
                // The failure above says enough.
            }
            throw JobFailed(message, e)
        }
        val latest = snapshots.last()
        syncRemotes()
        return latest
    }

    private fun databases(): Databases? {
        val own = try {
            sqlite()
        } catch (e: Exception) {
            null
        }
        val board = board ?: return own
        return Databases.Family(board, own)
    }

    private fun plan(now: Settings, reason: String): Backup.Plan {
        val worldFolders = server.worldFolders()
        val extra: MutableList<Path> = ArrayList()
        val excludes = ArrayList(now.excludes)
        for ((owner, sources) in plugin.apiSources.entries.toList()) {
            if (!server.enabled(owner)) {
                plugin.apiSources.remove(owner)
                continue
            }
            for (source in sources) {
                val path = root.resolve(source.path).toAbsolutePath().normalize()
                extra.add(path)
                excludes += co.xenastudios.xbackup.store.Globs.scoped(relative(root, path), source.excludes)
            }
        }
        val worlds = ArrayList<String>()
        val others = ArrayList<String>()
        if (now.worlds) {
            for (folder in worldFolders) {
                val rel = relative(root, folder)
                if (rel == null) {
                    log.warn("The world folder $folder is outside the server folder, so xBackup can't back it up. Keep the world container inside the server folder.")
                } else if (rel !in worlds) {
                    worlds += rel
                }
            }
        }
        if (now.plugins) relative(root, server.pluginsFolder)?.let { others += it }
        if (now.serverFiles) others += serverFiles(root)
        for (path in now.extra) {
            val clean = path.trim().replace('\\', '/').trimEnd('/')
            if (clean.isEmpty() || clean.startsWith("/") || ".." in clean) {
                log.warn("sources.extra: \"$path\" must be a path inside the server folder; it's ignored.")
                continue
            }
            others += clean
        }
        for (path in extra) relative(root, path)?.let { others += it }
        val skip: MutableSet<Path> = HashSet()
        skip.add(repositoryOf(now))
        skip.add(restoreFolder)
        skip.add(temp)
        skip.add(home.resolve("libraries"))
        // Secrets never go into a backup, which is copied to every remote.
        skip.add(home.resolve(RestoreSettings.NAME))
        skip.add(home.resolve(Remotes.TOKENS))
        skip.add(home.resolve(Remotes.LEGACY_TOKENS))
        skip.add(home.resolve(SecretText.SECRETS))
        skip.add(home.resolve(ServerCheck.MARKER))
        for (remote in remoteSettings.values) if (remote.privateKey.isNotBlank()) skip.add(home.resolve(remote.privateKey.trim()))
        val quiet = LinkedHashMap<String, Backup.Quiet>()
        for ((owner, entry) in plugin.quietMoments.entries.toList()) {
            if (!server.enabled(owner)) {
                plugin.quietMoments.remove(owner)
                continue
            }
            val path = relative(root, root.resolve(entry.first)) ?: continue
            val moment = entry.second
            quiet[path] = object : Backup.Quiet {
                override fun pause() = moment.pause()

                override fun resume() = moment.resume()
            }
        }
        val known = board?.let { Databases.Family.sqliteFiles(it).keys }.orEmpty()
        return Backup.Plan(
            root, worlds, others.distinct(), excludes, skip, quiet, reason, "${XBackupPlugin.NAME} ${plugin.version} on ${plugin.platform.name}", temp,
            Throttle(now.readLimit), Session.Options(now.level, 16L shl 20, maxOf(now.minFree, 64L shl 20)), known, external,
        )
    }

    // Packs a check found damaged leave the index, so this backup stores their data again instead
    // of reusing what can't be read back.
    private fun dropDamaged(repo: Repository) {
        val pending = state.getProperty("damaged-packs", "")
        if (pending.isEmpty()) return
        val packs = pending.split(',').filter(Hash::isHex).map(Hash::parse).toSet()
        repo.dropDamagedPacks(packs)
        state.remove("damaged-packs")
        saveState()
        log.warn("Storing again the data of ${packs.size} damaged pack${if (packs.size == 1) "" else "s"} the last check found. Older backups that only it held may not restore in full; a remote may have it whole.")
    }

    // Never start a backup that can't finish: make room first if the disk is short.
    private fun ensureSpace(repo: Repository, now: Settings) {
        val at = repositoryOf(now)
        var free = Files.getFileStore(at).usableSpace
        val needed = now.minFree + maxOf(256L shl 20, lastAdded * 2)
        if (free >= needed) return
        log.info("Only ${Sizes.format(free)} free; removing old backups to make room before backing up.")
        val maintenance = Maintenance(repo) { stopping.get() }
        maintenance.forget(now.retention, now.maxSize, ZoneCalendar())
        maintenance.collect(Session.Options(now.level, 16L shl 20, now.minFree), 0.3)
        state.setProperty("last-collect", System.currentTimeMillis().toString())
        saveState()
        free = Files.getFileStore(at).usableSpace
        if (free < needed) {
            throw NoSpaceException(
                "Not enough free space to back up: ${Sizes.format(free)} free in $at, and retention.min-free-space (${Sizes.format(now.minFree)}) plus room for the backup is ${Sizes.format(needed)}. Free some space, lower retention, or move the repository to a bigger disk.",
            )
        }
    }

    // After a backup: forget what retention no longer keeps, collect garbage once a day (or when
    // over the size limit), and check older data when that's due.
    private fun upkeep(repo: Repository, now: Settings, scheduled: Boolean) {
        progress("backing up", "applying retention")
        val maintenance = Maintenance(repo) { stopping.get() }
        val forgotten = maintenance.forget(now.retention, now.maxSize, ZoneCalendar())
        val lastCollect = state.getProperty("last-collect", "0").toLongOrNull() ?: 0
        val overCap = now.maxSize > 0 && repositoryBytes > now.maxSize
        if (forgotten.removed.isNotEmpty() && (System.currentTimeMillis() - lastCollect > DAY || overCap)) {
            progress("backing up", "giving back space")
            val collected = maintenance.collect(Session.Options(now.level, 16L shl 20, now.minFree), 0.3)
            state.setProperty("last-collect", System.currentTimeMillis().toString())
            saveState()
            if (collected.bytesFreed > 0) log.info("Gave back ${Sizes.format(collected.bytesFreed)} from old backups.")
        }
        val lastVerify = state.getProperty("last-verify", "0").toLongOrNull() ?: 0
        val verifyEvery = now.verifyEvery
        if (scheduled && verifyEvery != null && System.currentTimeMillis() - lastVerify > verifyEvery.inWholeMilliseconds) {
            progress("backing up", "checking older data")
            recordCheck(maintenance.check(now.verifyPercent / 100.0, null, Random()))
        }
    }

    private fun recordCheck(report: Maintenance.Report) {
        if (report.damaged.isNotEmpty()) {
            // Dropped by the next backup, which holds the lock to change the index.
            val pending = java.util.TreeSet(state.getProperty("damaged-packs", "").split(','))
            report.damaged.forEach { pending += it.hex() }
            pending.remove("")
            state.setProperty("damaged-packs", pending.joinToString(","))
        }
        state.setProperty("last-verify", System.currentTimeMillis().toString())
        state.setProperty("last-verify-result", if (report.ok) "ok" else "${report.problems.size} problems: ${report.problems[0]}")
        saveState()
        if (report.ok) {
            log.info("Checked ${report.packsChecked} of ${report.packsTotal} packs (${Sizes.format(report.bytesChecked)}): all good.")
        } else {
            problem = "The last check found ${report.problems.size} problems. /backup verify full for details." +
                if (report.damaged.isEmpty()) "" else " The next backup stores the damaged data again from the live files."
            // Problems name files from the repository, which a remote may have written.
            for (found in report.problems) log.warn("Check: " + found.replace(Regex("\\R"), "?").replace(Regex("\\p{Cntrl}"), "?"))
        }
    }

    // ---- the schedule ----

    /** Sets the timer for the next scheduled backup. */
    public fun arm() {
        timer?.cancel()
        timer = null
        val now = settings
        val every = now?.every
        if (stopping.get() || every == null) {
            nextBackup = null
            return
        }
        var last: Long? = snapshots.lastOrNull()?.time?.toEpochMilliseconds() ?: state.getProperty("last-backup")?.toLongOrNull()
        val attempt = lastAttempt
        if (attempt != null && (last == null || attempt > last)) last = attempt
        val clock = System.currentTimeMillis()
        // Never sooner than a minute from now: let the server settle after a start.
        val due = maxOf(if (last == null) clock + 60_000 else last + every.inWholeMilliseconds, clock + 60_000)
        nextBackup = Instant.fromEpochMilliseconds(due)
        timer = plugin.platform.scheduler.run(Where.Async, delay = (due - clock).milliseconds, action = Scheduled(this))
    }

    // The schedule's task: one class, not a lambda.
    private class Scheduled(private val engine: JvmEngine) : (Task) -> Unit {
        override fun invoke(task: Task) {
            engine.backup("scheduled", true)
        }
    }

    override fun playerSeen() {
        playersSeen = true
    }

    // ---- other jobs ----

    override fun verify(full: Boolean): Pending<CheckReport> = submit(if (full) "checking everything" else "checking") {
        val repo = repository()
        repo.lock(false).use {
            val report = Maintenance(repo) { stopping.get() }.check(if (full) 1.0 else settings!!.verifyPercent / 100.0, null, Random())
            recordCheck(report)
            CheckReport(report.snapshots, report.packsChecked, report.packsTotal, report.bytesChecked, report.problems)
        }
    }

    override fun prune(): Pending<Pruned> = submit("cleaning up") {
        val repo = repository()
        repo.lock(true).use {
            val now = settings!!
            val maintenance = Maintenance(repo) { stopping.get() }
            val forgotten = maintenance.forget(now.retention, now.maxSize, ZoneCalendar())
            val collected = maintenance.collect(Session.Options(now.level, 16L shl 20, now.minFree), 0.3)
            state.setProperty("last-collect", System.currentTimeMillis().toString())
            saveState()
            refresh()
            Pruned(forgotten.removed.size, forgotten.kept, collected.bytesFreed)
        }
    }

    override fun repair(): Pending<Rebuilt> = submit("repairing") {
        val repo = repository()
        repo.lock(true).use {
            val rebuilt = Maintenance(repo) { stopping.get() }.rebuildIndex()
            rebuilt.damaged.forEach { log.warn("Repair: $it") }
            refresh()
            Rebuilt(rebuilt.packs, rebuilt.blobs, rebuilt.damaged)
        }
    }

    override fun pin(snapshot: Snapshot, pinned: Boolean): Pending<Snapshot> = submit("pinning") {
        val repo = repository()
        repo.lock(true).use {
            repo.pin(snapshot.key, pinned)
            refresh()
            snapshot
        }
    }

    override fun forget(snapshot: Snapshot): Pending<Snapshot> = submit("removing a backup") {
        val repo = repository()
        repo.lock(true).use {
            repo.deleteSnapshot(snapshot)
            refresh()
            snapshot
        }
    }

    override fun pinned(id: Hash): Boolean = id in pins

    // ---- restore ----

    /** Paths that are never restored over: xBackup's own folder and jar, and the repository. */
    public fun protectedPaths(): Set<String> {
        val protect = HashSet<String>()
        relative(root, home)?.let { protect += it }
        settings?.let { s -> relative(root, repositoryOf(s))?.takeIf { it.isNotEmpty() }?.let { protect += it } }
        ownJar?.let { jar -> relative(root, jar)?.let { protect += it } }
        return protect
    }

    override fun plan(snapshot: Snapshot, paths: List<String>): Pending<RestorePlan> = submit("planning a restore") {
        val repo = repository()
        repo.lock(false).use {
            val plan = Restore.dryRun(repo, Restore.expand(repo, snapshot, paths, protectedPaths()), root)
            RestorePlan(plan.files, plan.bytes, plan.added, plan.changed, plan.removed, plan.changedChunks, plan.examples)
        }
    }

    override fun stage(snapshot: Snapshot, paths: List<String>): Pending<Staged> = submit("staging a restore") {
        if (Swap.pending(restoreFolder) != null) throw JobFailed("A restore is already waiting for the next start. /backup restore cancel first.")
        val repo = repository()
        repo.lock(false).use {
            val items = Restore.expand(repo, snapshot, paths, protectedPaths())
            val plan = Restore.dryRun(repo, items, root)
            val staged = restoreFolder.resolve(Swap.STAGED)
            Swap.deleteTree(staged)
            Files.createDirectories(staged)
            val free = Files.getFileStore(staged).usableSpace
            val now = settings!!
            if (free < plan.bytes + now.minFree) {
                throw NoSpaceException("Restoring that needs ${Sizes.format(plan.bytes)} and only ${Sizes.format(free)} is free (keeping retention.min-free-space).")
            }
            val written = try {
                Restore.write(repo, items, staged, root, false, Throttle(now.readLimit), { stopping.get() }) { progress("staging a restore", it) }
            } catch (e: java.io.IOException) {
                Swap.deleteTree(staged)
                throw e
            }
            Swap.schedule(restoreFolder, snapshot.key.hex(), items.map { Swap.Entry(it.path, it.node.kind == Tree.Kind.SQLITE) })
            val jars = ArrayList<String>()
            for (item in items) {
                if (item.path.endsWith(".jar") && item.node.kind != Tree.Kind.DIR) {
                    jars += item.path
                } else if (item.node.kind == Tree.Kind.DIR) {
                    for (child in repo.tree(item.node.ref!!).nodes) if (child.name.endsWith(".jar") && child.kind != Tree.Kind.DIR) jars += item.path + "/" + child.name
                }
            }
            Staged(written.files, written.bytes, jars)
        }
    }

    override fun cancelRestore(): Pending<Boolean> = submit("cancelling a restore") { Swap.cancel(restoreFolder) }

    override fun pendingRestore(): Pending<PendingRestore?> = submit(null) { Swap.pending(restoreFolder)?.let { PendingRestore(it.snapshot, it.entries.size) } }

    // ---- remotes ----

    private fun context(): Remotes.Context = Remotes.Context(home, Throttle(settings?.uploadLimit ?: 0), log::info) { type ->
        plugin.backends[type]?.takeIf { server.enabled(it.first) }?.second
    }

    // After a backup, under the shared lock so a command-line restore or check can run beside it but
    // nothing deletes from the repository meanwhile.
    private fun syncRemotes() {
        val remotes = remoteSettings
        if (remotes.values.none { it.enabled }) return
        val repo = repository ?: return
        try {
            repo.lock(false).use {
                for ((name, remote) in remotes) {
                    if (!remote.enabled || stopping.get()) continue
                    progress("copying", name)
                    val start = System.nanoTime()
                    try {
                        Remotes.open(name, remote, context()).use { backend ->
                            val result = Mirror.push(repo, backend, { stopping.get() }) { progress("copying to $name", it) }
                            remoteStatus[name] = "up to date (${Sizes.format(result.bytesSent)} sent in ${seconds((System.nanoTime() - start) / 1_000_000)}, ${java.time.LocalTime.now().withNano(0)})" +
                                if (result.held == null) "" else "; kept some files: see the console"
                            if (result.filesSent > 0 || result.filesDeleted > 0) log.info("Copied to $name: ${result.filesSent} files (${Sizes.format(result.bytesSent)}) sent, ${result.filesDeleted} removed.")
                            result.held?.let { log.warn("Copying to $name: $it.") }
                        }
                    } catch (e: Exception) {
                        val message = describe(e)
                        remoteStatus[name] = "failed: $message"
                        log.warn("Couldn't copy the backups to $name: $message")
                    }
                }
            }
        } catch (e: java.io.IOException) {
            log.warn("Couldn't copy the backups to the remotes: ${describe(e)}")
        }
    }

    override fun push(remote: String): Pending<Copied> = submit("copying to $remote") {
        val config = remoteSettings[remote] ?: throw JobFailed("There's no remote named $remote in config.yml.")
        val repo = repository()
        try {
            repo.lock(false).use {
                Remotes.open(remote, config, context()).use { backend ->
                    val result = Mirror.push(repo, backend, { stopping.get() }) { progress("copying to $remote", it) }
                    remoteStatus[remote] = "up to date (${java.time.LocalTime.now().withNano(0)})"
                    result.held?.let { log.warn("Copying to $remote: $it.") }
                    Copied(result.filesSent, result.bytesSent, result.filesDeleted, result.held)
                }
            }
        } catch (e: Exception) {
            remoteStatus[remote] = "failed: ${describe(e)}"
            throw e
        }
    }

    override fun tidy(remote: String, apply: Boolean): Pending<Tidied> = submit("tidying $remote") {
        val config = remoteSettings[remote] ?: throw JobFailed("There's no remote named $remote in config.yml.")
        val repo = repository()
        repo.lock(false).use {
            Remotes.open(remote, config, context()).use { backend ->
                val tidy = Mirror.tidy(repo, backend, apply, { stopping.get() }) { progress("tidying $remote", it) }
                if (apply) {
                    log.info(
                        "Tidied $remote: removed ${tidy.extra.size} backups only it had and ${tidy.packs} packs (${Sizes.format(tidy.bytes)})" +
                            if (tidy.keptPacks == 0) "." else "; kept ${tidy.keptPacks} packs a backup here needs.",
                    )
                }
                Tidied(tidy.extra, tidy.packs, tidy.bytes, tidy.keptPacks)
            }
        }
    }

    /**
     * Links a cloud drive remote. Google Drive and OneDrive: the address and code to enter, then
     * word once it's linked; the wait runs on its own thread, not the worker, so backups carry on.
     * Dropbox: without a code, the link to open; with the code Dropbox showed, it's swapped for a token.
     */
    override fun link(remote: String, code: String?, say: (LinkStep) -> Unit) {
        val config = remoteSettings[remote] ?: return say(LinkStep.Failed("There's no remote named $remote in config.yml."))
        val type = config.type.trim().lowercase()
        val thread = Thread({
            try {
                if (type == "dropbox") {
                    if (code == null) {
                        val url = OAuth.dropboxUrl(home, remote, config)
                        say(LinkStep.OpenLink(if (url.matches(Regex("https://[^\\s'\\\\<>]+"))) url else ""))
                        return@Thread
                    }
                    OAuth.dropboxCode(home, remote, config, code)
                } else {
                    val device = OAuth.start(remote, type, config)
                    val url = device.url ?: ""
                    // A link only to a provider's https address; anything else is shown as text only.
                    say(LinkStep.EnterCode(if (url.matches(Regex("https://[^\\s'\\\\<>]+"))) url else "", device.userCode ?: "?"))
                    OAuth.finish(home, remote, type, config, device) { stopping.get() }
                }
                say(LinkStep.Linked)
            } catch (e: Exception) {
                say(LinkStep.Failed(describe(e)))
            }
        }, "xBackup link $remote")
        thread.isDaemon = true
        thread.start()
    }

    // ---- state ----

    private fun loadState() {
        try {
            if (Files.exists(stateFile)) StringReader(String(Files.readAllBytes(stateFile), Charsets.UTF_8)).use { state.load(it) }
        } catch (e: Exception) {
            log.warn("Couldn't read $stateFile; starting it again: ${e.message}")
        }
    }

    private fun saveState() {
        try {
            val writer = StringWriter()
            state.store(writer, "xBackup's own bookkeeping: when it last checked and cleaned up. Safe to delete.")
            Files.createDirectories(home)
            val part = home.resolve("state.properties.part")
            Files.write(part, writer.toString().toByteArray(Charsets.UTF_8))
            Files.move(part, stateFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: java.io.IOException) {
            log.warn("Couldn't save $stateFile: ${e.message}")
        }
    }

    // ---- stopping ----

    /** Stops everything: autosave back on now, the running job told to stop, the worker given a few seconds. */
    override fun stop() {
        stopping.set(true)
        timer?.cancel()
        pause?.resume()
        worker.shutdown()
        try {
            if (!worker.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warn("A backup job didn't stop within 10 seconds; it's abandoned. Nothing it wrote is used.")
                worker.shutdownNow()
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        try {
            repository?.close()
        } catch (ignored: java.io.IOException) {
            // Stopping anyway.
        }
        readied.resolve(Unit)
    }

    public companion object {
        private const val DAY = 24L * 3600 * 1000

        /** An error as people should read it. */
        @JvmStatic
        public fun describe(error: Throwable): String {
            var e = error
            while ((e is java.util.concurrent.CompletionException || e is ExecutionException) && e.cause != null) e = e.cause!!
            if (e is NoSuchFileException && e.message != null && ' ' !in e.message!!) return "a file disappeared: ${e.message}"
            if (e is AccessDeniedException) return "access denied to ${e.message}"
            val message = e.message
            return if (message.isNullOrBlank()) e.javaClass.simpleName else message
        }

        internal fun seconds(millis: Long): String = when {
            millis < 1000 -> "$millis ms"
            millis < 60_000 -> String.format(java.util.Locale.ROOT, "%.1fs", millis / 1000.0)
            else -> co.xenastudios.core.common.time.Durations.format(millis.milliseconds)
        }

        /** The server's own settings files beside server.properties, and its config folder. */
        @JvmStatic
        public fun serverFiles(root: Path): List<String> {
            val files = ArrayList<String>()
            try {
                Files.newDirectoryStream(root).use { stream ->
                    for (file in stream) {
                        val name = file.fileName.toString()
                        if (name.startsWith(".") || !Files.isRegularFile(file)) continue
                        val lower = name.lowercase()
                        if (SERVER_FILE_ENDINGS.any { lower.endsWith(it) }) files += name
                    }
                }
            } catch (e: java.io.IOException) {
                // An unreadable server folder shows up when the backup runs.
            }
            if (Files.isDirectory(root.resolve("config"))) files += "config"
            files.sort()
            return files
        }

        private val SERVER_FILE_ENDINGS = listOf(".yml", ".yaml", ".properties", ".json", ".toml", ".txt")
    }
}

/**
 * The remotes' passwords and secret keys, kept in `secrets.yml` beside config.yml instead of in
 * it. Owners share their config.yml for help and copy it between servers, and xBackup backs it up
 * and copies it to every remote. This file is readable by the server's user only, never backed
 * up, never copied, and never shown. 1.x's file and rules.
 */
public object Secrets {
    private val KEYS = listOf("password", "client-secret")

    private const val HEADER = "Passwords and secret keys for the remotes in config.yml, by remote name. Only the\n" +
        "server's user can read this file, and xBackup never backs it up or copies it to a\n" +
        "remote. For example:\n\n" +
        "offsite:\n" +
        "  password: \"...\"      # s3: the secret key; sftp, ftp, webdav: the password\n" +
        "gdrive:\n" +
        "  client-secret: \"...\" # drive: your app's client secret\n"

    /** Every remote's secrets by name; makes the file, owner-only, if there's none. */
    @JvmStatic
    public fun read(home: Path, warn: (String) -> Unit): Map<String, Map<String, String>> {
        val file = home.resolve(SecretText.SECRETS)
        return try {
            if (Files.exists(file)) {
                co.xenastudios.xbackup.remote.OwnerOnlyFile.restrict(file)
                co.xenastudios.xbackup.remote.SecretFile.read(file)
            } else {
                co.xenastudios.xbackup.remote.SecretFile.write(file, HEADER, emptyMap())
                emptyMap()
            }
        } catch (e: java.io.IOException) {
            warn("Couldn't read $file: ${e.message} Remotes that need a password won't connect.")
            emptyMap()
        }
    }

    /**
     * Moves any secret in config.yml's remotes ([found], by remote, then key) into `secrets.yml`; a
     * value in config.yml replaces the one there, being the newer. Returns the remotes whose
     * secrets moved; the caller then takes them out of config.yml.
     */
    @JvmStatic
    public fun store(home: Path, found: Map<String, Map<String, String>>): List<String> {
        val file = home.resolve(SecretText.SECRETS)
        val secrets = co.xenastudios.xbackup.remote.SecretFile.read(file)
        val moved = ArrayList<String>()
        for ((remote, values) in found) {
            var any = false
            for ((key, value) in values) {
                if (key in KEYS && value.isNotBlank()) {
                    secrets.getOrPut(remote) { LinkedHashMap() }[key] = value
                    any = true
                }
            }
            if (any) moved += remote
        }
        co.xenastudios.xbackup.remote.SecretFile.write(file, HEADER, secrets)
        return moved
    }

    /** The secret keys a remote's section may hold, which config.yml mustn't. */
    @JvmField
    public val SECRET_KEYS: List<String> = KEYS
}
