package co.xenastudios.xstats

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.config.Config
import co.xenastudios.core.common.config.YamlNode
import co.xenastudios.core.common.db.Database
import co.xenastudios.core.common.db.DbError
import co.xenastudios.core.common.family.Family
import co.xenastudios.core.common.i18n.LanguageTag
import co.xenastudios.core.common.i18n.Translations
import co.xenastudios.core.common.platform.Log
import co.xenastudios.core.jvm.config.CarriedMessages
import co.xenastudios.core.jvm.config.ConfigFile
import co.xenastudios.core.jvm.config.RememberingYaml
import co.xenastudios.core.jvm.db.Drivers
import co.xenastudios.core.jvm.db.JdbcDatabase
import co.xenastudios.core.jvm.i18n.LangFolder
import co.xenastudios.xstats.config.Legacy
import co.xenastudios.xstats.config.XStatsConfig
import co.xenastudios.xstats.data.Zone
import co.xenastudios.xstats.data.Zones
import co.xenastudios.xstats.store.Store
import java.nio.charset.StandardCharsets
import java.nio.file.Files as NioFiles
import java.nio.file.Path
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId

/**
 * xStats' files on a JVM server or proxy: the family's `config.yml`, then `config.yml` in the
 * plugin's [folder] (carried forward from 1.x, the old file copied aside as `config.yml.bak-v1`),
 * then `lang/`. Coming from 1.x, the messages an owner edited in `config.yml` go into `lang/en.yml`
 * first ([Legacy.messages]), as the file's starting point.
 */
public open class JvmFiles(
    private val folder: Path,
    private val family: Family,
    /** Where the jar's translations are: [shipped] reads them through it. */
    private val jar: ClassLoader?,
    private val log: Log,
) : Files {
    private val path = folder.resolve("config.yml")
    private val yaml = RememberingYaml()
    private val file = ConfigFile(XStatsConfig, path, yaml)

    @Volatile
    private var last: Config? = null

    override fun load(): Loaded {
        val config = config()
        return translations(config.config, config.problems)
    }

    /** `config.yml` alone, carried forward if it's old: what a server's startup reads as the plugin enables. */
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
                last ?: XStatsConfig.defaults
            }
        }
        last = config
        yaml.forget()
        releaseYamlBuffers()
        return Loaded(config, Translations.of(XStatsMessages.catalog, emptyMap()), problems)
    }

    /** The family's settings and `lang/`, for [config]: every language the jar ships written in full, the owner's edits kept. Logs every problem. */
    public fun translations(config: Config, configProblems: List<String> = emptyList()): Loaded {
        val problems = ArrayList(configProblems)
        val familyLoad = family.load()
        familyLoad.error?.let { problems += it.message }
        familyLoad.problems.forEach { problems += it.describe("plugins/Xena/config.yml") }
        familyLoad.notices.forEach { log.info("plugins/Xena/config.yml: ${it.message}") }
        val look = family.look(config, XStatsConfig.messages)
        val lang = LangFolder(folder, XStatsMessages.catalog, shipped()).load(look.languages, look.theme)
        lang.notices.forEach { log.info(it.message) }
        lang.problems.forEach { problems += it.message }
        problems.forEach(log::warn)
        releaseYamlBuffers()
        return Loaded(config, lang.translations, problems)
    }

    /**
     * The jar's translations ([LangFolder.shipped] of [LANGUAGES]), read again at each load rather
     * than kept: an idle server has no use for every language's file as text (~180 KB).
     */
    protected open fun shipped(): Map<LanguageTag, String> = jar?.let { LangFolder.shipped(it, LANGUAGES) }.orEmpty()

    // A 1.x config's edited messages, into lang/en.yml before anything else writes it.
    private fun carryMessages(problems: MutableList<String>) {
        val root = read() as? YamlNode.Mapping ?: return
        // Read here, so a current config never loads Legacy's tables.
        val version = (root["config-version"] as? YamlNode.Scalar)?.content?.trim()?.toIntOrNull() ?: return
        if (version >= XStatsConfig.version) return
        val edited = Legacy.messages(root)
        if (edited.isEmpty()) return
        CarriedMessages.write(folder, edited, "xStats 1.x", "config.yml.bak-v$version", log)?.let { problems += it }
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
        /**
         * Hands back to the GC the buffers okio (the YAML library's I/O) pools after a read: up to
         * 64 KiB of spent 8 KiB segments per pool bucket, the one of this thread, kept for the next
         * read. xStats reads YAML only as it loads and on /xstats reload, so an idle server would
         * hold them for nothing. Nothing happens where okio isn't there or has changed.
         */
        internal fun releaseYamlBuffers() {
            try {
                val pool = Class.forName("okio.SegmentPool", false, JvmFiles::class.java.classLoader)
                val instance = pool.getField("INSTANCE").get(null)
                val pooled = pool.getMethod("getByteCount")
                val take = pool.getMethod("take")
                // At most 8 a bucket (64 KiB of 8 KiB segments); 0 also while another thread holds it.
                var left = 16
                while (left-- > 0 && (pooled.invoke(instance) as Int) > 0) take.invoke(null)
            } catch (e: Throwable) {
                // Another okio, or none: its pool stays as it is.
            }
        }

        /** The languages the jar ships besides English: drafts until native speakers have read them. */
        @JvmField
        public val LANGUAGES: List<LanguageTag> = listOf("de", "es", "fr", "pt-BR", "ru", "pl", "zh-CN").map { LanguageTag.of(it) }
    }
}

/**
 * Opens xStats' database where `storage` says, on the JVM: SQLite in the plugin's folder (`stats.db`,
 * 1.x's file) or the family's, or MySQL. Off the server's threads: it connects.
 */
public class JvmDatabase(
    private val family: Family,
    private val folder: Path,
    /** The family's, `plugins/Xena/` (`config/xena/` on Fabric). */
    private val familyFolder: Path,
    private val drivers: Drivers,
    private val log: Log,
) : Opener {
    override fun open(config: Config): Outcome<Database, String> {
        val plan = family.storage(config, XStatsConfig.storage)
        val file = if (plan.inFamilyFolder) familyFolder.resolve(Family.DATABASE_FILE.toString()) else folder.resolve(co.xenastudios.xstats.store.Q.FILE)
        plan.problem?.let(log::warn)
        val settings = plan.database
        return when (val opened: Outcome<Database, DbError> = JdbcDatabase.open(settings, file, drivers, log, XStatsPlugin.NAME)) {
            is Outcome.Ok -> Outcome.Ok(opened.value)
            is Outcome.Err -> Outcome.Err(
                "Couldn't open ${if (settings.type == co.xenastudios.core.common.db.DatabaseSettings.Type.MYSQL) "MySQL" else "the SQLite database ${file.fileName}"}: ${opened.error.detail}.",
            )
        }
    }
}

/** Time zones from `java.time`: the server's is the JVM's own. */
public object JvmZones : Zones {
    private class Of(private val zone: ZoneId) : Zone {
        override val id: String get() = zone.id
        override fun offsetMillis(epochMillis: Long): Long = zone.rules.getOffset(Instant.ofEpochMilli(epochMillis)).totalSeconds * 1000L
    }

    override val server: Zone get() = Of(ZoneId.systemDefault())

    override fun of(id: String): Zone? = try {
        Of(ZoneId.of(id))
    } catch (e: DateTimeException) {
        null
    }
}
