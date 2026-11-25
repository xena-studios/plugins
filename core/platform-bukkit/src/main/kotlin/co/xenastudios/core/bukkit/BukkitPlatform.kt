package co.xenastudios.core.bukkit

import co.xenastudios.core.jvm.scheduler.AsyncExecutor
import co.xenastudios.core.jvm.platform.JulLog
import co.xenastudios.core.bukkit.modern.FoliaScheduler
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.event.EventPriority
import co.xenastudios.core.common.admin.Dependency
import co.xenastudios.core.common.chat.ChatBridge
import co.xenastudios.core.common.command.CommandSource
import co.xenastudios.core.common.family.Family
import co.xenastudios.core.common.family.PlayerCache
import co.xenastudios.core.common.i18n.Translations
import co.xenastudios.core.common.permission.Permission
import co.xenastudios.core.common.update.UpdateChecker
import co.xenastudios.core.common.update.UpdateSettings
import co.xenastudios.core.jvm.config.JvmYaml
import co.xenastudios.core.jvm.db.DriverFetcher
import co.xenastudios.core.jvm.db.OnDemandDriverFetcher
import co.xenastudios.core.loader.bukkit.BukkitBootstrap
import kotlin.concurrent.Volatile
import co.xenastudios.core.common.command.OnlinePlayers
import co.xenastudios.core.common.permission.PermissionRegistrar
import co.xenastudios.core.common.platform.Capability
import co.xenastudios.core.common.platform.Clock
import co.xenastudios.core.common.platform.Log
import co.xenastudios.core.common.platform.Platform
import co.xenastudios.core.common.platform.Storage
import co.xenastudios.core.common.platform.SystemClock
import co.xenastudios.core.common.platform.Threading
import co.xenastudios.core.common.proxy.ProxyChannel
import co.xenastudios.core.common.scheduler.Scheduler
import co.xenastudios.core.jvm.platform.FolderStorage
import org.bukkit.plugin.Plugin

/**
 * The core's [Platform] on a Bukkit-family server, from Spigot 1.8.8 to the newest Paper and
 * Folia: made once in the plugin's `enable` with [create], which looks at the server once
 * ([BukkitServer]) and picks every path from that, never per call (ROADMAP.md, Architecture).
 *
 * | | Folia | Paper | Spigot | CraftBukkit |
 * |---|---|---|---|---|
 * | [scheduler] | Folia's region, entity, global and async schedulers | Bukkit's, async on threads of its own | the same | the same |
 * | [text] | Adventure | Adventure from 1.16.5, then Spigot's | `BaseComponent[]` | `§` strings |
 * | [locale] | the client's, and its changes from 1.12 | the same | the same; before 1.12, `spigot().getLocale()` | from 1.12 |
 *
 * Besides the [Platform], what a Bukkit plugin registers through: [commands] on Bukkit's
 * command map, [permissions] in its plugin manager, [events] bridged to Bukkit's, [players]
 * for the `player` argument, the [family] and its player cache ([record]), and what the admin
 * subcommands ask of a platform ([locale], [dependency], [about], [driverFetcher]), the
 * update checker and bStats ([checkUpdates], [metrics]), and the channel to the plugin's proxy
 * side ([proxyChannel]). [close] undoes all of it as the plugin
 * is disabled.
 */
