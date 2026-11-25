package co.xenastudios.zchat

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.config.Config
import co.xenastudios.core.common.config.YamlNode
import co.xenastudios.core.common.family.Family
import co.xenastudios.core.common.i18n.LanguageTag
import co.xenastudios.core.common.i18n.Translations
import co.xenastudios.core.common.platform.Log
import co.xenastudios.core.jvm.config.CarriedMessages
import co.xenastudios.core.jvm.config.ConfigFile
import co.xenastudios.core.jvm.config.JvmYaml
import co.xenastudios.core.jvm.i18n.LangFolder
import co.xenastudios.zchat.config.Group
import co.xenastudios.zchat.config.Legacy
import co.xenastudios.zchat.config.ZChatConfig
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.Files as NioFiles

/**
 * zChat's files on a JVM server: the family's `config.yml`, then `config.yml` in the plugin's
 * [folder] (carried forward from 2.x and 3.x, the old file copied aside as `config.yml.bak-v2`),
 * then `lang/`. Off the server's threads at startup; on the global thread for a reload.
 *
 * Coming from 3.x, the messages an owner edited in `config.yml` go into `lang/en.yml` first
 * ([Legacy.messages]), as the file's starting point: the language files are then written in
 * full around them. A zChat 1.x file, from a different plugin, is set aside as `config.yml.old`.
 */
public class JvmFiles(
    private val folder: Path,
    private val family: Family,
    /** The jar's translations: [LangFolder.shipped] of [LANGUAGES]. */
    private val shipped: Map<LanguageTag, String>,
    private val log: Log,
) : Files {
    private val path = folder.resolve("config.yml")
    private val file = ConfigFile(ZChatConfig, path)

    @Volatile
    private var last: Config? = null

    override fun load(): Loaded {
        val config = config()
        return translations(config.config, config.problems)
    }

    /**
     * `config.yml` alone, carried forward if it's old: what a server's startup reads as the plugin
     * enables, so the commands are registered with the server's own. [translations] follows, off
     * the server thread. Logs nothing: [translations] logs the problems.
     */
    public fun config(): Loaded {
        val problems = ArrayList<String>()
        setAsideZChatOne(problems)
        carryMessages(problems)
        val config = when (val loaded = file.load()) {
            is Outcome.Ok -> {
                loaded.value.notices.forEach { log.info("config.yml: ${it.message}") }
                loaded.value.problems.forEach { problems += it.describe("config.yml") }
                loaded.value.config
            }
            is Outcome.Err -> {
                problems += "${loaded.error}; ${if (last == null) "using the defaults" else "keeping the settings from before"} until it's fixed."
                last ?: ZChatConfig.defaults
            }
        }
        last = config
        problems += Group.problems((read() as? YamlNode.Mapping)?.let { (it["formatting"] as? YamlNode.Mapping)?.get("groups") })
        return Loaded(config, Translations.of(ZChatMessages.catalog, emptyMap()), problems)
    }

    /**
     * The family's settings and `lang/`, for [config] (its look and language): every language the
     * jar ships written in full, the owner's edits kept. Logs every problem, [configProblems] among them.
     */
    public fun translations(config: Config, configProblems: List<String> = emptyList()): Loaded {
        val problems = ArrayList(configProblems)
        val familyLoad = family.load()
        familyLoad.error?.let { problems += it.message }
        familyLoad.problems.forEach { problems += it.describe("plugins/Xena/config.yml") }
        familyLoad.notices.forEach { log.info("plugins/Xena/config.yml: ${it.message}") }
        val look = family.look(config, ZChatConfig.messages)
        val lang = LangFolder(folder, ZChatMessages.catalog, shipped).load(look.languages, look.theme)
        lang.notices.forEach { log.info(it.message) }
        lang.problems.forEach { problems += it.message }
        problems.forEach(log::warn)
        return Loaded(config, lang.translations, problems)
    }

    override fun editWords(word: String, add: Boolean): Outcome<Boolean, String> {
        val key = ZChatConfig.filter.words
        // Decided against the file itself, not the loaded config, so a word someone added by hand
        // (without reloading) is seen too.
        val edited = file.edit {
            val words = this[key] ?: key.default
            val present = words.any { it.trim().equals(word, ignoreCase = true) }
            if (add != present) this[key] = if (add) words + word else words.filterNot { it.trim().equals(word, ignoreCase = true) }
        }
        return when (edited) {
            is Outcome.Ok -> Outcome.Ok(edited.value)
            is Outcome.Err -> Outcome.Err(edited.error.toString())
        }
    }

    // zChat 1.x was a different plugin: its file is moved aside and the defaults written, as 3.x did.
    private fun setAsideZChatOne(problems: MutableList<String>) {
        val root = read() as? YamlNode.Mapping ?: return
        if (!Legacy.isZChatOne(root)) return
        try {
            NioFiles.move(path, folder.resolve("config.yml.old"), StandardCopyOption.REPLACE_EXISTING)
            log.info("config.yml is from zChat 1.x, which zChat 4 can't carry forward. It's kept as config.yml.old; a new config.yml has the defaults.")
        } catch (e: Exception) {
            problems += "config.yml is from zChat 1.x and couldn't be moved aside (${e.message}); it's read as it is."
        }
    }

    // A 2.x or 3.x config's edited messages, into lang/en.yml before anything else writes it.
    private fun carryMessages(problems: MutableList<String>) {
        val root = read() as? YamlNode.Mapping ?: return
        val version = Legacy.version(root) ?: return
        if (version >= ZChatConfig.version) return
        val edited = Legacy.messages(root)
        if (edited.isEmpty()) return
        val release = if (version == 1) "2.x" else "3.x"
        CarriedMessages.write(folder, edited, "zChat $release", "config.yml.bak-v$version", log)?.let { problems += it }
    }

    private fun read(): YamlNode? {
        val text = try {
            String(NioFiles.readAllBytes(path), StandardCharsets.UTF_8)
        } catch (e: Exception) {
            return null
        }
        return (JvmYaml.read(text) as? Outcome.Ok)?.value
    }

    public companion object {
        /** The languages the jar ships besides English: drafts until native speakers have read them. */
        @JvmField
        public val LANGUAGES: List<LanguageTag> = listOf("de", "es", "fr", "pt-BR", "ru", "pl", "zh-CN").map { LanguageTag.of(it) }
    }
}
