package co.xenastudios.xlimbo.bukkit

import co.xenastudios.core.bukkit.BukkitPlatform
import co.xenastudios.core.common.family.PlayerCache
import co.xenastudios.core.common.family.PlayerStore
import co.xenastudios.core.common.scheduler.Task
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.jvm.admin.MclogsPaste
import co.xenastudios.core.jvm.i18n.LangFolder
import co.xenastudios.core.loader.PluginEntry
import co.xenastudios.xlimbo.JvmFiles
import co.xenastudios.xlimbo.JvmStatusPing
import co.xenastudios.xlimbo.Loaded
import co.xenastudios.xlimbo.XLimboPlugin
import co.xenastudios.xlimbo.api.XLimbo
import co.xenastudios.xlimbo.api.XLimboApi
import co.xenastudios.xlimbo.config.XLimboConfig
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.event.server.PluginEnableEvent
import org.bukkit.event.world.WorldLoadEvent
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin

/**
 * xLimbo on Paper 1.21.11 and newer: the shared plugin on platform-bukkit, loaded by the core's
 * Java shim once the stdlib is there. Paper only, by design.
 *
 * - The plugin enables before the worlds load (`load: STARTUP`), so a world in bukkit.yml can use
 *   xLimbo's generator (which [XLimboBootstrap] answers for on its own). It starts as the first of
 *   the server's worlds loads: still before any plugin that loads after the worlds, any tick and
 *   any player, but by then the core's loader has loaded and verified the classes it needs on
 *   its own thread, as the world loaded, instead of the server's thread doing all of them as the
 *   plugin enables (a plugin enabled at STARTUP is enabled straight after it's loaded). A plugin
 *   enabled once the worlds are there (`/reload confirm`, a plugin manager) starts at once.
 * - `config.yml` is read as it starts; the limbo world itself is made on the first tick, once
 *   the server's own are loaded. The lang files follow on an async task.
 * - xTeleport and xVanish, when they're enabled: sending players back with xTeleport's teleports
 *   and destinations (and giving it the `xlimbo` destination), and xVanish's say on who's hidden.
 * - The API's events as Bukkit events too, and the API in the services manager.
 */
public class XLimboBukkit(private val plugin: JavaPlugin) : PluginEntry, Listener {

    private var platform: BukkitPlatform? = null
    private var xlimbo: XLimboPlugin? = null
    private var server: BukkitLimboServer? = null

    // Enabled, and waiting for the first of the server's worlds to load to start. Server thread.
    private var waiting = false

