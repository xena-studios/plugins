package co.xenastudios.core.common.update

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.i18n.CoreMessages
import co.xenastudios.core.common.i18n.MessageKey
import co.xenastudios.core.common.isFatal
import co.xenastudios.core.common.locked
import co.xenastudios.core.common.platform.Platform
import co.xenastudios.core.common.scheduler.Task
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.text.Placeholder
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * Where the update notice goes, given by the platform: drawn in each reader's language through
 * the plugin's translations, to the console (`viewer` `null`, in `default-language`) or a
 * player; and whether a player may see it (`<plugin>.admin.update`).
 */
public interface UpdateNotices {
    /** Draws [message] for [viewer] (the console when `null`) with [placeholders] and sends it. */
    public fun send(viewer: PlayerRef?, message: MessageKey, placeholders: Map<String, Placeholder>)

    /** Whether [player] is told about updates when they join. */
    public fun mayKnow(player: PlayerRef): Boolean
}

/**
 * The update checker every plugin gets (ROADMAP.md, Updates): asks [source] (Modrinth) once
 * when it starts and then once a day, always off the server's threads, and never otherwise;
 * finds the newest release on the owner's channel ([UpdateSettings.channel]) that's newer than
 * [current]; says so once in the console, and to every player who [UpdateNotices.mayKnow] as
 * they join, each in their own language ([CoreMessages.updateAvailable]).
 *
 * The platform's adapter builds it with its own [UpdateNotices] and calls [joined] from its
 * join event; the plugin calls [apply] with its config's [UpdatesSection] at startup and on
 * every reload, and [close] as it's disabled. Turning `check` off cancels the daily task and
 * forgets what was found. A check that fails (offline, Modrinth down) is kept for
 * `/<plugin> debug` ([problem]) and tried again the next day: nothing is logged, so a server
 * without internet isn't told every day.
 *
 * With `-Dxena.offline=true` (the tests) it never asks.
 */
public class UpdateChecker(
    private val platform: Platform,
    /** The plugin's name, as the notice shows it: `xUtilities`. */
    private val pluginName: String,
    /** The running version: `3.0.0`, `3.0.0-beta.1`, `3.0.0+fabric-1.21.11`. */
    public val current: String,
    private val source: ReleaseSource,
    private val notices: UpdateNotices,
    /** How often to ask again; a day, as the roadmap says. */
    private val every: Duration = 1.days,
    private val offline: Boolean = OFFLINE,
) {
    private val lock = Any()
    private var task: Task? = null

    @Volatile
    private var settings: UpdateSettings = UpdateSettings(false, UpdateChannel.RELEASE)

    @Volatile
    private var releases: List<Release>? = null

    @Volatile
    private var announced: String? = null

    /** Why the last check failed, in English, or `null` if it worked (or none has run). */
    @Volatile
    public var problem: String? = null
        private set

    /** The newest release on the channel that's newer than [current], once a check found one; `null` otherwise. */
    public val latest: Release?
        get() {
            val settings = settings
            if (!settings.check) return null
            return newestFor(releases ?: return null, current, settings.channel)
        }

    /**
     * Follows [settings]: starts checking (now, then every day) when `check` is on and it
     * wasn't, stops when it's off. A changed channel applies to what the last check found at
     * once, and is announced in the console if it brings a newer release in. Call it at startup
     * and on every reload.
     */
    public fun apply(settings: UpdateSettings) {
        var channelChanged = false
        val start = locked(lock) {
            val was = this.settings
            this.settings = settings
            when {
                offline || !settings.check -> {
                    task?.cancel()
                    task = null
                    releases = null
                    announced = null
                    false
                }
                task == null -> true
                else -> {
                    channelChanged = was.channel != settings.channel
                    false
                }
            }
        }
        if (channelChanged) announce()
        if (start) {
            val scheduled = platform.scheduler.run(Where.Async, delay = Duration.ZERO, period = every) { check() }
            locked(lock) {
                if (task == null && this.settings.check) task = scheduled else scheduled.cancel()
            }
        }
    }

    /** Tells [player] about the newer release, if there is one and they may know: call it as they join. */
    public fun joined(player: PlayerRef) {
        val release = latest ?: return
        if (notices.mayKnow(player)) notices.send(player, CoreMessages.updateAvailable, placeholders(release))
    }

    /** Stops checking, as the plugin is disabled. */
    public fun close() {
        locked(lock) {
            task?.cancel()
            task = null
        }
    }

    /**
     * Asks [source] now, on this thread, which must be off the server's (the daily task runs
     * it); returns the newer release, if any. Public for tests and for a plugin that wants a
     * check on demand.
     */
    public fun check(): Release? {
        if (offline || !settings.check) return null
        val found = try {
            source.releases()
        } catch (e: Throwable) {
            if (isFatal(e)) throw e
            Outcome.Err("the check failed: $e")
        }
        when (found) {
            is Outcome.Err -> problem = found.error
            is Outcome.Ok -> {
                problem = null
                releases = found.value
            }
        }
        return announce()
    }

    // Says so in the console, once per release found.
    private fun announce(): Release? {
        val release = latest ?: return null
        val first = locked(lock) {
            if (announced == release.version) false else { announced = release.version; true }
        }
        if (first) notices.send(null, CoreMessages.updateAvailable, placeholders(release))
        return release
    }

    /** The notice's placeholders: `<plugin>`, `<current>`, `<latest>` and `<url>`. */
    public fun placeholders(release: Release): Map<String, Placeholder> = mapOf(
        "plugin" to Placeholder.text(pluginName),
        "current" to Placeholder.text(current),
        "latest" to Placeholder.text(release.version),
        "url" to Placeholder.text(release.page),
    )

    public companion object {
        /** Whether the tests' `-Dxena.offline=true` is set, on the JVM; never on Wasm. */
        public val OFFLINE: Boolean get() = offlineProperty()
    }
}

internal expect fun offlineProperty(): Boolean
