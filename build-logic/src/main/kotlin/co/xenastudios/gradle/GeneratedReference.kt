package co.xenastudios.gradle

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
import java.io.File

/**
 * The files `xenaManifest` writes for the docs, and how they're compared with the copies
 * committed in `docs/src/generated/` (the `xena.manifest` convention). The same flow as the
 * config goldens: the build fails when they differ, and says how to update them.
 */
internal object GeneratedReference {
    /** The schema every manifest follows, beside the plugins' folders. */
    const val SCHEMA = "manifest.schema.json"

    /** The files in a plugin's folder that the manifest task writes. */
    val PLUGIN_FILES = listOf("manifest.json", "config.yml")

    /** The files of [plugin] that are generated, relative to the generated folder: its own, then the schema. */
    fun files(plugin: String): List<String> = PLUGIN_FILES.map { "$plugin/$it" } + SCHEMA

    /**
     * How [committed] differs from [fresh] for [plugin]: each generated file that's missing,
     * changed (with the first line that differs) or no longer generated. Empty when they match.
     */
    fun stale(fresh: File, committed: File, plugin: String): List<String> = files(plugin).mapNotNull { path ->
        val new = fresh.resolve(path)
        val old = committed.resolve(path)
        when {
            !new.isFile && !old.isFile -> null
            !new.isFile -> "$path: no longer generated"
            !old.isFile -> "$path: not committed"
            else -> {
                val a = old.readLines()
                val b = new.readLines()
                if (a == b) {
                    null
                } else {
                    val i = (0 until maxOf(a.size, b.size)).first { a.getOrNull(it) != b.getOrNull(it) }
                    "$path: line ${i + 1}\n    committed: ${a.getOrNull(i) ?: "(end of file)"}\n    fresh:     ${b.getOrNull(i) ?: "(end of file)"}"
                }
            }
        }
    }

    /** Makes [committed]'s copy of [plugin]'s files match [fresh]: copies each, and deletes one no longer generated. */
    fun update(fresh: File, committed: File, plugin: String) {
        for (path in files(plugin)) {
            val new = fresh.resolve(path)
            val old = committed.resolve(path)
            if (new.isFile) {
                old.parentFile.mkdirs()
                new.copyTo(old, overwrite = true)
            } else {
                old.delete()
            }
        }
    }
}

/**
 * Fails when `docs/src/generated/` has a stale copy of the plugin's reference, naming each
 * file and the first line that differs. With `-PupdateGolden` it updates the copy instead, as
 * the config goldens' tests do.
 */
@DisableCachingByDefault(because = "Compares files with the working tree; nothing to cache")
abstract class CheckXenaManifest : DefaultTask() {
    /** What `xenaManifest` just wrote. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val fresh: DirectoryProperty

    // Not an input: it may not exist yet, which is a failure to report, not an error.
    @get:Internal
    abstract val committed: DirectoryProperty

    @get:Input
    abstract val plugin: Property<String>

    @get:Input
    abstract val update: Property<Boolean>

    @TaskAction
    fun check() {
        val freshRoot = fresh.get().asFile
        val committedRoot = committed.get().asFile
        val stale = GeneratedReference.stale(freshRoot, committedRoot, plugin.get())
        when {
            stale.isEmpty() -> logger.info("${plugin.get()}'s reference in ${committedRoot.path} is up to date")
            update.get() -> {
                GeneratedReference.update(freshRoot, committedRoot, plugin.get())
                logger.lifecycle("Updated ${plugin.get()}'s reference in ${committedRoot.path}:\n  " + stale.joinToString("\n  "))
            }
            else -> throw GradleException(
                "${plugin.get()}'s generated reference in docs/src/generated is out of date:\n  " + stale.joinToString("\n  ") +
                    "\nIf the change is intended, run ./gradlew updateXenaManifest (or the build with -PupdateGolden) and commit the result.",
            )
        }
    }
}

/** Copies the plugin's fresh reference into `docs/src/generated/`, to commit. */
@DisableCachingByDefault(because = "Writes into the working tree")
abstract class UpdateXenaManifest : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val fresh: DirectoryProperty

    @get:Internal
    abstract val committed: DirectoryProperty

    @get:Input
    abstract val plugin: Property<String>

    @TaskAction
    fun update() {
        GeneratedReference.update(fresh.get().asFile, committed.get().asFile, plugin.get())
        logger.lifecycle("Wrote ${plugin.get()}'s reference to ${committed.get().asFile.path}")
    }
}