public class BukkitPlatform private constructor(
    /** The plugin it's for. */
    public val plugin: Plugin,
    /** What the server is and has, found at startup. */
    public val server: BukkitServer,
    override val development: Boolean,
) : Platform {

    override val name: String = server.toString()

    override val threading: Threading = if (server.flavor == BukkitServer.Flavor.FOLIA) Threading.REGIONS else Threading.MAIN_THREAD

    override val capabilities: Set<Capability> = setOf(Capability.NATIVE_EVENTS, Capability.SQL, Capability.SHARED_API)

    override val log: Log = JulLog(plugin.logger)

    override val clock: Clock = SystemClock

    /** `plugins/<Plugin>/`. */
    override val storage: Storage = FolderStorage(plugin.dataFolder.toPath())

    private val main: MainThreadScheduler? =
        if (threading == Threading.REGIONS) null else MainThreadScheduler(plugin, BukkitPlayers.entities(plugin.server, server), AsyncExecutor(plugin.name, log))

    override val scheduler: Scheduler = main ?: folia()

    /** How messages reach players and the console here. */
    public val text: BukkitText = BukkitText.pick(server, plugin.server)

    private val locales: ClientLocales = ClientLocales.pick(server, plugin, scheduler)

    /** The online players each command source can see, for the `player` argument. */
    public val players: OnlinePlayers = BukkitPlayers(plugin.server)

    /** The plugin's commands, on Bukkit's command map. */
    public val commands: BukkitCommands = BukkitCommands.create(plugin, plugin.server, text, log, refreshes = server.atLeast(1, 13))

    /** The plugin's permissions, in Bukkit's plugin manager. */
    public val permissions: PermissionRegistrar = BukkitPermissions(plugin.server.pluginManager)

    /** The plugin's events, fired as Bukkit events too. */
    public val events: BukkitEvents = BukkitEvents(plugin.server)

    /**
     * The family: `plugins/Xena/` and the board the Xena plugins on this server share, found
     * in Bukkit's `ServicesManager` (or put there, by the first plugin) and registered by this
     * one too, so it outlives whichever plugin is disabled first. Load it off the server's
     * threads ([Family.load]).
     */
    public val family: Family = Family(FolderStorage(plugin.dataFolder.toPath().resolveSibling(FAMILY_FOLDER)), BukkitFamily.board(plugin), JvmYaml)

    private val chats = java.util.concurrent.CopyOnWriteArrayList<ChatBridge>()

    /**
     * Starts hearing chat for the plugin at [priority] among other chat plugins (DECISIONS.md,
     * "The chat bridge"): Paper's `AsyncChatEvent` and its renderer on Paper 1.17+ and Folia,
     * Bukkit's `AsyncPlayerChatEvent` and its format on Spigot, CraftBukkit and older Paper.
     * Nothing listens to chat until this is called; [ChatBridge.close] stops it, as [close] does.
     */
    @JvmOverloads
    public fun chat(priority: EventPriority = EventPriority.NORMAL): ChatBridge =
        BukkitChat.create(plugin, text, priority, log).also { chats += it }

    /** Which way chat is heard here: `Paper's AsyncChatEvent` or `Bukkit's AsyncPlayerChatEvent`, for `/<plugin> debug`. */
    public val chatPath: String get() = if (BukkitChat.paperChat(plugin.server)) "Paper's AsyncChatEvent" else "Bukkit's AsyncPlayerChatEvent"

    @Volatile
    private var recording: AutoCloseable? = null

    /**
     * Records each join, quit and client language change in [cache], the family's player cache,
     * from now until [close], and the players online now. Messages then read each player's
     * language from the cache, which is a `LanguageSource`.
     */
    public fun record(cache: PlayerCache) {
        recording?.close()
        recording = BukkitFamily.record(plugin, cache, locales, scheduler)
    }

    @Volatile
    private var updates: AutoCloseable? = null

    @Volatile
    private var bStats: AutoCloseable? = null

    /**
     * Starts the update checker for the plugin's Modrinth [project] (ROADMAP.md, Updates): asks
     * once now and then once a day, off the server's threads, for the newest release on
     * [settings]' channel for this Minecraft version and Modrinth [loader] (`bukkit`; `paper` for a Paper-only plugin); says so in the console and to
     * players with [permission] (the admin commands' `updates`, `<plugin>.admin.update`) as they
     * join, each in their language from [translations] (read at each notice, so a reload's
     * files apply). Call the checker's `apply` with the config's `UpdatesSection` on every
     * reload; [close] stops it. Calling this again replaces the last one.
     */
    @JvmOverloads
    public fun checkUpdates(
        project: String,
        permission: Permission,
        settings: UpdateSettings,
        loader: String = "bukkit",
        translations: () -> Translations,
    ): UpdateChecker {
        val (checker, stop) = BukkitUpdates.start(this, project, permission.node, settings, translations, loader)
        updates?.close()
        updates = stop
        return checker
    }

    /**
     * Turns bStats on for the plugin under its bStats [serviceId] (bstats.org's plugin id), on
     * Bukkit only: bStats has no Fabric platform. The plugin's jar carries bstats-bukkit,
     * relocated into its own package; owners turn bStats off for every plugin at once in
     * `plugins/bStats/config.yml`, which bStats reads itself. Never with `-Dxena.offline=true`.
     * [close] shuts it down.
     */
    public fun metrics(serviceId: Int) {
        bStats?.close()
        bStats = BukkitUpdates.metrics(this, serviceId)
    }

    private val channels = ArrayList<ProxyChannel>()

    /**
     * The plugin's channel to its proxy side, `xena:<id>` (`xena:zchat`), for a server behind
     * Velocity or BungeeCord (DECISIONS.md, "The proxy layer"): signed and checked with the
     * network's secret, `plugins/Xena/proxy.secret`, or on Paper behind Velocity its modern
     * forwarding secret. A server on its own has no one to send to, which costs nothing.
     * Closed by [close].
     */
    @JvmOverloads
    public fun proxyChannel(id: String = plugin.name.lowercase()): ProxyChannel =
        BukkitChannel.open(this, ProxyChannel.nameOf(id), plugin.dataFolder.toPath().toAbsolutePath().resolveSibling(FAMILY_FOLDER))
            .also { synchronized(channels) { channels += it } }

    /** The locale [player]'s client sent (`de_de`), if they're online and the server says. */
    public fun locale(player: PlayerRef): String? = player.online(plugin.server)?.let(locales::of)

    /** The locale of whoever ran a command: a player's client's, `null` for the console. For `AdminText.of`. */
    public fun locale(source: CommandSource): String? = source.player?.let(::locale)

    /** Whether the plugin called [name] is installed and on, for `/<plugin> debug`'s dependencies check. */
    @JvmOverloads
    public fun dependency(name: String, required: Boolean = false): Dependency =
        Dependency(name, plugin.server.pluginManager.getPlugin(name)?.isEnabled == true, required)

    /**
     * The debug report's lines about the server: the Java running it, the server's own version
     * line and Bukkit's, the platform's picks, and the other plugins installed.
     */
    public fun about(): Map<String, String> {
        val bukkit = plugin.server
        return linkedMapOf(
            "Java" to "${System.getProperty("java.version")} (${System.getProperty("java.vendor")})",
            "Server" to bukkit.version,
            "Bukkit API" to bukkit.bukkitVersion,
            "Text" to text.name,
            "Plugins" to bukkit.pluginManager.plugins.joinToString(", ") { "${it.name} ${it.description.version}" + if (it.isEnabled) "" else " (off)" },
        )
    }

    /**
     * Fetches the JDBC drivers the plugin's jar pins (`onDemand`) into the family's shared
     * library cache when a server has none: for `Drivers`. `null` if the plugin's main class
     * isn't the core's shim. Reads the loader's settings, so call it at startup or off the
     * server's threads.
     */
    public fun driverFetcher(): DriverFetcher? = (plugin as? BukkitBootstrap)?.let { OnDemandDriverFetcher(it.onDemandLibraries()) }

    // Its own function, so only Folia loads the class that calls Folia's API.
    private fun folia(): Scheduler = FoliaScheduler(plugin, log)

    /**
     * Undoes what was registered here, as the plugin is disabled: its commands, permissions
     * and event bridges, its waiting tasks, and its async threads, which get five seconds to
     * finish what's running. Disable the plugin's modules first, which may still schedule work.
     */
    public fun close() {
        updates?.close()
        updates = null
        bStats?.close()
        bStats = null
        synchronized(channels) {
            channels.forEach { it.close() }
            channels.clear()
        }
        events.close()
        chats.forEach { it.close() }
        chats.clear()
        commands.clear()
        permissions.sync(emptyList())
        recording?.close()
        recording = null
        locales.close()
        main?.close()
        (scheduler as? AutoCloseable)?.close()
    }

    public companion object {
        // The family's folder, beside each plugin's own.
        private const val FAMILY_FOLDER = "Xena"

        /**
         * The platform for [plugin], from what its server is. A [development] build checks
         * threads (`checkThread`) and throws where code would break on Folia; release builds
         * don't (DECISIONS.md, "The platform layer").
         */
        @JvmStatic
        @JvmOverloads
        public fun create(plugin: Plugin, development: Boolean = false): BukkitPlatform =
            BukkitPlatform(plugin, BukkitServer.detect(plugin.server), development)
    }
}
