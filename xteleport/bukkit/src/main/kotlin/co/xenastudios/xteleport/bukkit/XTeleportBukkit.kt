package co.xenastudios.xteleport.bukkit

import co.xenastudios.core.api.onSettled
import co.xenastudios.core.bukkit.BukkitPlatform
import co.xenastudios.core.common.family.PlayerCache
import co.xenastudios.core.common.family.PlayerStore
import co.xenastudios.core.common.scheduler.Task
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.jvm.admin.MclogsPaste
import co.xenastudios.core.jvm.db.Drivers
import co.xenastudios.core.jvm.i18n.LangFolder
import co.xenastudios.core.loader.PluginEntry
import co.xenastudios.xteleport.EssentialsFiles
import co.xenastudios.xteleport.JvmDatabase
import co.xenastudios.xteleport.JvmFiles
import co.xenastudios.xteleport.Loaded
import co.xenastudios.xteleport.XTeleportPlugin
import co.xenastudios.xteleport.api.XTeleportApi
import co.xenastudios.xteleport.config.XTeleportConfig
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.server.PluginEnableEvent
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin
import java.util.concurrent.TimeUnit

/**
 * xTeleport on Paper, Folia, Spigot and CraftBukkit, 1.8.8 on: the shared plugin on
 * platform-bukkit, loaded by the core's Java shim once the stdlib is there. It wires the shared
 * code to the server:
 *
 * - `config.yml` is read as the plugin enables, so its commands are the server's from the start;
 *   the database is opened, migrated and the warps read on an async task, never the server thread.
 * - Joins, quits and each client's language go into the family's player cache.
 * - PlaceholderAPI's `%xteleport_...%` while it's installed.
 * - The API's events fired as Bukkit events too, and the API in the services manager.
 */
public class XTeleportBukkit(private val plugin: JavaPlugin) : PluginEntry, Listener {

    private var platform: BukkitPlatform? = null
    private var xteleport: XTeleportPlugin? = null
    private var papi: Any? = null

    override fun enable() {
        val started = System.nanoTime()
        val platform = BukkitPlatform.create(plugin, development = java.lang.Boolean.getBoolean("xena.development"))
        val family = platform.family
        val cache = PlayerCache(family.board, PlayerStore.InFile(family.folder), platform.scheduler, platform.clock, platform.log, XTeleportPlugin.PLUGIN_ID)
        platform.record(cache)
        val server = BukkitTeleportServer(plugin, platform)
        val folder = plugin.dataFolder.toPath()
        val files = JvmFiles(folder, family, LangFolder.shipped(plugin.javaClass.classLoader, JvmFiles.LANGUAGES), platform.log)
        val xteleport = XTeleportPlugin(
            platform, server, files, cache, platform.players, cache,
            registrar = platform.commands,
            permissionRegistrar = platform.permissions,
            about = platform::about,
            // -Dxena.debug.paste=false writes debug reports to the plugin's folder instead (boot tests do).
            paste = if (System.getProperty("xena.debug.paste") == "false") null else Mclogs,
            essentials = EssentialsFiles(folder.resolveSibling("Essentials")),
            version = plugin.description.version,
        )
        this.platform = platform
        this.xteleport = xteleport
        platform.commands.feedback = xteleport.texts.feedback
        EventBridge.bridge(plugin.server, xteleport.bus)
        plugin.server.servicesManager.register(XTeleportApi::class.java, xteleport, plugin, ServicePriority.Normal)
        plugin.server.pluginManager.registerEvents(this, plugin)

        // The config now, so the commands are the server's from the start; the database off the thread.
        val config = files.config()
        xteleport.start(config)
        val where = JvmDatabase.where(config.config, family, folder, folder.resolveSibling("Xena"))
        val drivers = Drivers(plugin.javaClass.classLoader, platform.driverFetcher())
        platform.scheduler.run(Where.Async, action = Steps(platform, xteleport, cache, files, config, where, drivers))
        xteleport.updates = platform.checkUpdates(XTeleportPlugin.PLUGIN_ID, xteleport.admin.updates, XTeleportConfig.updates.settings(config.config)) {
            xteleport.texts.translations
        }
        platform.metrics(BSTATS)
        val millis = (System.nanoTime() - started) / 1_000_000
        platform.log.info("xTeleport ${xteleport.version} on ${platform.name}: ${platform.threading} threading, text as ${platform.text}, teleports ${server.features.loading}, enabled in $millis ms")
    }

    override fun disable() {
        val platform = platform ?: return
        HandlerList.unregisterAll(this as Listener)
        papi?.let(PapiHook::unregister)
        plugin.server.servicesManager.unregisterAll(plugin)
        // What's queued is saved before the server stops: the database closes once it's written.
        try {
            xteleport?.stop()?.get(15, TimeUnit.SECONDS)
        } catch (e: Exception) {
            platform.log.warn("Stopping, xTeleport's database didn't finish saving in time: $e")
        }
        platform.close()
        this.platform = null
        xteleport = null
        papi = null
    }

    @EventHandler
    public fun onPluginEnable(event: PluginEnableEvent) {
        if (event.plugin.name == "PlaceholderAPI") xteleport?.let(::hookPlaceholders)
    }

    private fun hookPlaceholders(xteleport: XTeleportPlugin) {
        if (papi != null || !plugin.server.pluginManager.isPluginEnabled("PlaceholderAPI")) return
        papi = PapiHook.register(xteleport)
    }

    // mclo.gs, made with the first /xteleport debug upload: its class isn't loaded before.
    private object Mclogs : co.xenastudios.core.common.admin.PasteService {
        override val name: String get() = "mclo.gs"

        override fun upload(text: String) = MclogsPaste().upload(text)
    }

    /**
     * What happens after enable, as one class rather than a lambda each (each would be a class of
     * its own, made as the server starts: decisions/2026-11-25-a-lighter-core.md). On an async task
     * first: the database opened, the player cache and the lang files read. Then on the global
     * thread: the lang files applied, and PlaceholderAPI.
     */
    private inner class Steps(
        private val platform: BukkitPlatform,
        private val xteleport: XTeleportPlugin,
        private val cache: PlayerCache,
        private val files: JvmFiles,
        private val config: Loaded,
        private val where: JvmDatabase.Where,
        private val drivers: Drivers,
    ) : (Task) -> Unit {
        private var loaded: Loaded? = null

        override fun invoke(task: Task) {
            val read = loaded
            if (read == null) {
                xteleport.open(JvmDatabase.open(where, drivers, xteleport.log))
                cache.load()
                loaded = files.translations(config.config, config.problems)
                platform.scheduler.run(Where.Global, action = this)
            } else {
                xteleport.apply(read)
                hookPlaceholders(xteleport)
            }
        }
    }

    private companion object {
        // bstats.org/plugin/bukkit/xTeleport/35423, 1.x's.
        const val BSTATS = 35423
    }
}
