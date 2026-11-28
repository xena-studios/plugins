package co.xenastudios.xstats.bukkit

import co.xenastudios.core.api.onSettled
import co.xenastudios.core.bukkit.BukkitPlatform
import co.xenastudios.core.bukkit.ref
import co.xenastudios.core.common.family.PlayerCache
import co.xenastudios.core.common.family.PlayerStore
import co.xenastudios.core.common.scheduler.Task
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.jvm.admin.MclogsPaste
import co.xenastudios.core.jvm.db.Drivers
import co.xenastudios.core.jvm.i18n.LangFolder
import co.xenastudios.core.loader.PluginEntry
import co.xenastudios.xstats.FamilyHooks
import co.xenastudios.xstats.JvmDatabase
import co.xenastudios.xstats.JvmExtras
import co.xenastudios.xstats.JvmFiles
import co.xenastudios.xstats.JvmZones
import co.xenastudios.xstats.Loaded
import co.xenastudios.xstats.XStatsPlugin
import co.xenastudios.xstats.api.XStatsApi
import co.xenastudios.xstats.config.XStatsConfig
import co.xenastudios.xstats.data.Event
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.event.server.PluginEnableEvent
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin

/**
 * xStats on Paper, Folia, Spigot and CraftBukkit, 1.13 on: the shared plugin on platform-bukkit,
 * loaded by the core's Java shim once the stdlib is there. It wires the shared code to the server:
 *
 * - `config.yml` is read as the plugin enables, so its commands are the server's from the start; the
 *   database is opened, migrated and every player read on an async task, never the server thread.
 * - Joins start a session; quits read the player one last time, on their thread. Nothing else is
 *   listened to: Minecraft's own counters are read at each save.
 * - PlaceholderAPI's placeholders while it's on; the family's stats from the Xena plugins installed.
 */
public class XStatsBukkit(private val plugin: JavaPlugin) : PluginEntry, Listener {
    private var platform: BukkitPlatform? = null
    private var xstats: XStatsPlugin? = null
    private var server: BukkitStatsServer? = null
    private var placeholders: Any? = null
    private var family: FamilyHooks? = null

    override fun enable() {
        val started = System.nanoTime()
        val platform = BukkitPlatform.create(plugin, development = java.lang.Boolean.getBoolean("xena.development"))
        val family = platform.family
        val cache = PlayerCache(family.board, PlayerStore.InFile(family.folder), platform.scheduler, platform.clock, platform.log, XStatsPlugin.PLUGIN_ID)
        platform.record(cache)
        val server = BukkitStatsServer(platform)
        val folder = plugin.dataFolder.toPath()
        val files = JvmFiles(folder, family, LangFolder.shipped(plugin.javaClass.classLoader, JvmFiles.LANGUAGES), platform.log)
        val config = files.config()
        val drivers = Drivers(plugin.javaClass.classLoader, platform.driverFetcher())
        val plugins = folder.toAbsolutePath().parent
        // The main world's folder, where Minecraft keeps every player's stats file.
        val world: Path? = plugin.server.worlds.firstOrNull()?.worldFolder?.toPath()
        val extras = JvmExtras(folder, Holder(world), plugin.server.worldContainer.toPath(), OfflineNames(plugin), plugin.javaClass.classLoader)
        val xstats = XStatsPlugin(
            platform, server, files, cache, platform.players, cache,
            opener = JvmDatabase(family, folder, plugins.resolve("Xena"), drivers, platform.log),
            registrar = platform.commands,
            permissionRegistrar = platform.permissions,
            about = platform::about,
            // -Dxena.debug.paste=false writes debug reports to the plugin's folder instead (boot tests do).
            paste = if (System.getProperty("xena.debug.paste") == "false") null else Mclogs,
            extras = extras,
            zones = JvmZones,
            version = plugin.description.version,
        )
        this.platform = platform
        this.xstats = xstats
        this.server = server
        platform.commands.feedback = xstats.texts.feedback
        xstats.beforeFirstEvent = EventBridge(plugin.server, xstats.bus)
        plugin.server.servicesManager.register(XStatsApi::class.java, xstats, plugin, ServicePriority.Normal)
        plugin.server.pluginManager.registerEvents(this, plugin)
        xstats.start(config)
        xstats.open()
        platform.scheduler.run(Where.Async, action = Steps(platform, xstats, cache, files, config))
        xstats.updates = platform.checkUpdates(XStatsPlugin.PLUGIN_ID, xstats.admin.updates, XStatsConfig.updates.settings(config.config), translations = xstats.texts)
        platform.metrics(BSTATS)
        val millis = (System.nanoTime() - started) / 1_000_000
        platform.log.info("xStats ${xstats.version} on ${platform.name}: ${platform.threading} threading, text as ${platform.text}, enabled in $millis ms")
    }