    override fun enable() {
        plugin.server.pluginManager.registerEvents(this, plugin)
        if (plugin.server.worlds.isEmpty()) waiting = true else start(loadingWorlds = false)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public fun onWorldLoad(event: WorldLoadEvent) {
        WorldLoadEvent.getHandlerList().unregister(this as Listener)
        if (!waiting) return
        waiting = false
        try {
            start(loadingWorlds = true)
        } catch (e: Throwable) {
            plugin.logger.log(java.util.logging.Level.SEVERE, "${plugin.name} couldn't start: $e", e)
            plugin.server.pluginManager.disablePlugin(plugin)
        }
    }

    private fun start(loadingWorlds: Boolean) {
        val started = System.nanoTime()
        val platform = BukkitPlatform.create(plugin, development = java.lang.Boolean.getBoolean("xena.development"))
        val family = platform.family
        val cache = PlayerCache(family.board, PlayerStore.InFile(family.folder), platform.scheduler, platform.clock, platform.log, XLimboPlugin.PLUGIN_ID)
        platform.record(cache)
        val server = BukkitLimboServer(plugin, platform, cache)
        val folder = plugin.dataFolder.toPath()
        val files = JvmFiles(folder, family, LangFolder.shipped(plugin.javaClass.classLoader, JvmFiles.LANGUAGES), platform.log)
        val xlimbo = XLimboPlugin(
            platform, server, files, cache, platform.players, JvmStatusPing, cache,
            registrar = platform.commands,
            permissionRegistrar = platform.permissions,
            about = platform::about,
            // -Dxena.debug.paste=false writes debug reports to the plugin's folder instead (boot tests do).
            paste = if (System.getProperty("xena.debug.paste") == "false") null else Mclogs,
            version = plugin.pluginMeta.version,
        )
        server.xlimbo = xlimbo
        this.platform = platform
        this.xlimbo = xlimbo
        this.server = server
        platform.commands.feedback = xlimbo.texts.feedback
        xlimbo.beforeFirstEvent = EventBridge(plugin.server, xlimbo.bus)
        plugin.server.servicesManager.register(XLimboApi::class.java, xlimbo, plugin, ServicePriority.Normal)
        plugin.server.messenger.registerOutgoingPluginChannel(plugin, BukkitLimboServer.CHANNEL)

        // The config now, so the commands are the server's from the start.
        val config = files.config()
        // xLimbo 1.x made its world without a marker; the one its config names is ours.
        server.world.adoptUnmarked = files.carriedFrom == 1
        server.loadingWorlds = loadingWorlds
        xlimbo.start(config)
        platform.scheduler.run(Where.Async, action = Steps(platform, xlimbo, cache, files, config))
        xlimbo.updates = platform.checkUpdates(XLimboPlugin.PLUGIN_ID, xlimbo.admin.updates, XLimboConfig.updates.settings(config.config)) {
            xlimbo.texts.translations
        }
        platform.metrics(BSTATS)
        val millis = (System.nanoTime() - started) / 1_000_000
        platform.log.info("xLimbo ${xlimbo.version} on ${platform.name}: sending back ${xlimbo.route.name.lowercase()}, started in $millis ms")
    }

    override fun disable() {
        waiting = false
        HandlerList.unregisterAll(this as Listener)
        val platform = platform ?: return
        server?.teleport?.unregisterAll(XLimboPlugin.NAME)
        plugin.server.servicesManager.unregisterAll(plugin)
        xlimbo?.stop()
        // Reloaded in place (/reload confirm, a plugin manager): a world made with this instance's
        // generator would keep it, and its class loader, after the new one starts. A server
        // stopping saves and unloads its worlds itself.
        if (!Bukkit.isStopping()) server?.world?.letGo()
        plugin.server.messenger.unregisterOutgoingPluginChannel(plugin)
        platform.close()
        this.platform = null
        xlimbo = null
        server = null
    }

    @EventHandler
    public fun onPluginEnable(event: PluginEnableEvent) {
        when (event.plugin.name) {
            "xTeleport", "xVanish" -> hookFamily()
        }
    }

    @EventHandler
    public fun onPluginDisable(event: PluginDisableEvent) {
        val server = server ?: return
        when (event.plugin.name) {
            "xTeleport" -> server.teleport = null
            "xVanish" -> server.vanish = null
        }
    }

    // xTeleport's and xVanish's APIs, once they're enabled: they start after the worlds, xLimbo before.
    private fun hookFamily() {
        val server = server ?: return
        val platform = platform ?: return
        if (server.teleport == null && plugin.server.pluginManager.isPluginEnabled("xTeleport")) {
            val (bridge, why) = XTeleportBridge.find(plugin.server)
            if (bridge != null) {
                bridge.registerDestination(XLimboPlugin.NAME, XLimbo.DESTINATION, server::destinationFor)
                server.teleport = bridge
                platform.log.info("Sending players back through xTeleport; its \"${XLimbo.DESTINATION}\" destination sends them here.")
            } else if (why != null) {
                platform.log.warn("xTeleport is installed, but xLimbo can't use it: $why. Teleports back are Paper's own.")
            }
        }
        if (server.vanish == null && plugin.server.pluginManager.isPluginEnabled("xVanish")) server.vanish = XVanishBridge.find(plugin.server)
    }

    // mclo.gs, made with the first /xlimbo debug upload.
    private object Mclogs : co.xenastudios.core.common.admin.PasteService {
        override val name: String get() = "mclo.gs"

        override fun upload(text: String) = MclogsPaste().upload(text)
    }

    /**
     * What happens after enable, one class rather than a lambda each. On an async task: the player
     * cache and the lang files read. Then on the global thread (the first tick, once every plugin
     * is enabled): the lang files applied, and xTeleport and xVanish found.
     */
    private inner class Steps(
        private val platform: BukkitPlatform,
        private val xlimbo: XLimboPlugin,
        private val cache: PlayerCache,
        private val files: JvmFiles,
        private val config: Loaded,
    ) : (Task) -> Unit {
        private var loaded: Loaded? = null

        override fun invoke(task: Task) {
            val read = loaded
            if (read == null) {
                cache.load()
                loaded = files.translations(config.config, config.problems)
                platform.scheduler.run(Where.Global, action = this)
            } else {
                xlimbo.apply(read)
                hookFamily()
            }
        }
    }

    private companion object {
        // bstats.org/plugin/bukkit/xLimbo/34867, 2.x's.
        const val BSTATS = 34867
    }
}
