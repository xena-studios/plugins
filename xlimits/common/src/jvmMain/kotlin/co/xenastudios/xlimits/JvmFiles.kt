package co.xenastudios.xlimits

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.config.Config
import co.xenastudios.core.common.family.Family
import co.xenastudios.core.common.i18n.LanguageTag
import co.xenastudios.core.common.i18n.Translations
import co.xenastudios.core.common.platform.Log
import co.xenastudios.core.jvm.config.ConfigFile
import co.xenastudios.core.jvm.config.RememberingYaml
import co.xenastudios.core.jvm.i18n.LangFolder
import co.xenastudios.xlimits.config.XLimitsConfig
import java.nio.file.Path

/**
 * xLimits' files on a JVM server: the family's `config.yml`, then `config.yml` in the plugin's
 * [folder], then `lang/`.
 */
public class JvmFiles(
    private val folder: Path,
    private val family: Family,
    /** The jar's translations: [LangFolder.shipped] of [LANGUAGES]. */
    private val shipped: Map<LanguageTag, String>,
    private val log: Log,
) : Files {
    private val yaml = RememberingYaml()
    private val file = ConfigFile(XLimitsConfig, folder.resolve("config.yml"), yaml)

    @Volatile
    private var last: Config? = null

    override fun load(): Loaded {
        val config = config()
        return translations(config.config, config.problems)
    }

    /** `config.yml` alone: what a server's startup reads as the plugin enables. */
    public fun config(): Loaded {
        val problems = ArrayList<String>()
        val config = when (val loaded = file.load()) {
            is Outcome.Ok -> {
                loaded.value.notices.forEach { log.info("config.yml: ${it.message}") }
                loaded.value.problems.forEach { problems += it.describe("config.yml") }
                loaded.value.config
            }
            is Outcome.Err -> {
                problems += "${loaded.error}; ${if (last == null) "using the defaults" else "keeping the settings from before"} until it's fixed."
                last ?: XLimitsConfig.defaults
            }
        }
        last = config
        yaml.forget()
        return Loaded(config, Translations.of(XLimitsMessages.catalog, emptyMap()), problems)
    }

    /** The family's settings and `lang/`, every language the jar ships written in full, the owner's edits kept. Logs every problem. */
    public fun translations(config: Config, configProblems: List<String> = emptyList()): Loaded {
        val problems = ArrayList(configProblems)
        val familyLoad = family.load()
        familyLoad.error?.let { problems += it.message }
        familyLoad.problems.forEach { problems += it.describe("plugins/Xena/config.yml") }
        familyLoad.notices.forEach { log.info("plugins/Xena/config.yml: ${it.message}") }
        val look = family.look(config, XLimitsConfig.messages)
        val lang = LangFolder(folder, XLimitsMessages.catalog, shipped).load(look.languages, look.theme)
        lang.notices.forEach { log.info(it.message) }
        lang.problems.forEach { problems += it.message }
        problems.forEach(log::warn)
        return Loaded(config, lang.translations, problems)
    }

    public companion object {
        /** The languages the jar ships besides English: drafts until native speakers have read them. */
        @JvmField
        public val LANGUAGES: List<LanguageTag> = listOf("de", "es", "fr", "pt-BR", "ru", "pl", "zh-CN").map { LanguageTag.of(it) }
    }
}
