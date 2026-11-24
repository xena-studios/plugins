package co.xenastudios.core.common.family

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.config.Config
import co.xenastudios.core.common.config.ConfigContext
import co.xenastudios.core.common.config.ConfigGroup
import co.xenastudios.core.common.config.ConfigKey
import co.xenastudios.core.common.config.ConfigSchema
import co.xenastudios.core.common.config.ConfigSection
import co.xenastudios.core.common.config.ConfigType
import co.xenastudios.core.common.config.YamlNode
import co.xenastudios.core.common.db.DatabaseSettings
import co.xenastudios.core.common.db.NetworkSettings
import co.xenastudios.core.common.db.Sql
import co.xenastudios.core.common.i18n.LanguageChoice
import co.xenastudios.core.common.i18n.LanguageSettings
import co.xenastudios.core.common.i18n.LanguageTag
import co.xenastudios.core.common.text.Style
import co.xenastudios.core.common.text.Theme
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * A setting a plugin can leave to the family: [Shared] takes the value in
 * `plugins/Xena/config.yml`, which every Xena plugin reads; [Own] is the plugin's own. Written
 * `shared`, or the value.
 */
public sealed class Shareable<out T : Any> {
    /** The family's value. */
    public object Shared : Shareable<Nothing>() {
        override fun toString(): String = WORD
    }

    /** The plugin's own [value]. */
    public data class Own<out T : Any>(public val value: T) : Shareable<T>()

    public companion object {
        /** How [Shared] is written. */
        public const val WORD: String = "shared"

        /** A setting holding `shared` or a value of [type]. */
        public fun <T : Any> type(type: ConfigType<T>): ConfigType<Shareable<T>> = ShareableType(type)
    }

    private class ShareableType<T : Any>(private val inner: ConfigType<T>) : ConfigType<Shareable<T>>() {
        override val name: String get() = inner.name
        override val plain: Boolean get() = inner.plain
        override val choices: List<String> get() = if (inner.choices.isEmpty()) emptyList() else listOf(WORD) + inner.choices
        override fun empty(): Shareable<T>? = inner.empty()?.let { Own(it) }
        override fun decode(node: YamlNode, context: ConfigContext): Outcome<Shareable<T>, String> {
            if (node is YamlNode.Scalar && node.content.trim().equals(WORD, ignoreCase = true)) return Outcome.Ok(Shared)
            return when (val read = inner.decode(node, context)) {
                is Outcome.Ok -> Outcome.Ok(Own(read.value))
                is Outcome.Err -> Outcome.Err("${read.error}, or $WORD")
            }
        }
        override fun encode(value: Shareable<T>): YamlNode = when (value) {
            Shared -> YamlNode.Scalar(WORD)
            is Own -> inner.encode(value.value)
        }
    }
}

/** [this] plugin's own value, or [family]'s when it's [Shareable.Shared]. */
public fun <T : Any> Shareable<T>.or(family: T): T = when (this) {
    Shareable.Shared -> family
    is Shareable.Own -> value
}

/** How messages look, and which language they're in: the theme and the two language settings. */
public class Look(public val theme: Theme, public val languages: LanguageSettings) {
    public companion object {
        /** Today's theme, `language: auto`, `default-language: en`. */
        public val DEFAULT: Look = Look(Theme.DEFAULT, LanguageSettings.DEFAULT)
    }
}

/**
 * The `messages` section: the language players read in and the theme, the same in
 * `plugins/Xena/config.yml` (where they're set for the family, [family]) and in each plugin's
 * own config (where each is `shared` unless the owner gives the plugin its own).
 *
 * ```kotlin
 * object HomesConfig : ConfigSchema(1) {
 *     val messages: MessagesSection = MessagesSection(this)
 * }
 * val look = HomesConfig.messages.look(config, family.look)
 * ```
 */
