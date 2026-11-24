package co.xenastudios.gradle

import java.io.File

/**
 * What the `checkTranslations` task (the `xena.manifest` convention) hands core-jvm's
 * `TranslationCheckMain`, which checks every shipped `lang/<tag>.yml` against the plugin's
 * English messages. The check itself is the core's (`TranslationCheck`), so the build and a
 * plugin's own tests can't disagree on what's broken.
 */
internal object Translations {
    /** The folders a plugin's language files are in, under its project folder, by convention. */
    val FOLDERS = listOf("src/commonMain/resources/lang", "src/jvmMain/resources/lang", "src/main/resources/lang")

    /** The shipped language files in [folders], by name: what the check reads. */
    fun files(folders: Iterable<File>): List<File> = folders.filter { it.isDirectory }
        .flatMap { folder -> folder.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".yml") } }
        .sortedBy { it.name }

    /** The main class's arguments: the reference [source], each folder of [folders], and [allowMissing]. */
    fun arguments(source: String, folders: Iterable<File>, allowMissing: Boolean): List<String> =
        listOf("--source", source) + folders.flatMap { listOf("--lang", it.path) } + if (allowMissing) listOf("--allow-missing") else emptyList()
}
