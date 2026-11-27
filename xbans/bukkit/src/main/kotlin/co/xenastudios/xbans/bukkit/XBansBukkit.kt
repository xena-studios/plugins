package co.xenastudios.xbans.bukkit

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.bukkit.BukkitPlatform
import co.xenastudios.core.common.family.PlayerCache
import co.xenastudios.core.common.family.PlayerStore
import co.xenastudios.core.common.scheduler.Task
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.jvm.admin.MclogsPaste
import co.xenastudios.core.jvm.db.Drivers
import co.xenastudios.core.jvm.i18n.LangFolder
import co.xenastudios.core.loader.PluginEntry
import co.xenastudios.xbans.FileImports
import co.xenastudios.xbans.JvmDatabase
import co.xenastudios.xbans.JvmFiles
import co.xenastudios.xbans.Loaded
import co.xenastudios.xbans.XBansPlugin
import co.xenastudios.xbans.ZChatMutes
import co.xenastudios.xbans.api.XBansApi
import co.xenastudios.xbans.bukkit.modern.Anvils
import co.xenastudios.xbans.bukkit.modern.PaperChat
import co.xenastudios.xbans.bukkit.modern.PaperValidate
import co.xenastudios.xbans.bukkit.modern.Tell
import co.xenastudios.xbans.config.XBansConfig
import co.xenastudios.xbans.model.Entry
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.server.PluginEnableEvent
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin
import java.util.concurrent.TimeUnit

/**
 * xBans on Paper, Folia, Spigot and CraftBukkit, 1.8.8 on: the shared plugin on platform-bukkit,
 * loaded by the core's Java shim once the stdlib is there. It wires the shared code to the server:
 *
 * - `config.yml` is read as the plugin enables, so its commands are the server's from the start;
 *   the database is opened, migrated and the bans read on an async task, never the server thread,
 *   and until they're read nobody joins (a login waits up to 10 seconds for them).
 * - Logins are checked at pre-login, as the connection finishes, and at join; chat, commands,
 *   signs, books and anvils are guarded against muted players.
 * - zChat asks xBans who's muted; the API is in the services manager, its events Bukkit events too.
 * - Behind a proxy with network mode on, the proxy layer's channel tells the proxy of a ban at once.
 */
public class XBansBukkit(private val plugin: JavaPlugin) : PluginEntry, Listener {

    private var platform: BukkitPlatform? = null
    private var xbans: XBansPlugin? = null
    private var chat: LegacyChat? = null
    private var zchat = false

    override fun enable() {
        val started = System.nanoTime()
        val platform = BukkitPlatform.create(plugin, development = java.lang.Boolean.getBoolean("xena.development"))
        val family = platform.family
        val cache = PlayerCache(family.board, PlayerStore.InFile(family.folder), platform.scheduler, platform.clock, platform.log, XBansPlugin.PLUGIN_ID)
        platform.record(cache)
        val server = BukkitBansServer(platform)
        val folder = plugin.dataFolder.toPath()
        val files = JvmFiles(folder, family, LangFolder.shipped(plugin.javaClass.classLoader, JvmFiles.LANGUAGES), platform.log)
        val config = files.config()
        val drivers = Drivers(plugin.javaClass.classLoader, platform.driverFetcher())
        val plugins = folder.toAbsolutePath().parent
        val xbans = XBansPlugin(
            platform, server, files, cache, platform.players, cache,
            opener = JvmDatabase(family, folder, plugins.resolve("Xena"), drivers, platform.log),
            registrar = platform.commands,
            permissionRegistrar = platform.permissions,
            about = platform::about,
            // -Dxena.debug.paste=false writes debug reports to the plugin's folder instead (boot tests do).
            paste = if (System.getProperty("xena.debug.paste") == "false") null else Mclogs,
            imports = FileImports(plugins.parent ?: plugins, plugins, drivers, platform.log),
            // Behind a proxy, read at startup: the channel opens once.
            channel = if (config.config[XBansConfig.network.enabled]) platform.proxyChannel(XBansPlugin.PLUGIN_ID) else null,
            version = plugin.description.version,
        )
        this.platform = platform
        this.xbans = xbans
        platform.commands.feedback = xbans.texts.feedback
        xbans.beforeFirstEvent = EventBridge(plugin.server, xbans.bus)
        plugin.server.servicesManager.register(XBansApi::class.java, xbans, plugin, ServicePriority.Normal)

        // Enforcement can't be turned off: it's what the plugin is for.
        val paper = Modernity(plugin)
        val mutes = MuteGuard(xbans, server)
        val pm = plugin.server.pluginManager
        pm.registerEvents(Gate(xbans, server, paper.validates), plugin)
        pm.registerEvents(mutes, plugin)
        pm.registerEvents(this, plugin)
        val tell = Telling(xbans, mutes, server)
        if (paper.chat) pm.registerEvents(PaperChat(xbans, tell), plugin)
        if (paper.validates) pm.registerEvents(PaperValidate(xbans, tell), plugin)
        if (paper.anvils) pm.registerEvents(Anvils(xbans), plugin)
        chat = LegacyChat(xbans, plugin, mutes).also { it.on() }

        xbans.start(config)
        xbans.open()
        platform.scheduler.run(Where.Async, action = Steps(platform, xbans, cache, files, config))
        xbans.updates = platform.checkUpdates(XBansPlugin.PLUGIN_ID, xbans.admin.updates, XBansConfig.updates.settings(config.config)) {
            xbans.texts.translations
        }
        platform.metrics(BSTATS)
        val millis = (System.nanoTime() - started) / 1_000_000
        platform.log.info("xBans ${xbans.version} on ${platform.name}: ${platform.threading} threading, text as ${platform.text}, enabled in $millis ms")
    }

