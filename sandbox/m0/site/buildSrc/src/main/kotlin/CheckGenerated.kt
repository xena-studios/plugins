import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Fails when a file the build generates for the docs differs from the committed copy, or is
 * missing, the way core's Golden does for config.yml today. Run updateXenaManifest to fix.
 */
@DisableCachingByDefault(because = "Compares files; nothing to cache")
abstract class CheckGenerated : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val fresh: DirectoryProperty

    // Not an @InputDirectory: the committed copy may not exist yet, and that's a failure, not an error.
    @get:Internal
    abstract val committed: DirectoryProperty

    @get:Input
    abstract val fixCommand: Property<String>

    @TaskAction
    fun check() {
        val freshRoot = fresh.get().asFile
        val committedRoot = committed.get().asFile
        val stale = mutableListOf<String>()
        freshRoot.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { file ->
            val rel = file.relativeTo(freshRoot).invariantSeparatorsPath
            val other = committedRoot.resolve(rel)
            if (!other.isFile) {
                stale += "$rel: not committed"
                return@forEach
            }
            val a = other.readLines()
            val b = file.readLines()
            if (a != b) {
                val i = (0 until maxOf(a.size, b.size)).first { a.getOrNull(it) != b.getOrNull(it) }
                stale += "$rel: line ${i + 1}\n    committed: ${a.getOrNull(i) ?: "(end of file)"}\n    fresh:     ${b.getOrNull(i) ?: "(end of file)"}"
            }
        }
        if (stale.isNotEmpty()) {
            throw GradleException(
                "The docs' generated reference is out of date:\n  " + stale.joinToString("\n  ") +
                    "\nIf the change is intended, run ${fixCommand.get()} and commit the result.",
            )
        }
        logger.lifecycle("Generated reference up to date (${committedRoot.path})")
    }
}
