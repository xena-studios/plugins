package co.xenastudios.xbackup

import co.xenastudios.core.api.Pending
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.config.Config
import co.xenastudios.core.common.time.Durations
import co.xenastudios.xbackup.config.Remote
import co.xenastudios.xbackup.config.XBackupConfig
import co.xenastudios.xbackup.store.Globs
import co.xenastudios.xbackup.store.Hash
import co.xenastudios.xbackup.store.Retention
import co.xenastudios.xbackup.store.Sizes
import co.xenastudios.xbackup.store.Snapshot
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The config, read once into the forms the engine uses: durations, sizes, patterns. A value that
 * doesn't read falls back to its default with a warning, like every other setting; 1.x's rules.
 *
 * @param every how often backups run; `null` when the schedule is off
 * @param verifyEvery how often older data is read back; `null` for never
 */
public class Settings(
    public val repository: String,
    public val every: Duration?,
    public val skipWhenIdle: Boolean,
    public val worlds: Boolean,
    public val plugins: Boolean,
    public val serverFiles: Boolean,
    public val extra: List<String>,
    public val excludes: List<String>,
    public val retention: Retention,
    public val maxSize: Long,
    public val minFree: Long,
    public val readLimit: Long,
    public val uploadLimit: Long,
    public val level: Int,
    public val maxPause: Duration,
    public val verifyEvery: Duration?,
    public val verifyPercent: Int,
    public val remotes: Map<String, Remote>,
) {
    public companion object {
        /** [config]'s settings; each value that doesn't read is said to [warn] and its default used. */
        public fun of(config: Config, warn: (String) -> Unit): Settings {
            val c = XBackupConfig
            var every = duration(config[c.schedule.every], "1h", "schedule.every", warn)
            if (every != null && every < 5.minutes) {
                warn("schedule.every is less than 5 minutes; using 5m.")
                every = 5.minutes
            }
            var maxPause = duration(config[c.performance.maxPause], "15m", "performance.max-pause", warn)
            if (maxPause == null || maxPause < 1.minutes) maxPause = 1.minutes
            val excludes = ArrayList<String>()
            for (pattern in config[c.exclude]) {
                try {
                    Globs.check(pattern)
                    excludes += pattern
                } catch (e: IllegalArgumentException) {
                    warn("exclude: ${e.message}; that pattern is ignored.")
                }
            }
            val r = c.retention
            return Settings(
                config[c.repository].trim().ifEmpty { "plugins/xBackup/repository" }, every, config[c.schedule.skipWhenIdle],
                config[c.sources.worlds], config[c.sources.plugins], config[c.sources.serverFiles], config[c.sources.extra], excludes,
                Retention(config[r.last], config[r.hourly], config[r.daily], config[r.weekly], config[r.monthly]),
                size(config[r.maxSize], "off", "retention.max-size", warn), size(config[r.minFreeSpace], "2GB", "retention.min-free-space", warn),
                size(config[c.performance.readLimit], "off", "performance.read-limit", warn), size(config[c.performance.uploadLimit], "off", "performance.upload-limit", warn),
                config[c.performance.compressionLevel], maxPause,
                duration(config[c.verify.every], "7d", "verify.every", warn), config[c.verify.percent], config[c.remotes],
            )
        }

        private fun duration(text: String, fallback: String, key: String, warn: (String) -> Unit): Duration? {
            val value = text.trim().lowercase()
            if (value == "off" || value == "never" || value == "0") return null
            return when (val read = Durations.parse(value)) {
                is Outcome.Ok -> read.value
                is Outcome.Err -> {
                    warn("$key: \"$text\" isn't a duration, such as 30m, 6h or 7d. Using $fallback.")
                    if (fallback == "off") null else (Durations.parse(fallback) as Outcome.Ok).value
                }
            }
        }

        private fun size(text: String, fallback: String, key: String, warn: (String) -> Unit): Long = try {
            Sizes.parse(text)
        } catch (e: IllegalArgumentException) {
            warn("$key: ${e.message} Using $fallback.")
            Sizes.parse(fallback)
        }
    }
}

/** A backup that didn't run, on purpose: a backup ran since it was scheduled, nobody was on, a plugin cancelled it. */
public class Skipped(public val reason: String) : Exception(reason)

/** A job that failed, with what went wrong for people (in English: it names files and remotes). */
public class JobFailed(message: String, cause: Throwable? = null) : Exception(message, cause)

/** What [Engine.verify] found. */
public class CheckReport(public val snapshots: Int, public val packsChecked: Int, public val packsTotal: Int, public val bytesChecked: Long, public val problems: List<String>) {
    public val ok: Boolean get() = problems.isEmpty()
}

/** What [Engine.prune] did. */
public class Pruned(public val removed: Int, public val kept: Int, public val freed: Long)

/** What [Engine.repair] found. */
public class Rebuilt(public val packs: Int, public val blobs: Int, public val damaged: List<String>)

