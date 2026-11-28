package co.xenastudios.xlimits.bukkit

import co.xenastudios.core.api.event.EventPriority
import co.xenastudios.core.bukkit.BukkitPlatform
import co.xenastudios.core.bukkit.world.world
import co.xenastudios.core.common.family.PlayerCache
import co.xenastudios.core.common.family.PlayerStore
import co.xenastudios.core.common.scheduler.Task
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.jvm.admin.MclogsPaste
import co.xenastudios.core.jvm.i18n.LangFolder
import co.xenastudios.core.loader.PluginEntry
import co.xenastudios.xlimits.JvmFiles
import co.xenastudios.xlimits.LimitImporter
import co.xenastudios.xlimits.Loaded
import co.xenastudios.xlimits.XLimitsPlugin
import co.xenastudios.xlimits.api.XLimitsApi
import co.xenastudios.xlimits.config.XLimitsConfig
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin

/**
 * xLimits on Paper, Folia, Spigot and CraftBukkit, 1.13 on: the shared plugin on platform-bukkit,
 * loaded by the core's Java shim once the stdlib is there.
 *
 * - `config.yml` is read as the plugin enables, so the limits are on before anyone joins; the
 *   lang files follow on an async task.
 * - Spawns, blocks, tames and deaths come from the core's world hooks, at the HIGH priority so
 *   protection plugins (LOWEST to NORMAL) have refused first and a logger (MONITOR) sees what
 *   xLimits refused as refused.
 * - The API's events as Bukkit events too, and the API in the services manager.
 */
public class XLimitsBukkit(private val plugin: JavaPlugin) : PluginEntry {

    private var platform: BukkitPlatform? = null
    private var xlimits: XLimitsPlugin? = null

    override fun enable() {
        val started = System.nanoTime()
        val platform = BukkitPlatform.create(plugin, development = java.lang.Boolean.getBoolean("xena.development"))
        val family = platform.family
        // The family's player cache: each player's client language, for the messages.
        val cache = PlayerCache(family.board, PlayerStore.InFile(family.folder), platform.scheduler, platform.clock, platform.log, XLimitsPlugin.PLUGIN_ID)
        platform.record(cache)
        val server = BukkitLimitsServer(plugin, platform, platform.world(EventPriority.HIGH))
        val folder = plugin.dataFolder.toPath()
        val files = JvmFiles(folder, family, LangFolder.shipped(plugin.javaClass.classLoader, JvmFiles.LANGUAGES), platform.log)
        val xlimits = XLimitsPlugin(
            platform, server, files, cache, platform.players,
            registrar = platform.commands,
            permissionRegistrar = platform.permissions,
            about = platform::about,
            // -Dxena.debug.paste=false writes debug reports to the plugin's folder instead (boot tests do).
            paste = if (System.getProperty("xena.debug.paste") == "false") null else Mclogs,
            importers = Importers(folder.parent),
            version = plugin.description.version,
        )
        this.platform = platform
        this.xlimits = xlimits
        platform.commands.feedback = xlimits.texts.feedback
        xlimits.beforeFirstEvent = EventBridge(plugin.server, xlimits.bus)
        plugin.server.servicesManager.register(XLimitsApi::class.java, xlimits, plugin, ServicePriority.Normal)

        val config = files.config()
        xlimits.start(config)
        platform.scheduler.run(Where.Async, action = Steps(platform, xlimits, cache, files, config))
        xlimits.updates = platform.checkUpdates(XLimitsPlugin.PLUGIN_ID, xlimits.admin.updates, XLimitsConfig.updates.settings(config.config)) {
            xlimits.texts.translations
        }
        val millis = (System.nanoTime() - started) / 1_000_000
        platform.log.info("xLimits ${xlimits.version} on ${platform.name}: ${platform.threading} threading, text as ${platform.text}, ${xlimits.limits().size} limits on, enabled in $millis ms")
    }

    override fun disable() {
        val platform = platform ?: return
        plugin.server.servicesManager.unregisterAll(plugin)
        xlimits?.stop()
        platform.close()
        this.platform = null
        xlimits = null
    }

    // FarmControl's and Insights' folders, beside this plugin's.
    private class Importers(private val plugins: java.nio.file.Path) : (XLimitsPlugin) -> List<co.xenastudios.core.common.admin.Importer<*>> {
        override fun invoke(plugin: XLimitsPlugin) = LimitImporter.all(plugins, plugin)
    }

    // mclo.gs, made with the first /xlimits debug upload.
    private object Mclogs : co.xenastudios.core.common.admin.PasteService {
        override val name: String get() = "mclo.gs"

        override fun upload(text: String) = MclogsPaste().upload(text)
    }

    /** After enable, on an async task: the player cache and the lang files read. Then on the global thread: applied. */
    private class Steps(
        private val platform: BukkitPlatform,
        private val xlimits: XLimitsPlugin,
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
                xlimits.apply(read)
            }
        }
    }
}
