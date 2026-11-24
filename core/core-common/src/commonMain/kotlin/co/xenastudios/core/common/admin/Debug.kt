package co.xenastudios.core.common.admin

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.newPending
import co.xenastudios.core.api.onSettled
import co.xenastudios.core.api.pendingOf
import co.xenastudios.core.api.resolve
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.isFatal
import co.xenastudios.core.common.locked
import co.xenastudios.core.common.platform.Platform
import co.xenastudios.core.common.platform.StoragePath
import co.xenastudios.core.common.platform.readText
import co.xenastudios.core.common.platform.writeText
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.scheduler.later
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Where `/<plugin> debug` uploads its report: mclo.gs on the JVM (`core-jvm`'s `MclogsPaste`),
 * the Minecraft community's paste site, which keeps a report for 90 days and is what support
 * channels already ask for. Swappable, so a network can use its own.
 */
public interface PasteService {
    /** Its name, for the console: `mclo.gs`. */
    public val name: String

    /**
     * Uploads [text] and gives its link, or why it couldn't, in English. Called on an async
     * task, so it may wait for the network there; it never throws.
     */
    public fun upload(text: String): Pending<Outcome<String, String>>
}

/** Where a debug report went. */
public sealed class DebugResult {
    /** Uploaded, at [url]. */
    public data class Uploaded(public val url: String) : DebugResult()

    /**
     * Written to [file] in the plugin's folder: uploads are off ([uploadError] is `null`), or
     * the upload failed, as [uploadError] says.
     */
    public data class Saved(public val file: String, public val uploadError: String?) : DebugResult()

    /** Neither uploaded nor written, as [reason] says. */
    public data class Failed(public val reason: String) : DebugResult()
}

/**
 * `/<plugin> debug`: runs the plugin's health [checks], then writes a report for support with
 * the versions, the platform, the checks, the [errors] kept lately and the config [files] with
 * their secrets removed ([Secrets]), and uploads it to [paste], or writes it to `debug/` in
 * the plugin's folder when there's no paste service or the upload fails.
 *
 * Nothing waits on a server thread: the checks answer through [Pending]s, a check that hasn't
 * answered after [timeout] fails, and the report is read, written and uploaded on an async
 * task.
 */
public class Debug(
    private val platform: Platform,
    /** The plugin's name and version, the report's first line: `xSample 1.0.0`. */
    private val plugin: String,
    private val checks: List<HealthCheck>,
    /** More lines for the report's top, such as the Java version and the other plugins installed, by name. */
    private val about: () -> Map<String, String> = { emptyMap() },
    private val errors: RecentErrors? = null,
    /** Files in the plugin's folder to put in the report, secrets removed: `config.yml`. */
    private val files: List<String> = listOf("config.yml"),
    /** Where to upload; `null` to always write a file instead. */
    private val paste: PasteService? = null,
    private val timeout: Duration = 10.seconds,
) {
    /** Runs every check at once, and gives their results in the checks' order once all have answered. */
    public fun check(): Pending<List<Health>> {
        if (checks.isEmpty()) return pendingOf(emptyList())
        val lock = Any()
        val results = arrayOfNulls<Health>(checks.size)
        var left = checks.size
        val done = newPending<List<Health>>()
        checks.forEachIndexed { index, check ->
            val answered = newPending<Health>()
            val late = platform.scheduler.later(Where.Async, timeout) {
                answered.resolve(HealthCheck.broken(check.name, "it didn't answer within ${timeout.inWholeSeconds}s"))
            }
            check.run().onSettled { health, error ->
                answered.resolve(health ?: HealthCheck.broken(check.name, "it failed: $error"))
            }
            answered.onSettled { health, _ ->
                late.cancel()
                val all = locked(lock) {
                    results[index] = health
                    left--
                    if (left == 0) results.map { it!! } else null
                }
                if (all != null) done.resolve(all)
            }
        }
        return done
    }

    /** The report, with [results] from [check]: reads the files, so call it on an async task. Secrets are removed. */
    public fun report(results: List<Health>): String = Secrets.redact(buildString {
        append(plugin).append(" debug report, ").append(platform.clock.now()).append('\n')
        append("Platform: ").append(platform.name).append(" (").append(HealthChecks.describe(platform.threading))
        if (platform.development) append(", development build")
        append(")\n")
        for ((key, value) in safe { about() } ?: emptyMap()) append(key).append(": ").append(value).append('\n')

        append("\nHealth\n")
        if (results.isEmpty()) append("  (no checks)\n")
        for (health in results) append("  ").append(health.status.name.padEnd(8)).append(health.english()).append('\n')

        val recent = errors?.recent.orEmpty()
        append("\nRecent warnings and errors (").append(recent.size).append(")\n")
        for (entry in recent) append(entry.toString().prependIndent("  ")).append('\n')

        for (file in files) {
            append('\n').append(file).append(" (secrets removed)\n")
            val path = StoragePath.parse(file).getOrNull()
            when (val read = path?.let { platform.storage.readText(it) }) {
                null -> append("  (not a path in the plugin's folder)\n")
                is Outcome.Err -> append("  (couldn't be read: ").append(read.error.detail).append(")\n")
                is Outcome.Ok -> {
                    val lines = read.value?.lines() ?: listOf("(no file)")
                    lines.take(MAX_FILE_LINES).forEach { append("  ").append(it).append('\n') }
                    if (lines.size > MAX_FILE_LINES) append("  (").append(lines.size - MAX_FILE_LINES).append(" more lines)\n")
                }
            }
        }
    })

    /**
     * Uploads [report], or writes it to `debug/` in the plugin's folder when there's no paste
     * service or the upload fails. Call it on an async task.
     */
    public fun publish(report: String): Pending<DebugResult> {
        val service = paste ?: return pendingOf(save(report, null))
        val done = newPending<DebugResult>()
        val upload = try {
            service.upload(report)
        } catch (e: Throwable) {
            if (isFatal(e)) throw e
            pendingOf(Outcome.Err("${service.name} failed: $e"))
        }
        upload.onSettled { result, error ->
            done.resolve(
                when {
                    result is Outcome.Ok -> DebugResult.Uploaded(result.value)
                    // The upload ran on an async task; writing the file can stay there.
                    else -> save(report, (result as? Outcome.Err)?.error ?: "${service.name} failed: $error")
                },
            )
        }
        return done
    }

    private fun save(report: String, uploadError: String?): DebugResult {
        // Instant's text has colons, which no path here may.
        val name = "debug/debug-" + platform.clock.now().toString().replace(':', '-') + ".txt"
        return when (val written = platform.storage.writeText(StoragePath.of(name), report)) {
            is Outcome.Ok -> DebugResult.Saved(name, uploadError)
            is Outcome.Err -> DebugResult.Failed((uploadError?.let { "$it; " } ?: "") + "couldn't write $name: ${written.error.detail}")
        }
    }

    private inline fun <T> safe(block: () -> T): T? = try {
        block()
    } catch (e: Throwable) {
        if (isFatal(e)) throw e
        null
    }

    private companion object {
        // mclo.gs takes up to 25,000 lines; a config past this is a mistake, not a setting.
        const val MAX_FILE_LINES = 2_000
    }
}
