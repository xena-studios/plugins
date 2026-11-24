package co.xenastudios.core.jvm.i18n

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.config.YamlReader
import co.xenastudios.core.common.i18n.LangFiles
import co.xenastudios.core.common.i18n.LangNotice
import co.xenastudios.core.common.i18n.LangProblem
import co.xenastudios.core.common.i18n.LanguageSettings
import co.xenastudios.core.common.i18n.LanguageTag
import co.xenastudios.core.common.i18n.MessageCatalog
import co.xenastudios.core.common.i18n.ShippedLanguage
import co.xenastudios.core.common.i18n.Translations
import co.xenastudios.core.common.text.Theme
import co.xenastudios.core.jvm.config.ConfigFile
import co.xenastudios.core.jvm.config.JvmYaml
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/**
 * A plugin's `lang/` folder on disk, read and written against its [catalog] by [LangFiles]:
 * every language the jar ships ([shipped], by tag, each file's text) is written there in
 * full, English always, and any `<tag>.yml` the owner adds is a language too.
 *
 * - A file that's missing is written again; one that can't be read, or isn't valid YAML, is
 *   left alone and its language uses the shipped text, with a problem saying why.
 * - Writes go to a temporary file that's moved into place, as configs' do.
 * - A file whose name isn't a tag as the core writes it (`pt_br.yml` for `pt-BR.yml`) is
 *   reported and skipped, so one language never has two files.
 *
 * Call it from one thread at a time, off the server thread: it reads and writes files.
 */
public class LangFolder(
    /** The plugin's data folder; the files are in its `lang/` folder. */
    public val folder: Path,
    public val catalog: MessageCatalog,
    private val shipped: Map<LanguageTag, String>,
    private val reader: YamlReader = JvmYaml,
) {
    /** What [load] found: the [translations] to draw with, what was wrong ([problems]) and what changed ([notices]). */
    public class Loaded internal constructor(
        public val translations: Translations,
        public val problems: List<LangProblem>,
        public val notices: List<LangNotice>,
    )

    /** The `lang/` folder. */
    public val directory: Path = folder.resolve("lang")

    /**
     * Reads every language, writing the files that are missing or that loading changed, and
     * builds the [Translations] with [settings] and [theme]. Never throws: every problem is in
     * [Loaded.problems].
     */
    public fun load(settings: LanguageSettings = LanguageSettings.DEFAULT, theme: Theme = Theme.DEFAULT): Loaded {
        val problems = mutableListOf<LangProblem>()
        val notices = mutableListOf<LangNotice>()
        val languages = LinkedHashMap<LanguageTag, ShippedLanguage?>()
        languages[LanguageTag.ENGLISH] = null
        for ((tag, text) in shipped) {
            when (val read = LangFiles.readShipped(tag, text, reader)) {
                is Outcome.Ok -> languages[tag] = read.value
                is Outcome.Err -> problems += LangProblem(
                    LangProblem.Kind.UNREADABLE, tag, "", read.error.line,
                    "The jar's ${read.error}; its messages are in English until it's fixed.",
                )
            }
        }
        for (tag in ownerLanguages(problems)) if (tag !in languages) languages[tag] = null

        val files = LinkedHashMap<LanguageTag, Map<String, String>>()
        for ((tag, language) in languages) {
            val file = directory.resolve("$tag.yml")
            val text = try {
                String(Files.readAllBytes(file), StandardCharsets.UTF_8)
            } catch (e: NoSuchFileException) {
                null
            } catch (e: Exception) {
                problems += LangProblem(LangProblem.Kind.UNREADABLE, tag, "", 0, "Couldn't read lang/$tag.yml (${e.reason()}); using the shipped messages.")
                files[tag] = shippedMessages(tag, language)
                continue
            }
            val load = when (val result = LangFiles.load(catalog, tag, language, text, reader)) {
                is Outcome.Ok -> result.value
                is Outcome.Err -> {
                    problems += LangProblem(LangProblem.Kind.UNREADABLE, tag, "", result.error.line, "${result.error}; it's left as it is, and the shipped messages are used.")
                    files[tag] = shippedMessages(tag, language)
                    continue
                }
            }
            files[tag] = load.messages
            problems += load.problems
            val write = load.write
            if (write == null) {
                notices += load.notices
                continue
            }
            try {
                ConfigFile.writeAtomically(file, write)
                notices += load.notices
            } catch (e: Exception) {
                problems += LangProblem(
                    LangProblem.Kind.SAVE_FAILED, tag, "", 0,
                    "Couldn't save lang/$tag.yml (${e.reason()}), so it's unchanged on disk; its messages are in use anyway.",
                )
            }
        }
        val translations = Translations.of(catalog, files, settings, theme)
        return Loaded(translations, problems + translations.problems, notices)
    }

    private fun shippedMessages(tag: LanguageTag, language: ShippedLanguage?): Map<String, String> =
        (LangFiles.load(catalog, tag, language, null, reader) as? Outcome.Ok)?.value?.messages.orEmpty()

    // The tags of the files in lang/, named as the core names them.
    private fun ownerLanguages(problems: MutableList<LangProblem>): List<LanguageTag> {
        if (!Files.isDirectory(directory)) return emptyList()
        val out = ArrayList<LanguageTag>()
        try {
            Files.newDirectoryStream(directory, "*.yml").use { stream ->
                for (path in stream) {
                    val name = path.fileName.toString().removeSuffix(".yml")
                    val tag = LanguageTag.parse(name) ?: continue
                    if (tag.toString() != name) {
                        problems += LangProblem(
                            LangProblem.Kind.UNREADABLE, tag, "", 0,
                            "lang/$name.yml isn't read: name it $tag.yml (the language tag as the plugin writes it).",
                        )
                        continue
                    }
                    out += tag
                }
            }
        } catch (e: Exception) {
            problems += LangProblem(LangProblem.Kind.UNREADABLE, LanguageTag.ENGLISH, "", 0, "Couldn't list lang/ (${e.reason()}).")
        }
        return out.sortedBy { it.toString() }
    }

    public companion object {
        /**
         * The jar's translations: `lang/<tag>.yml` for each of [tags], read from [loader] (the
         * plugin's class loader). No classpath scanning, so the plugin lists what it ships.
         * English is in the code, never a file. A tag whose file is missing is left out.
         */
        @JvmStatic
        public fun shipped(loader: ClassLoader, tags: List<LanguageTag>): Map<LanguageTag, String> {
            val out = LinkedHashMap<LanguageTag, String>()
            for (tag in tags) {
                if (tag == LanguageTag.ENGLISH) continue
                val stream = loader.getResourceAsStream("lang/$tag.yml") ?: continue
                out[tag] = stream.use { String(it.readBytes(), StandardCharsets.UTF_8) }
            }
            return out
        }

        private fun Exception.reason(): String = when (this) {
            is java.nio.file.AccessDeniedException -> "permission denied"
            is java.nio.file.FileSystemException -> reason ?: javaClass.simpleName
            else -> message ?: javaClass.simpleName
        }
    }
}
