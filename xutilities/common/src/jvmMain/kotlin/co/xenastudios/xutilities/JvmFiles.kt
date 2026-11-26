package co.xenastudios.xutilities

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.config.Config
import co.xenastudios.core.common.config.YamlNode
import co.xenastudios.core.common.family.Family
import co.xenastudios.core.common.i18n.LanguageTag
import co.xenastudios.core.common.i18n.Translations
import co.xenastudios.core.common.platform.Log
import co.xenastudios.core.jvm.config.CarriedMessages
import co.xenastudios.core.jvm.config.ConfigFile
import co.xenastudios.core.jvm.config.RememberingYaml
import co.xenastudios.core.jvm.i18n.LangFolder
import co.xenastudios.xutilities.config.Legacy
import co.xenastudios.xutilities.config.LinkCommand
import co.xenastudios.xutilities.config.XUtilitiesConfig
import java.nio.charset.StandardCharsets
import java.nio.file.Files as NioFiles
import java.nio.file.Path

/**
 * xUtilities' files on a JVM server: the family's `config.yml`, then `config.yml` in the plugin's
 * [folder] (carried forward from 1.x and 2.x, the old file copied aside as `config.yml.bak-v2`),
 * then `lang/`. Off the server's threads at startup; on the global thread for a reload.
 *
 * Coming from 2.x, the messages an owner edited in `config.yml` go into `lang/en.yml` first
 * ([Legacy.messages]), as the file's starting point: the language files are then written in
 * full around them.
 */
public class JvmFiles(
    private val folder: Path,
    private val family: Family,
    /** The jar's translations: [LangFolder.shipped] of [LANGUAGES]. */
    private val shipped: Map<LanguageTag, String>,
    private val log: Log,
) : Files {
    // config.yml is read three ways as the plugin enables (for 2.x's messages, against the
    // schema, for the link commands), on the server thread: one parse of the same text does.
    private val yaml = RememberingYaml()

    private val file = ConfigFile(XUtilitiesConfig, folder.resolve("config.yml"), yaml)

    @Volatile
    private var last: Config? = null

    override fun load(): Loaded {
        val config = config()
        return translations(config.config, config.problems)
    }

    /**
     * `config.yml` alone, carried forward if it's old: what a server's startup reads on the
     * server thread, so the commands are registered as the plugin enables, as they always were.
     * [translations] follows, off the server thread. Logs nothing: [translations] logs the problems.
     */
    public fun config(): Loaded {
        val problems = ArrayList<String>()
        carryMessages(problems)
        val config = when (val loaded = file.load()) {
            is Outcome.Ok -> {
                loaded.value.notices.forEach { log.info("config.yml: ${it.message}") }
                loaded.value.problems.forEach { problems += it.describe("config.yml") }
                loaded.value.config
            }
            is Outcome.Err -> {
                problems += "${loaded.error}; ${if (last == null) "using the defaults" else "keeping the settings from before"} until it's fixed."
                last ?: XUtilitiesConfig.defaults
            }
        }
        last = config
        problems += LinkCommand.problems(read()?.let { (it as? YamlNode.Mapping)?.get("info-commands") })
        yaml.forget()
        return Loaded(config, Translations.of(XUtilitiesMessages.catalog, emptyMap()), problems)
    }

    /**
     * The family's settings and `lang/`, for [config] (its look and language): every language
     * the jar ships written in full, the owner's edits kept. Logs every problem, [configProblems]
     * among them.
     */
    public fun translations(config: Config, configProblems: List<String> = emptyList()): Loaded {
        val problems = ArrayList(configProblems)
        val familyLoad = family.load()
        familyLoad.error?.let { problems += it.message }
        familyLoad.problems.forEach { problems += it.describe("plugins/Xena/config.yml") }
        familyLoad.notices.forEach { log.info("plugins/Xena/config.yml: ${it.message}") }
        val look = family.look(config, XUtilitiesConfig.messages)
        val lang = LangFolder(folder, XUtilitiesMessages.catalog, shipped).load(look.languages, look.theme)
        lang.notices.forEach { log.info(it.message) }
        lang.problems.forEach { problems += it.message }
        problems.forEach(log::warn)
        return Loaded(config, lang.translations, problems)
    }

    // A 1.x or 2.x config's edited messages, into lang/en.yml before anything else writes it.
    private fun carryMessages(problems: MutableList<String>) {
        val root = read() as? YamlNode.Mapping ?: return
        val version = Legacy.version(root) ?: return
        if (version >= XUtilitiesConfig.version) return
        val edited = Legacy.messages(root)
        if (edited.isEmpty()) return
        CarriedMessages.write(folder, edited, "xUtilities ${if (version == 1) "1.x" else "2.x"}", "config.yml.bak-v$version", log)?.let { problems += it }
    }

    private fun read(): YamlNode? {
        val path = folder.resolve("config.yml")
        val text = try {
            String(NioFiles.readAllBytes(path), StandardCharsets.UTF_8)
        } catch (e: Exception) {
            return null
        }
        return (yaml.read(text) as? Outcome.Ok)?.value
    }


    public companion object {
        /** The languages the jar ships besides English: drafts until native speakers have read them. */
        @JvmField
        public val LANGUAGES: List<LanguageTag> = listOf("de", "es", "fr", "pt-BR", "ru", "pl", "zh-CN").map { LanguageTag.of(it) }
    }
}
