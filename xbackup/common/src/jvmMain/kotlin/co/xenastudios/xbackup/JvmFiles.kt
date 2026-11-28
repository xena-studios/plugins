package co.xenastudios.xbackup

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
import co.xenastudios.xbackup.config.Legacy
import co.xenastudios.xbackup.config.XBackupConfig
import java.nio.file.Files as NioFiles
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.concurrent.Volatile

/**
 * xBackup's files on a JVM server: `config.yml` in the plugin's [folder] (carried forward from
 * 1.x, the old file copied aside as `config.yml.bak-v1`), then the family's settings and `lang/`.
 * Before the config is read, a remote's password or client secret written in `config.yml` moves
 * to the owner-only `secrets.yml` (decisions/2026-11-18-xbackup.md), and coming from 1.x the
 * messages an owner edited go into `lang/en.yml` first ([Legacy.messages]).
 */
public class JvmFiles(
    private val folder: Path,
    private val family: Family,
    /**
     * The jar's translations, [LangFolder.shipped] of [LANGUAGES]: read the first time the lang
     * files are, which the plugin does off the server thread.
     */
    private val jar: () -> Map<LanguageTag, String>,
    private val log: Log,
) : Files {
    public constructor(folder: Path, family: Family, shipped: Map<LanguageTag, String>, log: Log) : this(folder, family, Given(shipped), log)

    private class Given(private val shipped: Map<LanguageTag, String>) : () -> Map<LanguageTag, String> {
        override fun invoke(): Map<LanguageTag, String> = shipped
    }

    @Volatile
    private var read: Map<LanguageTag, String>? = null

    private val shipped: Map<LanguageTag, String> get() = read ?: jar().also { read = it }

    private val path = folder.resolve("config.yml")
    private val yaml = RememberingYaml()
    private val file = ConfigFile(XBackupConfig, path, yaml)

    @Volatile
    private var last: Config? = null

    override fun load(): Loaded {
        val config = config()
        return translations(config.config, config.problems)
    }

    /** `config.yml` alone, carried forward if it's old: what the plugin reads as it enables. */
    public fun config(): Loaded {
        val problems = ArrayList<String>()
        moveSecrets(problems)
        carryMessages(problems)
        val config = when (val loaded = file.load()) {
            is Outcome.Ok -> {
                loaded.value.notices.forEach { log.info("config.yml: ${it.message}") }
                loaded.value.problems.forEach { problems += it.describe("config.yml") }
                loaded.value.config
            }
            is Outcome.Err -> {
                problems += "${loaded.error}; ${if (last == null) "using the defaults" else "keeping the settings from before"} until it's fixed."
                last ?: XBackupConfig.defaults
            }
        }
        last = config
        yaml.forget()
        return Loaded(config, Translations.of(XBackupMessages.catalog, emptyMap()), problems)
    }

    /** The family's settings and `lang/`, for [config]: every language the jar ships written in full, the owner's edits kept. Logs every problem. */
    public fun translations(config: Config, configProblems: List<String> = emptyList()): Loaded {
        val problems = ArrayList(configProblems)
        val familyLoad = family.load()
        familyLoad.error?.let { problems += it.message }
        familyLoad.problems.forEach { problems += it.describe("plugins/Xena/config.yml") }
        familyLoad.notices.forEach { log.info("plugins/Xena/config.yml: ${it.message}") }
        val look = family.look(config, XBackupConfig.messages)
        val lang = LangFolder(folder, XBackupMessages.catalog, shipped).load(look.languages, look.theme)
        lang.notices.forEach { log.info(it.message) }
        lang.problems.forEach { problems += it.message }
        problems.forEach(log::warn)
        return Loaded(config, lang.translations, problems)
    }

    /**
     * A remote's `password` or `client-secret` in `config.yml` (from a pre-release 1.x build, or
     * typed there by habit) goes to `secrets.yml`, then out of `config.yml`: stored safely before
     * config.yml loses it. Each load checks again.
     */
    private fun moveSecrets(problems: MutableList<String>) {
        val text = read() ?: return
        val root = (yaml.read(text) as? Outcome.Ok)?.value as? YamlNode.Mapping ?: return
        val remotes = root["remotes"] as? YamlNode.Mapping ?: return
        val found = LinkedHashMap<String, Map<String, String>>()
        for (entry in remotes.entries) {
            val section = entry.value as? YamlNode.Mapping ?: continue
            val secrets = Secrets.SECRET_KEYS.mapNotNull { key -> (section[key] as? YamlNode.Scalar)?.content?.let { key to it } }.toMap()
            if (secrets.isNotEmpty()) found[entry.key] = secrets
        }
        if (found.isEmpty()) return
        // The lines themselves come out, the rest of the file as the owner wrote it: rewriting it
        // in 2.0's layout here would fill in 2.0's defaults before a 1.x config is carried forward.
        val write = withoutSecrets(text, found)
        if (write == null) {
            problems += "Couldn't move the remotes' passwords from config.yml to secrets.yml: move them by hand (remotes.<name>.password goes to secrets.yml as <name>.password). config.yml is still backed up with them in it."
            return
        }
        try {
            val moved = Secrets.store(folder, found)
            val part = folder.resolve("config.yml.part")
            NioFiles.write(part, write.toByteArray(Charsets.UTF_8))
            NioFiles.move(part, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            if (moved.isNotEmpty()) {
                log.info(
                    "Moved the passwords of ${moved.joinToString(", ")} from config.yml to secrets.yml, which only the server's user can read and which is never backed up. Backups taken before hold config.yml with them in it, and so do the remotes' copies: change those passwords if anyone else could read the backups.",
                )
            }
        } catch (e: Exception) {
            problems += "Couldn't move the remotes' passwords from config.yml to secrets.yml: ${e.message} config.yml is still backed up with them in it."
        }
    }

    /**
     * [text] without the lines holding [found]'s secrets, or `null` if that can't be done safely:
     * the result must read back as the same file with those keys, and only those, gone.
     */
    internal fun withoutSecrets(text: String, found: Map<String, Map<String, String>>): String? {
        val out = ArrayList<String>()
        var inRemotes = false
        var remoteIndent = -1
        for (line in text.split('\n')) {
            val trimmed = line.trimStart()
            val indent = line.length - trimmed.length
            val content = trimmed.isNotEmpty() && !trimmed.startsWith("#")
            if (content && indent == 0) {
                inRemotes = trimmed.startsWith("remotes:")
                remoteIndent = -1
            } else if (content && inRemotes) {
                if (remoteIndent < 0) remoteIndent = indent
                if (indent > remoteIndent && Secrets.SECRET_KEYS.any { trimmed.startsWith("$it:") }) continue
            }
            out += line
        }
        val result = out.joinToString("\n")
        val before = (yaml.read(text) as? Outcome.Ok)?.value as? YamlNode.Mapping ?: return null
        val after = (yaml.read(result) as? Outcome.Ok)?.value as? YamlNode.Mapping ?: return null
        val wanted = YamlNode.Mapping(
            before.entries.map { entry ->
                if (entry.key != "remotes") return@map entry
                val remotes = entry.value as? YamlNode.Mapping ?: return@map entry
                YamlNode.Mapping.Entry(
                    entry.key,
                    YamlNode.Mapping(
                        remotes.entries.map { remote ->
                            val section = remote.value as? YamlNode.Mapping
                            val gone = found[remote.key]?.keys.orEmpty()
                            if (section == null || gone.isEmpty()) remote else YamlNode.Mapping.Entry(remote.key, YamlNode.Mapping(section.entries.filter { it.key !in gone }))
                        },
                    ),
                )
            },
        )
        return if (after.plain() == wanted.plain()) result else null
    }

    // The node as plain values, without its lines, to compare two readings of a file.
    private fun YamlNode.plain(): Any? = when (this) {
        is YamlNode.Scalar -> content
        is YamlNode.Sequence -> items.map { it.plain() }
        is YamlNode.Mapping -> entries.map { it.key to it.value.plain() }
        is YamlNode.Null -> null
    }

    // A 1.x config's edited messages, into lang/en.yml before anything else writes it.
    private fun carryMessages(problems: MutableList<String>) {
        val text = read() ?: return
        val root = (yaml.read(text) as? Outcome.Ok)?.value as? YamlNode.Mapping ?: return
        val version = (root["config-version"] as? YamlNode.Scalar)?.content?.trim()?.toIntOrNull() ?: return
        if (version >= XBackupConfig.version) return
        val edited = Legacy.messages(root)
        if (edited.isEmpty()) return
        CarriedMessages.write(folder, edited, "xBackup 1.x", "config.yml.bak-v$version", log)?.let { problems += it }
    }

    private fun read(): String? = try {
        String(NioFiles.readAllBytes(path), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }

    public companion object {
        /** The languages the jar ships besides English: drafts until native speakers have read them. */
        @JvmField
        public val LANGUAGES: List<LanguageTag> = listOf("de", "es", "fr", "pt-BR", "ru", "pl", "zh-CN").map { LanguageTag.of(it) }
    }
}
