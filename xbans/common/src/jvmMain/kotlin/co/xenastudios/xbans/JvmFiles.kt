package co.xenastudios.xbans

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
import co.xenastudios.xbans.config.Legacy
import co.xenastudios.xbans.config.XBansConfig
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.Files as NioFiles

/**
 * xBans' files on a JVM server or proxy: the family's `config.yml`, then `config.yml` in the
 * plugin's [folder] (carried forward from 1.x, the old file copied aside as `config.yml.bak-v1`),
 * then `lang/`. Coming from 1.x, the messages an owner edited in `config.yml` go into `lang/en.yml`
 * first ([Legacy.messages]), as the file's starting point.
 */
public class JvmFiles(
    private val folder: Path,
    private val family: Family,
    /** The jar's translations: [LangFolder.shipped] of [LANGUAGES]. */
    private val shipped: Map<LanguageTag, String>,
    private val log: Log,
) : Files {
    private val path = folder.resolve("config.yml")
    private val yaml = RememberingYaml()
    private val file = ConfigFile(XBansConfig, path, yaml)

    @Volatile
    private var last: Config? = null

    override fun load(): Loaded {
        val config = config()
        return translations(config.config, config.problems)
    }

    /** `config.yml` alone, carried forward if it's old: what a server's startup reads as the plugin enables. Logs nothing. */
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
                last ?: XBansConfig.defaults
            }
        }
        last = config
        yaml.forget()
        return Loaded(config, Translations.of(XBansMessages.catalog, emptyMap()), problems)
    }

    /** The family's settings and `lang/`, for [config]: every language the jar ships written in full, the owner's edits kept. Logs every problem. */
    public fun translations(config: Config, configProblems: List<String> = emptyList()): Loaded {
        val problems = ArrayList(configProblems)
        val familyLoad = family.load()
        familyLoad.error?.let { problems += it.message }
        familyLoad.problems.forEach { problems += it.describe("plugins/Xena/config.yml") }
        familyLoad.notices.forEach { log.info("plugins/Xena/config.yml: ${it.message}") }
        val look = family.look(config, XBansConfig.messages)
        val lang = LangFolder(folder, XBansMessages.catalog, shipped).load(look.languages, look.theme)
        lang.notices.forEach { log.info(it.message) }
        lang.problems.forEach { problems += it.message }
        problems.forEach(log::warn)
        return Loaded(config, lang.translations, problems)
    }

    // A 1.x config's edited messages, into lang/en.yml before anything else writes it.
    private fun carryMessages(problems: MutableList<String>) {
        val root = read() as? YamlNode.Mapping ?: return
        // Read here, so a current config never loads Legacy's tables.
        val version = (root["config-version"] as? YamlNode.Scalar)?.content?.trim()?.toIntOrNull() ?: return
        if (version >= XBansConfig.version) return
        val edited = Legacy.messages(root)
        if (edited.isEmpty()) return
        CarriedMessages.write(folder, edited, "xBans 1.x", "config.yml.bak-v$version", log)?.let { problems += it }
    }

    private fun read(): YamlNode? {
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