/** What restoring would change: [Engine.plan]. */
public class RestorePlan(public val files: Long, public val bytes: Long, public val added: Int, public val changed: Int, public val removed: Int, public val changedChunks: Long, public val examples: List<String>)

/** A staged restore: [Engine.stage]. [jars] are plugin jars in it, which take effect only from the start after. */
public class Staged(public val files: Long, public val bytes: Long, public val jars: List<String>)

/** The restore waiting for the next start. */
public class PendingRestore(public val snapshot: String, public val paths: Int)

/** What a copy to a remote did; [held] says why some removals were held back. */
public class Copied(public val filesSent: Int, public val bytesSent: Long, public val filesDeleted: Int, public val held: String?)

/** What tidying a remote found, or did: its [extra] backups, oldest first, and the packs no backup here needs. */
public class Tidied(public val extra: List<Snapshot>, public val packs: Int, public val bytes: Long, public val keptPacks: Int)

/** How linking a drive went, step by step, for [Engine.link]'s caller to say. */
public sealed interface LinkStep {
    public class OpenLink(public val url: String) : LinkStep
    public class EnterCode(public val url: String, public val code: String) : LinkStep
    public object Linked : LinkStep
    public class Failed(public val reason: String) : LinkStep
}

/**
 * What the plugin asks of the platform as a backup runs: the events (on the global thread), the
 * broadcasts, and whether anyone is online. The plugin itself.
 */
public interface EngineHooks {
    /** Fires [co.xenastudios.xbackup.api.event.BackupStartEvent] on the global thread; settles `false` if a listener cancelled it. Settled at once when nothing listens. */
    public fun starting(reason: String): Pending<Boolean>

    public fun finished(snapshot: Snapshot)

    public fun failed(reason: String, message: String)

    /** Tells everyone online that a backup started, or is done after [elapsed], if the config says to. */
    public fun broadcast(elapsed: Duration?)

    public val anyoneOnline: Boolean
}

/**
 * Everything xBackup does, one job at a time, off the game threads: on the JVM, `JvmEngine`.
 * Commands and the API only queue jobs and read what this keeps in memory, so they answer at once.
 * A job's [Pending] fails with [Skipped] or [JobFailed].
 */
public interface Engine {
    /** Applies [settings]: on startup and every reload. */
    public fun configure(settings: Settings)

    /** Every backup, oldest first; empty until the repository's been read once ([ready]). */
    public val snapshots: List<Snapshot>

    /** Whether the list of backups is in memory. Until then [whenReady] reads it. */
    public val ready: Boolean

    /** Settles once [snapshots] is read. */
    public fun whenReady(): Pending<Unit>

    public fun pinned(id: Hash): Boolean

    /** What's running, such as `backing up: reading the worlds`, or `null`. */
    public val activity: String?

    /** The last thing that went wrong, or `null`. */
    public val problem: String?
    public val nextBackup: Instant?
    public val repositoryBytes: Long

    /** Where the repository is, for messages. */
    public val repositoryPath: String

    /** Region chunks the last backup read (0 when none changed). */
    public val lastChunksRead: Long

    /** When older data was last checked, and how it went; `null` if never. */
    public val lastCheck: Pair<Instant, String>?

    /** Each remote's state since the server started: `up to date (...)`, `failed: ...`. */
    public val remoteStatus: Map<String, String>

    /** The Xena plugins whose databases their own writers copy for each backup, by name. */
    public val family: List<String>

    /** What a backup can't hold: the family's MySQL databases, one line each. */
    public val external: List<String>

    /** The top-level paths in the newest backup, for completing `/backup restore`. */
    public val topLevel: List<String>

    public fun backup(reason: String, scheduled: Boolean): Pending<Snapshot>

    public fun verify(full: Boolean): Pending<CheckReport>

    public fun prune(): Pending<Pruned>

    public fun repair(): Pending<Rebuilt>

    public fun pin(snapshot: Snapshot, pinned: Boolean): Pending<Snapshot>

    public fun forget(snapshot: Snapshot): Pending<Snapshot>

    public fun plan(snapshot: Snapshot, paths: List<String>): Pending<RestorePlan>

    public fun stage(snapshot: Snapshot, paths: List<String>): Pending<Staged>

    public fun cancelRestore(): Pending<Boolean>

    public fun pendingRestore(): Pending<PendingRestore?>

    public fun push(remote: String): Pending<Copied>

    public fun tidy(remote: String, apply: Boolean): Pending<Tidied>

    /** Links a cloud drive: each step to [say], off the game threads. [code] is Dropbox's, its second time. */
    public fun link(remote: String, code: String?, say: (LinkStep) -> Unit)

    /** A player joined: a scheduled backup won't be skipped as idle. */
    public fun playerSeen()

    /** Where the restore's replaced files go, for messages: `plugins/xBackup/restore/replaced/`. */
    public val replacedFolder: String

    /** Stops everything: autosave back on now, the running job told to stop. */
    public fun stop()
}
