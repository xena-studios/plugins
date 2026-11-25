package co.xenastudios.core.common.family

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.config.Config
import co.xenastudios.core.common.config.ConfigError
import co.xenastudios.core.common.config.ConfigNotice
import co.xenastudios.core.common.config.ConfigProblem
import co.xenastudios.core.common.config.Configs
import co.xenastudios.core.common.config.YamlReader
import co.xenastudios.core.common.platform.Storage
import co.xenastudios.core.common.platform.StoragePath
import co.xenastudios.core.common.platform.readText
import co.xenastudios.core.common.platform.writeText
import kotlin.concurrent.Volatile

/**
 * The family as one plugin sees it: `plugins/Xena/` (the platform's shared folder, beside each
 * plugin's own), what's set there for every Xena plugin ([FamilyConfig]), and the [board] the
 * plugins on this server meet on.
 *
 * The adapter makes one at startup, with the folder and board it found, and the plugin calls
 * [load] then and on every reload, off the server's threads: so a change to the family's file
 * reaches every plugin as it reloads. Its own config's `messages` and `storage` sections are read
 * over the family's with [look] and [storage].
 */
public class Family(
    /** `plugins/Xena/` on Bukkit, `config/xena/` on the mod loaders. */
    public val folder: Storage,
    /** Where the plugins on this server share the player cache. */
    public val board: FamilyBoard,
    private val reader: YamlReader,
) {
    // Null until the first load: the defaults are read only when asked for, so making the
    // family (as a plugin enables, on the server's thread) doesn't build the family's schema.
    @Volatile
    private var loaded: Config? = null

    /** The family's settings as last loaded; the defaults until then. */
    public val config: Config get() = loaded ?: FamilyConfig.defaults

    /** The look the family sets: the theme and languages of every plugin that leaves them `shared`. */
    public val look: Look get() = FamilyConfig.messages.look(config)

    /** The family's shared database, if it has one. */
    public val database: FamilyDatabase get() = FamilyConfig.database.read(config)

    /** How many players the shared cache keeps in memory. */
    public val cacheSize: Int get() = config[FamilyConfig.players.cacheSize]

    /**
     * Reads `config.yml` from the family's folder. The first plugin to start finds none and
     * writes the defaults; a file from an older version is copied aside
     * (`config.yml.bak-v1`) and carried forward, as a plugin's own config is. A file that can't
     * be read keeps the last good settings, and says why ([FamilyLoad.error]). Call it off the
     * server's threads, as [Storage] is.
     */
    public fun load(): FamilyLoad {
        val text = when (val read = folder.readText(FILE)) {
            is Outcome.Ok -> read.value
            is Outcome.Err -> return failed("Couldn't read plugins/Xena/$FILE (${read.error.detail}).")
        }
        val load = when (val loaded = Configs.load(FamilyConfig, text, reader)) {
            is Outcome.Ok -> loaded.value
            is Outcome.Err -> return failed("plugins/Xena/$FILE: ${loaded.error}")
        }
        val problems = load.problems.toMutableList()
        val notices = load.notices.toMutableList()
        val write = load.write
        if (write != null) {
            var saved = true
            if (load.backup && text != null) {
                val backup = StoragePath.of("$FILE.bak-v${load.carriedFrom}")
                if (folder.writeText(backup, text) is Outcome.Err) {
                    problems += ConfigProblem(
                        ConfigProblem.Kind.BACKUP_FAILED, "", 0, null,
                        "Couldn't copy plugins/Xena/$FILE aside, so it's carried forward for this run only and left unchanged on disk.",
                    )
                    saved = false
                } else {
                    notices += ConfigNotice(ConfigNotice.Kind.BACKED_UP, "The old plugins/Xena/$FILE is kept as $backup.")
                }
            }
            if (saved) {
                val written = folder.writeText(FILE, write)
                if (written is Outcome.Err) {
                    problems += ConfigProblem(
                        ConfigProblem.Kind.SAVE_FAILED, "", 0, null,
                        "Couldn't save plugins/Xena/$FILE (${written.error.detail}), so it's unchanged on disk; these settings are in use anyway.",
                    )
                }
            }
        }
        loaded = load.config
        return FamilyLoad(load.config, problems, notices, null)
    }

    /** [own]'s look: its `messages` [section], over the family's for what it leaves `shared`. */
    public fun look(own: Config, section: MessagesSection): Look = section.look(own, look)

    /** Where [own]'s data goes: its `storage` [section], with the family's database for `shared`. */
    public fun storage(own: Config, section: StorageSection): StoragePlan = section.plan(own, database)

    private fun failed(message: String) = FamilyLoad(config, emptyList(), emptyList(), ConfigError(message, 0))

    public companion object {
        /** The family's config file, in its folder. */
        public val FILE: StoragePath = StoragePath.of("config.yml")

        /** The family's SQLite database, in its folder, for `database.type: sqlite`. */
        public val DATABASE_FILE: StoragePath = StoragePath.of("xena.db")
    }
}

/**
 * What [Family.load] found: the [config] in use, what was wrong with the file ([problems]), what
 * loading changed ([notices]), and, if the file couldn't be read at all, why ([error]): then
 * [config] is the last good one.
 */
public class FamilyLoad(
    public val config: Config,
    public val problems: List<ConfigProblem>,
    public val notices: List<ConfigNotice>,
    public val error: ConfigError?,
)
