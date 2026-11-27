package co.xenastudios.xvanish.bukkit

import co.xenastudios.core.bukkit.BukkitPlatform
import co.xenastudios.core.common.family.PlayerCache
import co.xenastudios.core.common.family.PlayerStore
import co.xenastudios.core.common.scheduler.Task
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.jvm.admin.MclogsPaste
import co.xenastudios.core.jvm.db.Drivers
import co.xenastudios.core.jvm.i18n.LangFolder
import co.xenastudios.core.loader.PluginEntry
import co.xenastudios.xvanish.JvmFiles
import co.xenastudios.xvanish.JvmNetwork
import co.xenastudios.xvanish.Loaded
import co.xenastudios.xvanish.VanishImporter
import co.xenastudios.xvanish.XVanishPlugin
import co.xenastudios.xvanish.api.XVanishApi
import co.xenastudios.xvanish.config.XVanishConfig
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.server.PluginEnableEvent
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin
import java.util.concurrent.TimeUnit

/**
 * xVanish on Paper, Folia, Spigot and CraftBukkit, 1.8.8 on: the shared plugin on platform-bukkit,
 * loaded by the core's Java shim once the stdlib is there.
 *
 * - `config.yml` and `vanished.txt` are read as the plugin enables, before anyone can join, so
 *   nobody vanished is ever seen; the lang files follow on an async task.
 * - Joins, quits and each client's language go into the family's player cache, where xVanish
 *   marks who's vanished for the other Xena plugins.
 * - PlaceholderAPI's `%xvanish_...%` while it's installed; the API's events as Bukkit events too,
 *   and the API in the services manager.
 * - Network mode's database, only with `network.enabled`, opened on an async task.
 */
public class XVanishBukkit(private val plugin: JavaPlugin) : PluginEntry, Listener {

    private var platform: BukkitPlatform? = null
    private var xvanish: XVanishPlugin? = null
    private var papi: Any? = null

    override fun enable() {
        val started = System.nanoTime()
        val platform = BukkitPlatform.create(plugin, development = java.lang.Boolean.getBoolean("xena.development"))
        val family = platform.family
        val cache = PlayerCache(family.board, PlayerStore.InFile(family.folder), platform.scheduler, platform.clock, platform.log, XVanishPlugin.PLUGIN_ID)
        platform.record(cache)
        val server = BukkitVanishServer(plugin, platform)
        val folder = plugin.dataFolder.toPath()
        val files = JvmFiles(folder, family, LangFolder.shipped(plugin.javaClass.classLoader, JvmFiles.LANGUAGES), platform.log)
        val xvanish = XVanishPlugin(
            platform, server, files, cache, platform.players, cache,
            registrar = platform.commands,
            permissionRegistrar = platform.permissions,
            about = platform::about,
            // -Dxena.debug.paste=false writes debug reports to the plugin's folder instead (boot tests do).
            paste = if (System.getProperty("xena.debug.paste") == "false") null else Mclogs,
            importers = Importers(folder.parent),
            version = plugin.description.version,
        )
        this.platform = platform
        this.xvanish = xvanish
        platform.commands.feedback = xvanish.texts.feedback
        xvanish.beforeFirstEvent = EventBridge(plugin.server, xvanish.bus)
        plugin.server.servicesManager.register(XVanishApi::class.java, xvanish, plugin, ServicePriority.Normal)
        plugin.server.pluginManager.registerEvents(this, plugin)

        val config = files.config()
        xvanish.start(config)
        platform.scheduler.run(Where.Async, action = Steps(platform, xvanish, cache, files, config))
        xvanish.updates = platform.checkUpdates(XVanishPlugin.PLUGIN_ID, xvanish.admin.updates, XVanishConfig.updates.settings(config.config)) {
            xvanish.texts.translations
        }
        val millis = (System.nanoTime() - started) / 1_000_000
        platform.log.info("xVanish ${xvanish.version} on ${platform.name}: ${platform.threading} threading, text as ${platform.text}, hiding with ${server.features.hiding}, enabled in $millis ms")
    }

    override fun disable() {
        val platform = platform ?: return
        HandlerList.unregisterAll(this as Listener)
        papi?.let(PapiHook::unregister)
        plugin.server.servicesManager.unregisterAll(plugin)
        try {
            xvanish?.stop()?.get(15, TimeUnit.SECONDS)
        } catch (e: Exception) {
            platform.log.warn("Stopping, xVanish didn't finish saving in time: $e")
        }
        platform.close()
        this.platform = null
        xvanish = null
        papi = null
    }

    @EventHandler
    public fun onPluginEnable(event: PluginEnableEvent) {
        if (event.plugin.name == "PlaceholderAPI") xvanish?.let(::hookPlaceholders)
    }

    private fun hookPlaceholders(xvanish: XVanishPlugin) {
        if (papi != null || !plugin.server.pluginManager.isPluginEnabled("PlaceholderAPI")) return
        papi = PapiHook.register(xvanish)
    }

    // SuperVanish's and PremiumVanish's data.yml, beside this plugin's folder.
    private class Importers(private val plugins: java.nio.file.Path) : (XVanishPlugin) -> List<co.xenastudios.core.common.admin.Importer<*>> {
        override fun invoke(plugin: XVanishPlugin) = VanishImporter.all(plugins, plugin)
    }

    // mclo.gs, made with the first /xvanish debug upload.
    private object Mclogs : co.xenastudios.core.common.admin.PasteService {
        override val name: String get() = "mclo.gs"

        override fun upload(text: String) = MclogsPaste().upload(text)
    }

    /**
     * What happens after enable, one class rather than a lambda each. On an async task: the player
     * cache and the lang files read, and network mode's database opened if it's on. Then on the
     * global thread: the lang files applied, and PlaceholderAPI.
     */
    private inner class Steps(
        private val platform: BukkitPlatform,
        private val xvanish: XVanishPlugin,
        private val cache: PlayerCache,
        private val files: JvmFiles,
        private val config: Loaded,
    ) : (Task) -> Unit {
        private var loaded: Loaded? = null

        override fun invoke(task: Task) {
            val read = loaded
            if (read == null) {
                cache.load()
                if (JvmNetwork.wanted(config.config)) {
                    val folder = plugin.dataFolder.toPath()
                    val drivers = Drivers(plugin.javaClass.classLoader, platform.driverFetcher())
                    xvanish.openNetwork(JvmNetwork.open(config.config, platform.family, folder, folder.resolveSibling("Xena"), drivers, xvanish.log))
                }
                loaded = files.translations(config.config, config.problems)
                platform.scheduler.run(Where.Global, action = this)
            } else {
                xvanish.apply(read)
                hookPlaceholders(xvanish)
            }
        }
    }
}
