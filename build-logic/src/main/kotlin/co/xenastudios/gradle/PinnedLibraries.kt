package co.xenastudios.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Writes `META-INF/xena/libraries.txt`, the list the core's loader downloads from (each jar
 * with the SHA-256 it must have and a class to check for), or [resource]'s other list, after
 * checking every jar against
 * `gradle/library-pins.txt`. A jar with no pin or another hash fails the build, naming it;
 * `-PupdatePins` writes the hashes Gradle resolved into the pins file instead, for a deliberate
 * bump. Applied by the xena.fetched-libraries convention.
 */
@DisableCachingByDefault(because = "Hashing a few jars is quicker than the cache")
abstract class PinnedLibraries : DefaultTask() {

    /** `group:artifact:version` of each jar in [jars], in the same order. */
    @get:Input
    abstract val coordinates: ListProperty<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val jars: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val pins: RegularFileProperty

    @get:Input
    abstract val update: Property<Boolean>

    /** The list's path in the jar: the startup libraries', or `META-INF/xena/on-demand-libraries.txt`. */
    @get:Input
    abstract val resource: Property<String>

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    init {
        resource.convention("META-INF/xena/libraries.txt")
        // Rewriting the pins must happen even when nothing else changed.
        outputs.upToDateWhen { !update.get() }
    }

    @TaskAction
    fun write() {
        val files = jars.files.toList()
        val ids = coordinates.get()
        check(files.size == ids.size) { "${ids.size} coordinates for ${files.size} jars" }
        val resolved = ids.zip(files) { id, file -> LibraryPins.Jar(id, LibraryPins.sha256(file)) to file }
        val pinsFile = pins.get().asFile
        // Projects can run this at once; the pins file is shared.
        synchronized(LOCK) {
            val problems = LibraryPins.problems(resolved.map { it.first }, LibraryPins.parse(pinsFile.readText()))
            if (problems.isNotEmpty()) {
                if (update.get()) {
                    pinsFile.writeText(LibraryPins.updated(pinsFile.readText(), resolved.map { it.first }))
                    logger.lifecycle("Pinned in ${pinsFile.name}:\n  " + problems.joinToString("\n  "))
                } else {
                    throw GradleException(
                        "The libraries downloaded at runtime don't match gradle/library-pins.txt:\n  " +
                            problems.joinToString("\n  ") +
                            "\nCheck the new version is the one you meant (the hash is Maven Central's jar), then run" +
                            " this build again with -PupdatePins and commit the pins file."
                    )
                }
            }
        }
        val manifest = LibraryPins.manifest(resolved.map { (jar, file) -> jar to LibraryPins.probe(file) })
        output.get().asFile.resolve(resource.get()).apply { parentFile.mkdirs() }.writeText(manifest)
    }

    private companion object {
        val LOCK = Any()
    }
}
