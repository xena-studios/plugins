package co.xenastudios.xutilities

import co.xenastudios.core.api.Location
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.event.EventBus
import co.xenastudios.core.common.admin.Debug
import co.xenastudios.core.common.admin.HealthChecks
import co.xenastudios.core.common.admin.PasteService
import co.xenastudios.core.common.admin.RecentErrors
import co.xenastudios.core.common.command.AdminCommands
import co.xenastudios.core.common.command.CommandDispatcher
import co.xenastudios.core.common.command.CommandRegistrar
import co.xenastudios.core.common.command.ModuleReloader
import co.xenastudios.core.common.command.OnlinePlayers
import co.xenastudios.core.common.config.Config
import co.xenastudios.core.common.event.SimpleEventBus
import co.xenastudios.core.common.i18n.LanguageSource
import co.xenastudios.core.common.i18n.Translations
import co.xenastudios.core.common.module.Module
import co.xenastudios.core.common.module.ModuleSet
import co.xenastudios.core.common.permission.PermissionRegistrar
import co.xenastudios.core.common.platform.Platform
import co.xenastudios.xutilities.api.XUtilities
import co.xenastudios.xutilities.api.XUtilitiesApi
import co.xenastudios.xutilities.config.XUtilitiesConfig
import kotlin.concurrent.Volatile

/** What the plugin's files said when they were last read: the config, the lang files, and the problems found. */
public class Loaded(
    public val config: Config,
    public val translations: Translations,
    /** Each problem, in English, already logged. */
    public val problems: List<String> = emptyList(),
)

/**
 * Reads `config.yml` and `lang/`, and the family's settings: on the JVM, [JvmFiles]. Called
 * off the server's threads at startup, and on the global thread by `/xutilities reload`.
 */
public fun interface Files {
    public fun load(): Loaded
}

/**
 * xUtilities, the same on every platform: each adapter makes one with its [Platform] and its
 * [Server], loads the files off the server thread ([Files]) and calls [start] on the global
 * thread. Everything the config says applies on `/xutilities reload`, commands included.
 */
public class XUtilitiesPlugin(
    private val platform: Platform,
    /** What the adapter does to players. */
    public val server: Server,
    private val players: OnlinePlayers,
    private val files: Files,
    /** Each player's client language: the family's player cache. */
    languages: LanguageSource,
    private val registrar: CommandRegistrar? = null,
    private val permissionRegistrar: PermissionRegistrar? = null,
    /** What `/xutilities debug`'s report says about the server: Java, the server's version, other plugins. */
    about: () -> Map<String, String> = { emptyMap() },
    /** Where `/xutilities debug` uploads its report; `null` to write a file. */
    paste: PasteService? = null,
    override val version: String = VERSION,
) : XUtilitiesApi {

    /** Where the plugin's console lines go; the last 50 warnings and errors are kept for `/xutilities debug`. */
    public val log: RecentErrors = RecentErrors(platform.log, platform.clock)

    /** The messages, in each reader's language. */
    public val texts: Texts = Texts(languages)

    /** The API's events. On Bukkit the adapter bridges them to Bukkit events. */
    public val bus: SimpleEventBus = SimpleEventBus(log)

    override val events: EventBus get() = bus

    /** Where `/back` takes each player. */
    public val spots: BackSpots = BackSpots(bus)

    /** The modules: `/back`'s listener, while `/back` is on. */
    public val modules: ModuleSet = ModuleSet(platform)

    /** The config in use. */
    @Volatile
    public var config: Config = XUtilitiesConfig.defaults
        private set

    @Volatile
    private var problems: List<String> = emptyList()

    /** `/xutilities reload` and `/xutilities debug`. xUtilities keeps no player data, so there's no `forget`. */
    public val admin: AdminCommands = AdminCommands(
        PLUGIN_ID, NAME, platform,
        ModuleReloader(modules, reloadFiles = ::reload, wanted = ::wanted),
        debug = Debug(
            platform, "$NAME $version",
            listOf(HealthChecks.threading(platform), HealthChecks.modules(modules), HealthChecks.config { problems }),
            about = about, errors = log, paste = paste,
        ),
        text = texts.admin,
    )

    private val commands = Commands(this, platform, players)

    /** What runs the commands; built again on reload, from the new config. */
    @Volatile
    public var dispatcher: CommandDispatcher = CommandDispatcher(commands.tree(config), platform.clock, log)
        private set

    /** The permissions the config declares, with the admin subcommands' after them. */
    @Volatile
    public var permissions: XUtilitiesPermissions = commands.permissions
        private set

    @Volatile
    private var labels: Set<String> = emptySet()

    /**
     * Turns the plugin on with what [Files] read: the permissions, the commands and `/back`'s
     * listener as the config says, then the API. On the global thread.
     */
    public fun start(loaded: Loaded) {
        apply(loaded)
        modules.sync(wanted())
        XUtilities.register(this)
    }

    /** Turns the plugin off, as the server stops: the API, the modules, then the commands. */
    public fun stop() {
        XUtilities.register(null)
        modules.clear()
        registrar?.clear()
        permissionRegistrar?.sync(emptyList())
    }

    // /xutilities reload: the files again, then everything built from them. Returns the problems found.
    private fun reload(): List<String> = apply(files.load())

    /**
     * Uses what [Files] read since [start]: at startup, the lang files and the family's look,
     * read off the server thread once the config's commands are on. On the global thread.
     */
    public fun apply(loaded: Loaded): List<String> {
        config = loaded.config
        texts.translations = loaded.translations
        dispatcher = CommandDispatcher(commands.tree(config), platform.clock, log)
        permissions = commands.permissions
        labels = dispatcher.tree.roots.flatMapTo(HashSet()) { listOf(it.name) + it.aliases }
        permissionRegistrar?.sync(permissions.permissions + admin.permissions.permissions)
        val warnings = registrar?.sync(dispatcher).orEmpty() + commands.warnings + soundWarnings()
        warnings.forEach(log::warn)
        problems = loaded.problems + warnings
        return problems
    }

    private fun soundWarnings(): List<String> = XUtilitiesConfig.all.mapNotNull { section ->
        val key = (section as? co.xenastudios.xutilities.config.SoundCommand)?.sound ?: return@mapNotNull null
        val sound = config[key]
        if (sound.isNone || !config[section.enabled] || server.knowsSound(sound) != false) return@mapNotNull null
        "config.yml: commands.${section.command}.sound \"${sound.name}\" isn't a sound this server knows. Only a resource pack that adds it will play it."
    }

    private fun wanted(): Map<String, () -> Module> =
        if (config[XUtilitiesConfig.commands.back.enabled]) mapOf("back" to { BackModule(server, spots) }) else emptyMap()

    // The API.

    override fun isBackEnabled(): Boolean = spots.enabled

    override fun getBackLocation(player: PlayerRef): Location? = spots[player]

    override fun setBackLocation(player: PlayerRef, location: Location): Boolean = server.isOnline(player) && spots.put(player, location)

    override fun clearBackLocation(player: PlayerRef) {
        spots.forget(player)
    }

    override fun isCommandEnabled(label: String): Boolean {
        val name = label.removePrefix("/").lowercase().removePrefix("$PLUGIN_ID:")
        return name in labels
    }

    public companion object {
        public const val PLUGIN_ID: String = "xutilities"
        public const val NAME: String = "xUtilities"

        /** xUtilities' version. The adapters' builds check theirs is the same. */
        public const val VERSION: String = "3.0.0-alpha.1"
    }
}