public class MessagesSection(parent: ConfigGroup, private val family: Boolean = false) : ConfigSection(
    parent, "messages",
    *if (family) {
        arrayOf(
            "How the Xena plugins' messages look, and the language they're in. Every Xena plugin",
            "uses these, unless its own config.yml gives it its own (anything there but shared).",
        )
    } else {
        arrayOf(
            "How this plugin's messages look, and the language they're in. shared uses the",
            "setting in plugins/Xena/config.yml, which every Xena plugin reads; anything else is",
            "this plugin's own.",
        )
    },
) {
    public val language: ConfigKey<Shareable<LanguageChoice>> =
        setting("language", LanguageChoice.TYPE, LanguageChoice.Auto, LanguageSettings.LANGUAGE_COMMENT)
    public val defaultLanguage: ConfigKey<Shareable<LanguageTag>> =
        setting("default-language", LanguageTag.TYPE, LanguageTag.ENGLISH, LanguageSettings.DEFAULT_LANGUAGE_COMMENT)
    public val theme: ThemeSection = ThemeSection(this, family)

    /**
     * The look this section sets, with [family]'s for what it leaves `shared`. For the family's
     * own section, leave [family] out: nothing there is shared.
     */
    public fun look(config: Config, family: Look = Look.DEFAULT): Look = Look(
        theme.theme(config, family.theme),
        LanguageSettings(config[language].or(family.languages.choice), config[defaultLanguage].or(family.languages.default)),
    )

    private fun <T : Any> setting(name: String, type: ConfigType<T>, default: T, comment: List<String>): ConfigKey<Shareable<T>> =
        key(
            name, Shareable.type(type), if (family) Shareable.Own(default) else Shareable.Shared, *comment.toTypedArray(),
            check = { if (family && it == Shareable.Shared) "can't be shared here: this is the setting the plugins share" else null },
        )
}

/** The theme's settings, inside a [MessagesSection]: see [Theme]. */
public class ThemeSection(parent: ConfigGroup, family: Boolean) : ConfigSection(
    parent, "theme",
    "The look every message shares, used in messages as tags: <prefix>, <highlight>,",
    "<success> and <error>. Colours are names (gray, gold) or hex (#B48CFF), with bold,",
    "italic, underlined, strikethrough or obfuscated if you like: <#B48CFF><bold>.",
) {
    public val prefix: ConfigKey<Shareable<String>> = shared(
        "prefix", ConfigType.STRING, Theme.DEFAULT_PREFIX, family,
        "<prefix> starts the default messages. Make it your server's name, such as",
        "\"<dark_gray>[<gradient:#B48CFF:#5EE08A>MyServer</gradient>]</dark_gray> \", or blank for none.",
    )
    public val base: ConfigKey<Shareable<Style>> =
        shared("base", STYLE, Theme.DEFAULT_BASE, family, "The colour of message text that has no tag of its own.")
    public val highlight: ConfigKey<Shareable<Style>> =
        shared("highlight", STYLE, Theme.DEFAULT_HIGHLIGHT, family, "<highlight>: names and values, such as a player or a speed.")
    public val success: ConfigKey<Shareable<Style>> =
        shared("success", STYLE, Theme.DEFAULT_SUCCESS, family, "<success>: something turned on or done.")
    public val error: ConfigKey<Shareable<Style>> =
        shared("error", STYLE, Theme.DEFAULT_ERROR, family, "<error>: something refused or turned off.")

    /** The theme these settings make, with [family]'s for what they leave `shared`. */
    public fun theme(config: Config, family: Theme): Theme = Theme(
        config[prefix].or(family.prefix),
        config[base].or(family.base),
        config[highlight].or(family.highlight),
        config[success].or(family.success),
        config[error].or(family.error),
    )

    private fun <T : Any> shared(name: String, type: ConfigType<T>, default: T, family: Boolean, vararg comment: String): ConfigKey<Shareable<T>> =
        key(
            name, Shareable.type(type), if (family) Shareable.Own(default) else Shareable.Shared, *comment,
            check = { if (family && it == Shareable.Shared) "can't be shared here: this is the setting the plugins share" else null },
        )

    public companion object {
        /** A style written as tags, as the theme's settings are: see [Theme.style]. */
        public val STYLE: ConfigType<Style> = ConfigType.scalar("style", ::tags) { Theme.style(it) }

        /** [style] as the tags that make it, `<#b48cff><bold>`: what [Theme.style] reads back. */
        public fun tags(style: Style): String = buildString {
            style.color?.let { append('<').append(it).append('>') }
            for ((name, on) in listOf(
                "bold" to style.bold, "italic" to style.italic, "underlined" to style.underlined,
                "strikethrough" to style.strikethrough, "obfuscated" to style.obfuscated,
            )) {
                if (on == true) append('<').append(name).append('>')
            }
        }
    }
}

/** Which database a plugin keeps its data in: see [StorageSection]. */
public enum class StorageType {
    /** The family's database, set in `plugins/Xena/config.yml`. */
    SHARED,

