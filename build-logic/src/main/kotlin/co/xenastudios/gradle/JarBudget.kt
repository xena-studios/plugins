package co.xenastudios.gradle

import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Fails when a rewrite jar is bigger than its budget in `gradle/jar-budgets.txt`, or has none.
 * One file for every jar, so a budget is raised in review, beside the reason, and the table
 * reads as a whole (ROADMAP.md, "Fast, robust, light": no bigger than today's jar on the
 * Bukkit family, today's + 300 KB where the stdlib is shaded).
 *
 * The file has one jar a line, `<Gradle project path> <KiB>`, then an optional `# reason`.
 */
@DisableCachingByDefault(because = "Reading one file's size is quicker than the cache")
abstract class JarBudget : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val jar: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val budgets: RegularFileProperty

    /** The project the jar belongs to, as the budgets file names it: `:xbans:bukkit`. */
    @get:Input
    abstract val projectPath: Property<String>

    @TaskAction
    fun check() {
        val file = jar.get().asFile
        val path = projectPath.get()
        val table = budgets.get().asFile
        val budget = read(table)[path]
            ?: throw GradleException("${file.name} has no size budget: add \"$path <KiB>\" to $table.")
        val kib = file.length() / 1024.0
        if (kib > budget) {
            throw GradleException(
                "%s is %.1f KiB, over its %d KiB budget in %s. Shrink it, or raise the budget there with the reason."
                    .format(file.name, kib, budget, table.name),
            )
        }
        logger.lifecycle("%s: %.1f of %d KiB".format(file.name, kib, budget))
    }

    companion object {
        /** The budgets in [file], by project path. A malformed line fails, naming it. */
        fun read(file: File): Map<String, Int> = file.readLines().withIndex()
            .map { (i, line) -> i + 1 to line.substringBefore('#').trim() }
            .filter { it.second.isNotEmpty() }
            .associate { (n, line) ->
                val parts = line.split(Regex("\\s+"))
                val kib = parts.getOrNull(1)?.toIntOrNull()
                if (parts.size != 2 || !parts[0].startsWith(":") || kib == null || kib <= 0) {
                    throw GradleException("${file.name}:$n: expected \"<project path> <KiB>\", found \"$line\"")
                }
                parts[0] to kib
            }
    }
}
