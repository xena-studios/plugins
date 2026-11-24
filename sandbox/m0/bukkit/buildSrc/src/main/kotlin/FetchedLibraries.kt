import java.security.MessageDigest
import java.util.zip.ZipFile
import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * Writes `META-INF/m0/libraries.txt`: for each group (named by a class that shows the server
 * has it), every jar of the group (transitively) with its SHA-256, pinned at build time, and a
 * class of its own. The bootstrap fetches a jar only when that class isn't visible already.
 */
abstract class FetchedLibraries : DefaultTask() {
    /** probe class -> resolved jars of the group, as "group:artifact:version sha256" lines. */
    @get:Input
    abstract val groups: MapProperty<String, List<String>>

    @get:OutputFile
    abstract val output: RegularFileProperty

    fun group(probe: String, configuration: Configuration) {
        groups.put(probe, project.provider {
            configuration.incoming.artifacts.artifacts.map { artifact ->
                val id = artifact.id.componentIdentifier as ModuleComponentIdentifier
                val sha = MessageDigest.getInstance("SHA-256").digest(artifact.file.readBytes())
                    .joinToString("") { "%02x".format(it) }
                "${id.group}:${id.module}:${id.version} $sha ${probe(artifact.file)}"
            }.sorted()
        })
    }

    /** A class in the jar, to ask the server's class loaders whether they already have the jar. */
    private fun probe(jar: java.io.File): String = ZipFile(jar).use { zip ->
        zip.entries().asSequence().map { it.name }
            .filter { it.endsWith(".class") && !it.startsWith("META-INF/") && '$' !in it && !it.endsWith("module-info.class") && !it.endsWith("package-info.class") }
            .minBy { it.length }
            .removeSuffix(".class").replace('/', '.')
    }

    @TaskAction
    fun write() {
        val text = buildString {
            groups.get().forEach { (probe, jars) ->
                append("probe ").append(probe).append('\n')
                jars.forEach { append("jar ").append(it).append('\n') }
            }
        }
        output.get().asFile.apply { parentFile.mkdirs() }.writeText(text)
    }
}
