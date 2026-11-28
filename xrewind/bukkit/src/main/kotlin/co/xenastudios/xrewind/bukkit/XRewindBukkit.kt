package co.xenastudios.xrewind.bukkit

import co.xenastudios.core.bukkit.BukkitPlatform
import co.xenastudios.core.common.family.PlayerCache
import co.xenastudios.core.common.family.PlayerStore
import co.xenastudios.core.common.scheduler.Task
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.jvm.admin.MclogsPaste
import co.xenastudios.core.jvm.db.Drivers
import co.xenastudios.core.jvm.i18n.LangFolder
import co.xenastudios.core.loader.PluginEntry
import co.xenastudios.xrewind.CoreProtectImport
import co.xenastudios.xrewind.JvmDatabase
import co.xenastudios.xrewind.JvmFiles
import co.xenastudios.xrewind.Loaded
import co.xenastudios.xrewind.XRewindPlugin
import co.xenastudios.xrewind.api.XRewindApi
import co.xenastudios.xrewind.config.XRewindConfig
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin
import java.util.concurrent.TimeUnit

/**
 * xRewind on Paper, Folia, Spigot and CraftBukkit, 1.13 on: the shared plugin on platform-bukkit,
 * loaded by the core's Java shim once the stdlib is there.
 *
 * - `config.yml` is read as the plugin enables, so its commands and what's logged are the
 *   server's from the start; the lang files and the database are read on an async task, never the
 *   server thread, and until the database is ready changes wait in memory.
 * - The API's events are fired as Bukkit events too, and the API is in the services manager.
 */
public class XRewindBukkit(private val plugin: JavaPlugin) : PluginEntry {
    private var platform: BukkitPlatform? = null
    private var xrewind: XRewindPlugin? = null

    override fun enable() {
        val started = System.nanoTime()
        val platform = BukkitPlatform.create(plugin, development = java.lang.Boolean.getBoolean("xena.development"))
        val family = platform.family
        val cache = PlayerCache(family.board, PlayerStore.InFile(family.folder), platform.scheduler, platform.clock, platform.log, XRewindPlugin.PLUGIN_ID)
        platform.record(cache)
        val server = BukkitRewindServer(plugin, platform)
        val folder = plugin.dataFolder.toPath()
        val files = JvmFiles(folder, family, LangFolder.shipped(plugin.javaClass.classLoader, JvmFiles.LANGUAGES), platform.log)
        val drivers = Drivers(plugin.javaClass.classLoader, platform.driverFetcher())
        val opener = JvmDatabase(family, folder, folder.resolveSibling("Xena"), drivers, platform.log)
        val xrewind = XRewindPlugin(
            platform, server, files, cache, platform.players, cache, opener,
            registrar = platform.commands,
            permissionRegistrar = platform.permissions,
            about = platform::about,
            paste = if (System.getProperty("xena.debug.paste") == "false") null else Mclogs,
            importer = History(folder, drivers, plugin.javaClass.classLoader, platform, server),
            version = plugin.description.version,
        )
        this.platform = platform
        this.xrewind = xrewind
        running = xrewind to server
        platform.commands.feedback = xrewind.texts.feedback
        xrewind.beforeFirstEvent = EventBridge(plugin.server, xrewind.bus)
        plugin.server.servicesManager.register(XRewindApi::class.java, xrewind, plugin, ServicePriority.Normal)

        val config = files.config()
        xrewind.start(config)
        xrewind.open()
        platform.scheduler.run(Where.Async, action = Steps(platform, xrewind, cache, files, config))
        xrewind.updates = platform.checkUpdates(XRewindPlugin.PLUGIN_ID, xrewind.admin.updates, XRewindConfig.updates.settings(config.config)) {
            xrewind.texts.translations
        }
        // bStats 35410, xRewind 1.x's.
        platform.metrics(BSTATS)
        val millis = (System.nanoTime() - started) / 1_000_000
        platform.log.info("xRewind ${xrewind.version} on ${platform.name}: ${platform.threading} threading, text as ${platform.text}, enabled in $millis ms")
    }

    override fun disable() {
        val platform = platform ?: return
        running = null
        plugin.server.servicesManager.unregisterAll(plugin)
        try {
            xrewind?.stop()?.get(15, TimeUnit.SECONDS)
        } catch (e: Exception) {
            platform.log.warn("Stopping, xRewind's database didn't finish saving in time: $e")
        }
        platform.close()
        this.platform = null
        xrewind = null
    }

    private object Mclogs : co.xenastudios.core.common.admin.PasteService {
        override val name: String get() = "mclo.gs"

        override fun upload(text: String) = MclogsPaste().upload(text)
    }

    /** After enable, on an async task: the player cache and the lang files; then on the global thread, the lang files applied. */
    private class Steps(
        private val platform: BukkitPlatform,
        private val xrewind: XRewindPlugin,
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
                xrewind.apply(read)
            }
        }
    }

    /** CoreProtect's history, made the first time `/xrewind import` runs: none of the import's classes load as the server starts. */
    private class History(
        private val folder: java.nio.file.Path,
        private val drivers: Drivers,
        private val loader: ClassLoader,
        private val platform: BukkitPlatform,
        private val server: BukkitRewindServer,
    ) : () -> co.xenastudios.xrewind.HistoryImport {
        override fun invoke(): co.xenastudios.xrewind.HistoryImport = CoreProtectImport(folder.parent, drivers, loader, platform.log, ItemBytes(server))
    }

    private class ItemBytes(private val server: BukkitRewindServer) : (String) -> ByteArray? {
        override fun invoke(id: String): ByteArray? = server.itemBytes(id)
    }

    internal companion object {
        const val BSTATS = 35410

        /** The running plugin and its server, for the Bukkit helpers of the API (`ApiItems`). */
        @Volatile
        var running: Pair<XRewindPlugin, BukkitRewindServer>? = null
    }
}
