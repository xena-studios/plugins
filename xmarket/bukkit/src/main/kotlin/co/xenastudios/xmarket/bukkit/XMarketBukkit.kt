package co.xenastudios.xmarket.bukkit

import co.xenastudios.core.bukkit.BukkitPlatform
import co.xenastudios.core.common.family.PlayerCache
import co.xenastudios.core.common.family.PlayerStore
import co.xenastudios.core.common.scheduler.Task
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.jvm.admin.MclogsPaste
import co.xenastudios.core.jvm.db.Drivers
import co.xenastudios.core.jvm.i18n.LangFolder
import co.xenastudios.core.loader.PluginEntry
import co.xenastudios.xmarket.importSources
import co.xenastudios.xmarket.JvmDatabase
import co.xenastudios.xmarket.JvmFiles
import co.xenastudios.xmarket.Loaded
import co.xenastudios.xmarket.XMarketPlugin
import co.xenastudios.xmarket.api.XMarketApi
import co.xenastudios.xmarket.api.bukkit.BukkitItemKeys
import co.xenastudios.xmarket.config.XMarketConfig
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin
import java.util.concurrent.TimeUnit

/**
 * xMarket on Paper, Folia, Spigot and CraftBukkit, 1.8.8 on: the shared plugin on
 * platform-bukkit, loaded by the core's Java shim once the stdlib is there.
 *
 * - `config.yml` is read as the plugin enables, so its commands are the server's from the start;
 *   the price list (`shops/`), the lang files and the database are read on an async task, never
 *   the server thread, and until they're in, the shop says it's loading.
 * - The API's events are fired as Bukkit events too, and the API is in the services manager.
 */
public class XMarketBukkit(private val plugin: JavaPlugin) : PluginEntry, (XMarketPlugin) -> co.xenastudios.core.common.admin.Imports? {
    private var platform: BukkitPlatform? = null
    private var xmarket: XMarketPlugin? = null
    private var server: BukkitMarketServer? = null
    private var files: JvmFiles? = null

    override fun enable() {
        val started = System.nanoTime()
        val platform = BukkitPlatform.create(plugin, development = java.lang.Boolean.getBoolean("xena.development"))
        val family = platform.family
        val cache = PlayerCache(family.board, PlayerStore.InFile(family.folder), platform.scheduler, platform.clock, platform.log, XMarketPlugin.PLUGIN_ID)
        platform.record(cache)
        val server = BukkitMarketServer(plugin, platform)
        this.server = server
        val folder = plugin.dataFolder.toPath()
        val files = JvmFiles(folder, family, LangFolder.shipped(plugin.javaClass.classLoader, JvmFiles.LANGUAGES), platform.log, JvmFiles.classpath(plugin.javaClass.classLoader))
        this.files = files
        val xmarket = XMarketPlugin(
            platform, server, files, cache, platform.players, cache,
            registrar = platform.commands,
            permissionRegistrar = platform.permissions,
            about = platform::about,
            paste = if (System.getProperty("xena.debug.paste") == "false") null else Mclogs,
            importers = this,
            version = plugin.description.version,
        )
        this.platform = platform
        this.xmarket = xmarket
        this.server = server
        server.xmarket = xmarket
        platform.commands.feedback = xmarket.texts.feedback
        xmarket.beforeFirstEvent = EventBridge(plugin.server, xmarket.bus)
        plugin.server.servicesManager.register(XMarketApi::class.java, xmarket, plugin, ServicePriority.Normal)
        BukkitItemKeys.register(server)

        val config = files.config()
        xmarket.start(config)
        val where = JvmDatabase.where(config.config, family, folder, folder.resolveSibling("Xena"))
        val drivers = Drivers(plugin.javaClass.classLoader, platform.driverFetcher())
        platform.scheduler.run(Where.Async, action = Steps(platform, xmarket, cache, files, config, where, drivers))
        xmarket.updates = platform.checkUpdates(XMarketPlugin.PLUGIN_ID, xmarket.admin.updates, XMarketConfig.updates.settings(config.config)) {
            xmarket.texts.translations
        }
        // No bStats: xMarket has no service id on bstats.org yet (decisions/2026-11-27-xmarket.md).
        val millis = (System.nanoTime() - started) / 1_000_000
        platform.log.info("xMarket ${xmarket.version} on ${platform.name}: ${platform.threading} threading, text as ${platform.text}, enabled in $millis ms")
    }

    override fun disable() {
        val platform = platform ?: return
        BukkitItemKeys.register(null)
        plugin.server.servicesManager.unregisterAll(plugin)
        try {
            xmarket?.stop()?.get(15, TimeUnit.SECONDS)
        } catch (e: Exception) {
            platform.log.warn("Stopping, xMarket's database didn't finish saving in time: $e")
        }
        server?.close()
        platform.close()
        this.platform = null
        xmarket = null
        server = null
    }

    private object Mclogs : co.xenastudios.core.common.admin.PasteService {
        override val name: String get() = "mclo.gs"

        override fun upload(text: String) = MclogsPaste().upload(text)
    }

    // `/xmarket import`'s sources, as the admin commands are first built: one small class each.
    override fun invoke(xmarket: XMarketPlugin): co.xenastudios.core.common.admin.Imports? {
        val files = files ?: return null
        val server = server ?: return null
        return importSources(xmarket, plugin.dataFolder.toPath().parent, files, server)
    }

    /**
     * After enable, on an async task: the price list, the database, the player cache and the
     * lang files; then on the global thread, the lang files applied.
     */
    private inner class Steps(
        private val platform: BukkitPlatform,
        private val xmarket: XMarketPlugin,
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
                xmarket.loadPrices()
                xmarket.open(JvmDatabase.open(where, drivers, xmarket.log))
                cache.load()
                loaded = files.translations(config.config, config.problems)
                platform.scheduler.run(Where.Global, action = this)
            } else {
                xmarket.apply(read)
            }
        }
    }
}