    override fun disable() {
        val platform = platform ?: return
        HandlerList.unregisterAll(this as Listener)
        chat?.off()
        plugin.server.servicesManager.unregisterAll(plugin)
        // What's queued is saved before the server stops: the database closes once it's written.
        try {
            xbans?.stop()?.get(15, TimeUnit.SECONDS)
        } catch (e: Exception) {
            platform.log.warn("Stopping, xBans' database didn't finish saving in time: $e")
        }
        platform.close()
        this.platform = null
        xbans = null
        chat = null
    }

    @EventHandler
    public fun onPluginEnable(event: PluginEnableEvent) {
        if (event.plugin.name == "zChat") xbans?.let(::hookZChat)
    }

    private fun hookZChat(xbans: XBansPlugin) {
        if (zchat) return
        val services = plugin.server.servicesManager
        val type = services.knownServices.firstOrNull { it.name == "co.xenastudios.zchat.api.ZChatApi" } ?: return
        val api = services.getRegistration(type)?.provider ?: return
        zchat = ZChatMutes.register(api, xbans, xbans.log)
    }

    // mclo.gs, made with the first /xbans debug upload: its class isn't loaded before.
    private object Mclogs : co.xenastudios.core.common.admin.PasteService {
        override val name: String get() = "mclo.gs"

        override fun upload(text: String) = MclogsPaste().upload(text)
    }

    /** What this server has of Paper's, found once: each its own listener class, loaded only if it's there. */
    private class Modernity(private val owner: JavaPlugin) {
        private fun has(name: String) = try {
            Class.forName(name, false, owner.javaClass.classLoader)
            true
        } catch (e: ClassNotFoundException) {
            false
        } catch (e: LinkageError) {
            false
        }

        val chat = has("io.papermc.paper.event.player.AsyncChatEvent")
        val validates = has("io.papermc.paper.event.connection.PlayerConnectionValidateLoginEvent") && has("io.papermc.paper.connection.PlayerConfigurationConnection")
        val anvils = has("org.bukkit.event.inventory.PrepareAnvilEvent") && try {
            org.bukkit.inventory.AnvilInventory::class.java.getMethod("getRenameText")
            true
        } catch (e: NoSuchMethodException) {
            false
        }
    }

    /** What Paper's listeners ask of the adapter. */
    private class Telling(private val xbans: XBansPlugin, private val mutes: MuteGuard, private val server: BukkitBansServer) : Tell {
        override fun tell(player: Player) = mutes.tell(player)

        override fun screen(player: PlayerRef, name: String, ban: Entry?): String = server.legacy(xbans.denied(player, name, ban))
    }

    /**
     * What happens after enable, as one class rather than a lambda each. On an async task first:
     * the player cache and the lang files read. Then on the global thread: the lang files applied,
     * and zChat if it's already on.
     */
    private inner class Steps(
        private val platform: BukkitPlatform,
        private val xbans: XBansPlugin,
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
                xbans.apply(read)
                if (plugin.server.pluginManager.isPluginEnabled("zChat")) hookZChat(xbans)
            }
        }
    }

    private companion object {
        // bstats.org/plugin/bukkit/xBans/35418, 1.x's.
        const val BSTATS = 35418
    }
}