    /** A SQLite file: the plugin's own, or for the family's, `plugins/Xena/xena.db`. */
    SQLITE,

    /** MySQL or MariaDB. */
    MYSQL,

    /** None: in the family's config, no shared database. */
    NONE,
}

/** The MySQL connection, inside a [StorageSection] or the family's [DatabaseSection]. */
public class MySqlSection(parent: ConfigGroup) : ConfigSection(parent, "mysql", "Only for type: mysql.") {
    public val host: ConfigKey<String> = string("host", "localhost")
    public val port: ConfigKey<Int> = int("port", 3306, min = 1, max = 65535)
    public val database: ConfigKey<String> = string("database", "minecraft")
    public val username: ConfigKey<String> = string("username", "minecraft")
    public val password: ConfigKey<String> = string("password", "")
    public val properties: ConfigKey<String> =
        string("properties", "", "Extra connection settings, as in a JDBC URL: useSSL=true&serverTimezone=UTC.")

    /** The connection these settings name. */
    public fun read(config: Config): DatabaseSettings.MySql = DatabaseSettings.MySql(
        config[host], config[port], config[database], config[username], config[password], config[properties],
    )
}

/** The connections a database keeps for reading, beside its one writer. */
public class PoolSection(parent: ConfigGroup) : ConfigSection(parent, "pool", "The connections used to read, beside the one that writes.") {
    public val readers: ConfigKey<Int> = int(
        "readers", 0,
        "How many: 0 picks for the database (1 for SQLite, 2 for MySQL). More helps only a",
        "MySQL server that many reads wait on.",
        min = 0, max = 16,
    )

    /** The number of readers, or `null` to let the database pick. */
    public fun read(config: Config): Int? = config[readers].takeIf { it > 0 }
}

/**
 * The `storage` section of a plugin that keeps data in a database: which database, its table
 * prefix, the MySQL connection and the pool. `type: shared` keeps the plugin's tables in the
 * family's database (`database:` in `plugins/Xena/config.yml`), under its own prefix.
 *
 * Read once, at startup: where a plugin keeps its data doesn't change on reload
 * (CONTRIBUTING.md, Every setting applies on reload).
 */
public class StorageSection(parent: ConfigGroup, prefix: String) : ConfigSection(
    parent, "storage",
    "Where this plugin keeps its data. Read at startup only: restart after changing it.",
) {
    init {
        require(Sql.validPrefix(prefix)) { "\"$prefix\" isn't a table prefix: lower-case letters, digits and _" }
    }

    public val type: ConfigKey<StorageType> = key(
        "type", ConfigType.enumOf(listOf(StorageType.SQLITE, StorageType.MYSQL, StorageType.SHARED)), StorageType.SQLITE,
        "sqlite keeps everything in one file in the plugin's folder, with nothing to set up.",
        "mysql (or MariaDB) is for networks, or servers with a lot of history.",
        "shared keeps it in the database every Xena plugin can share, set in plugins/Xena/config.yml.",
    )
    public val tablePrefix: ConfigKey<String> = key(
        "table-prefix", ConfigType.STRING, prefix,
        "Put in front of every table's name, so plugins and servers can share one database.",
        check = { if (Sql.validPrefix(it)) null else "should be lower-case letters, digits and _, at most 32" },
    )
    public val mysql: MySqlSection = MySqlSection(this)
    public val pool: PoolSection = PoolSection(this)

    /**
     * Where the plugin's data goes, given [family]'s database settings: its own, or the
     * family's for `type: shared`. A `shared` plugin in a family without a database falls back
     * to its own SQLite file, and says why in [StoragePlan.problem].
     */
    public fun plan(config: Config, family: FamilyDatabase): StoragePlan {
        val prefix = config[tablePrefix]
        val readers = pool.read(config)
        return when (config[type]) {
            StorageType.MYSQL -> StoragePlan(DatabaseSettings(DatabaseSettings.Type.MYSQL, prefix, mysql.read(config), readers), inFamilyFolder = false)
            StorageType.SHARED -> if (family.enabled) {
                StoragePlan(
                    DatabaseSettings(family.type, prefix, family.mysql, family.readers),
                    inFamilyFolder = family.type == DatabaseSettings.Type.SQLITE,
                )
            } else {
                StoragePlan(
                    DatabaseSettings(DatabaseSettings.Type.SQLITE, prefix, readers = readers), inFamilyFolder = false,
                    problem = "storage.type is shared, but plugins/Xena/config.yml has no database (database.type: none); " +
                        "using this plugin's own SQLite file instead.",
                )
            }
            else -> StoragePlan(DatabaseSettings(DatabaseSettings.Type.SQLITE, prefix, readers = readers), inFamilyFolder = false)
        }
    }
}