    override fun disable() {
        val platform = platform ?: return
        HandlerList.unregisterAll(this as Listener)
        unhookPlaceholders()
        family?.close()
        plugin.server.servicesManager.unregisterAll(plugin)
        // Players still online: one last reading each, without placeholders (other plugins may be gone).
        val readings = ArrayList<Event.Sample>()
        val xstats = xstats
        if (xstats != null) {
            for (player in plugin.server.onlinePlayers) {
                try {
                    server?.read(player.ref, xstats.catalog.snapshot, quit = true, placeholders = false)?.let(readings::add)
                } catch (e: RuntimeException) {
                    // Folia: a player on another region's thread. Their last save's reading stands.
                }
            }
        }
        try {
            xstats?.stop(readings)?.get(15, TimeUnit.SECONDS)
        } catch (e: Exception) {
            platform.log.warn("Stopping, xStats' last save didn't finish in time: $e")
        }
        platform.close()
        this.platform = null
        this.xstats = null
        this.server = null
    }

    // First, before other plugins' join listeners: a first-join reward that adds to a stat through
    // the API finds the player already known, instead of being dropped.
    @EventHandler(priority = EventPriority.LOWEST)
    public fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        val look = player.location
        xstats?.joined(player.ref, player.name, player.firstPlayed, look.yaw, look.pitch)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public fun onQuit(event: PlayerQuitEvent) {
        xstats?.quit(event.player.ref)
    }

    @EventHandler
    public fun onPluginEnable(event: PluginEnableEvent) {
        val xstats = xstats ?: return
        val name = event.plugin.name
        if (name == "PlaceholderAPI") hookPlaceholders(xstats) else family?.hook(name)
    }

    @EventHandler
    public fun onPluginDisable(event: PluginDisableEvent) {
        val name = event.plugin.name
        if (name == "PlaceholderAPI") {
            placeholders = null
            server?.placeholders = null
        } else {
            family?.unhook(name)
            xstats?.unregisterStats(name)
            xstats?.sections?.removeAll(name)
        }
    }

    private fun hookPlaceholders(xstats: XStatsPlugin) {
        if (placeholders != null) return
        placeholders = try {
            server?.placeholders = Fill
            PlaceholderHook.register(xstats, plugin.description.version)
        } catch (e: LinkageError) {
            xstats.log.warn("Couldn't add xStats' placeholders to PlaceholderAPI: $e")
            server?.placeholders = null
            null
        }
    }

    private fun unhookPlaceholders() {
        val expansion = placeholders ?: return
        server?.placeholders = null
        try {
            PlaceholderHook.unregister(expansion)
        } catch (e: LinkageError) {
            // PlaceholderAPI went first.
        }
        placeholders = null
    }

    /**
     * What happens after enable, as one class rather than a lambda each. On an async task first: the
     * player cache and the lang files read. Then on the global thread: the lang files applied,
     * PlaceholderAPI and, once the database is open, the family's stats.
     */
    private inner class Steps(
        private val platform: BukkitPlatform,
        private val xstats: XStatsPlugin,
        private val cache: PlayerCache,
        private val files: JvmFiles,
        private val config: Loaded,
    ) : (Task) -> Unit {
        private var loaded: Loaded? = null
        private var step = 0

        override fun invoke(task: Task) {
            when (step++) {
                0 -> {
                    cache.load()
                    loaded = files.translations(config.config, config.problems)
                    platform.scheduler.run(Where.Global, action = this)
                }
                1 -> {
                    xstats.apply(loaded!!)
                    if (plugin.server.pluginManager.isPluginEnabled("PlaceholderAPI")) hookPlaceholders(xstats)
                    // The family's stats are registered once the database is open, so their saved values are read first.
                    xstats.ready.onSettled { open, _ -> if (open == true) platform.scheduler.run(Where.Global, action = this) }
                }
                else -> {
                    val hooks = FamilyHooks(xstats, Loaders(plugin))
                    family = hooks
                    hooks.hookAll()
                }
            }
        }
    }

    /** A plugin's class loader by its name, while it's enabled: where its API is. */
    private class Loaders(private val plugin: JavaPlugin) : (String) -> ClassLoader? {
        override fun invoke(name: String): ClassLoader? = plugin.server.pluginManager.getPlugin(name)?.takeIf { it.isEnabled }?.javaClass?.classLoader
    }

    /** A player's last known name, from the server's own records. Off the server's threads. */
    private class OfflineNames(private val plugin: JavaPlugin) : (co.xenastudios.core.api.PlayerRef) -> String? {
        override fun invoke(player: co.xenastudios.core.api.PlayerRef): String? = try {
            plugin.server.getOfflinePlayer(java.util.UUID.fromString(player.toString())).name
        } catch (e: RuntimeException) {
            null
        }
    }

    private class Holder(private val world: Path?) : () -> Path? {
        override fun invoke(): Path? = world
    }

    // mclo.gs, made with the first /xstats debug, not as the plugin enables.
    private object Mclogs : co.xenastudios.core.common.admin.PasteService {
        override val name: String get() = "mclo.gs"

        override fun upload(text: String) = MclogsPaste().upload(text)
    }

    private companion object {
        // bstats.org/plugin/bukkit/xStats/35436, 1.x's.
        const val BSTATS = 35436
    }
}
