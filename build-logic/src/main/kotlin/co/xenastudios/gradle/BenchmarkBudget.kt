package co.xenastudios.gradle

import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File

/**
 * Checks the newest JMH report under [reports] (kotlinx-benchmark's JSON, with JMH's `gc`
 * profiler) against [budgets]: one benchmark a line, `<Class.method[param=value]> <ns/op>
 * <B/op>`, `-` for no limit. Fails when a benchmark is over either limit or has no line, so a
 * new benchmark gets a budget on purpose, and writes the table to [summary] (and the job
 * summary on GitHub Actions).
 *
 * Times move with the machine, so their limits are loose, a tripwire for a hot path that got
 * many times slower; bytes allocated per operation don't, so theirs are close to today's.
 */
@DisableCachingByDefault(because = "It reads the report the benchmark run just wrote")
abstract class BenchmarkBudget : DefaultTask() {

    /** The folder the benchmark configuration writes its timestamped reports into. */
    @get:Internal
    abstract val reports: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val budgets: RegularFileProperty

    @get:OutputFile
    abstract val summary: RegularFileProperty

    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun check() {
        val report = reports.get().asFile.walkTopDown().filter { it.isFile && it.extension == "json" }
            .maxByOrNull { it.lastModified() }
            ?: throw GradleException("No benchmark report under ${reports.get().asFile}: run the benchmarks first")
        val limits = parseBudgets(budgets.get().asFile)
        val results = parseReport(report)
        val rows = mutableListOf<String>()
        val problems = mutableListOf<String>()
        for (result in results) {
            val limit = limits[result.name]
            if (limit == null) {
                problems += "${result.name} has no line in ${budgets.get().asFile.name}"
                rows += row(result, null, "no budget")
                continue
            }
            val over = buildList {
                if (limit.nanos != null && result.nanos > limit.nanos) add("%.1f ns/op over %.1f".format(result.nanos, limit.nanos))
                if (limit.bytes != null && result.bytes != null && result.bytes > limit.bytes) {
                    add("%.0f B/op over %.0f".format(result.bytes, limit.bytes))
                }
            }
            over.forEach { problems += "${result.name}: $it" }
            rows += row(result, limit, if (over.isEmpty()) "ok" else "**over**")
        }
        (limits.keys - results.map { it.name }.toSet()).forEach { problems += "$it is in ${budgets.get().asFile.name} but didn't run" }
        val table = buildString {
            append("## Benchmark budgets: ").append(if (problems.isEmpty()) "PASS" else "FAIL").append("\n\n")
            append("| Benchmark | ns/op | Limit | B/op | Limit | |\n|---|--:|--:|--:|--:|---|\n")
            rows.forEach { append(it).append('\n') }
        }
        summary.get().asFile.apply { parentFile.mkdirs() }.writeText(table)
        System.getenv("GITHUB_STEP_SUMMARY")?.takeIf { it.isNotEmpty() }?.let { File(it).appendText(table) }
        logger.lifecycle(table)
        if (problems.isNotEmpty()) {
            throw GradleException("Benchmarks over budget (core/core-benchmarks/budgets.txt):\n  " + problems.joinToString("\n  "))
        }
    }

    private fun row(result: Result, limit: Limit?, verdict: String): String =
        "| ${result.name} | %.1f | %s | %s | %s | $verdict |".format(
            result.nanos,
            limit?.nanos?.let { "%.0f".format(it) } ?: "-",
            result.bytes?.let { "%.0f".format(it) } ?: "?",
            limit?.bytes?.let { "%.0f".format(it) } ?: "-",
        )

    private data class Limit(val nanos: Double?, val bytes: Double?)

    private data class Result(val name: String, val nanos: Double, val bytes: Double?)

    private fun parseBudgets(file: File): Map<String, Limit> = file.readLines().mapNotNull { raw ->
        val line = raw.substringBefore('#').trim()
        if (line.isEmpty()) return@mapNotNull null
        val parts = line.split(Regex("\\s+"))
        if (parts.size != 3) throw GradleException("${file.name}: \"$raw\" isn't \"<benchmark> <ns/op> <B/op>\"")
        parts[0] to Limit(parts[1].takeIf { it != "-" }?.toDouble(), parts[2].takeIf { it != "-" }?.toDouble())
    }.toMap()

    @Suppress("UNCHECKED_CAST")
    private fun parseReport(file: File): List<Result> = (JsonSlurper().parse(file) as List<Map<String, Any?>>).map { entry ->
        val name = (entry["benchmark"] as String).split('.').takeLast(2).joinToString(".")
        val params = (entry["params"] as? Map<String, Any?>)?.entries?.joinToString(",") { "${it.key}=${it.value}" }
        val primary = entry["primaryMetric"] as Map<String, Any?>
        val unit = primary["scoreUnit"] as String
        if (unit != "ns/op") throw GradleException("${file.name}: ${entry["benchmark"]} is in $unit, not ns/op")
        val secondary = entry["secondaryMetrics"] as? Map<String, Map<String, Any?>> ?: emptyMap()
        val bytes = secondary.entries.firstOrNull { it.key.endsWith("gc.alloc.rate.norm") }?.value?.get("score") as Number?
        Result(if (params.isNullOrEmpty()) name else "$name[$params]", (primary["score"] as Number).toDouble(), bytes?.toDouble())
    }
}