/**
 * Where a plugin's data goes, from its [StorageSection]: the [database], and for SQLite, whether
 * the file is the family's (`plugins/Xena/xena.db`) rather than the plugin's own. [problem] says
 * why it isn't what the config asked for, for the console.
 */
public data class StoragePlan(
    public val database: DatabaseSettings,
    public val inFamilyFolder: Boolean,
    public val problem: String? = null,
)

/** The family's shared database, from [DatabaseSection]: [enabled] unless its type is `none`. */
public data class FamilyDatabase(
    public val enabled: Boolean,
    public val type: DatabaseSettings.Type,
    public val mysql: DatabaseSettings.MySql,
    public val readers: Int?,
) {
    public companion object {
        /** No shared database. */
        public val NONE: FamilyDatabase = FamilyDatabase(false, DatabaseSettings.Type.SQLITE, DatabaseSettings.MySql(), null)
    }
}

/** The `database` section of `plugins/Xena/config.yml`: the database the family can share. */
public class DatabaseSection(parent: ConfigGroup) : ConfigSection(
    parent, "database",
    "One database the Xena plugins can share. A plugin keeps its tables here when its own",
    "storage.type is shared (each under its own prefix), and the family's player cache is",
    "kept here instead of in players.records. Read at startup only: restart after changing it.",
) {
    public val type: ConfigKey<StorageType> = key(
        "type", ConfigType.enumOf(listOf(StorageType.NONE, StorageType.SQLITE, StorageType.MYSQL)), StorageType.NONE,
        "none for no shared database; sqlite for one file, xena.db in this folder; mysql for",
        "MySQL or MariaDB, which a network's servers can share.",
    )
    public val mysql: MySqlSection = MySqlSection(this)
    public val pool: PoolSection = PoolSection(this)

    /** The shared database these settings describe. */
    public fun read(config: Config): FamilyDatabase = when (config[type]) {
        StorageType.SQLITE -> FamilyDatabase(true, DatabaseSettings.Type.SQLITE, mysql.read(config), pool.read(config))
        StorageType.MYSQL -> FamilyDatabase(true, DatabaseSettings.Type.MYSQL, mysql.read(config), pool.read(config))
        else -> FamilyDatabase.NONE
    }
}

/**
 * The `network` section of a plugin with network features: whether servers sharing its MySQL
 * database check the change feed for each other's changes, and how often.
 */
public class NetworkSection(parent: ConfigGroup) : ConfigSection(
    parent, "network",
    "For a network: several servers sharing this plugin's MySQL or MariaDB database.",
    "Read at startup only: restart after changing it.",
) {
    public val enabled: ConfigKey<Boolean> = boolean(
        "enabled", false,
        "On so a change made on one server (a ban, a payment) reaches the others within a few",
        "seconds. Leave it off for a single server: it does nothing on SQLite.",
    )
    public val checkEvery: ConfigKey<Duration> =
        duration("check-every", 3.seconds, "How often to check for the other servers' changes.", min = 1.seconds, max = 1.minutes)

    /** The feed's settings. */
    public fun read(config: Config): NetworkSettings = NetworkSettings(config[enabled], config[checkEvery])
}

/**
 * `plugins/Xena/config.yml`: what the family's plugins share. The first plugin to start writes it
 * ([Family.load]); each reads it again on its reload.
 */
public object FamilyConfig : ConfigSchema(
    1,
    "What every Xena plugin on this server shares. Each plugin's own config.yml can give it its",
    "own messages settings instead.",
) {
    public val messages: MessagesSection = MessagesSection(this, family = true)
    public val database: DatabaseSection = DatabaseSection(this)
    public val players: Players = Players(this)

    /** The family's player cache. */
    public class Players(parent: ConfigGroup) : ConfigSection(parent, "players", "The player cache every Xena plugin shares: names, last seen, language.") {
        public val cacheSize: ConfigKey<Int> = int(
            "cache-size", PlayerCache.DEFAULT_CAPACITY,
            "How many players are kept in memory. With a shared database the rest are read from it",
            "when needed; without one, only these are kept.",
            min = 100, max = 1_000_000,
        )
    }
}
