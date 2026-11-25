package co.xenastudios.zchat.bukkit

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.bukkit.BukkitPlatform
import co.xenastudios.core.bukkit.online
import co.xenastudios.core.bukkit.ref
import co.xenastudios.core.common.family.PlayerCache
import co.xenastudios.core.common.family.PlayerStore
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.scheduler.now
import co.xenastudios.core.jvm.admin.MclogsPaste
import co.xenastudios.core.jvm.i18n.LangFolder
import co.xenastudios.core.loader.PluginEntry
import co.xenastudios.zchat.JvmFiles
import co.xenastudios.zchat.ZChatPlugin
import co.xenastudios.zchat.api.ZChatApi
import co.xenastudios.zchat.api.bukkit.BukkitChatMessageEvent
import co.xenastudios.zchat.api.bukkit.BukkitChatMessageSentEvent
import co.xenastudios.zchat.api.bukkit.BukkitFilterMatchEvent
import co.xenastudios.zchat.api.bukkit.BukkitMentionEvent
import co.xenastudios.zchat.api.bukkit.BukkitPrivateMessageEvent
import co.xenastudios.zchat.api.event.ChatMessageEvent
import co.xenastudios.zchat.api.event.ChatMessageSentEvent
import co.xenastudios.zchat.api.event.FilterMatchEvent
import co.xenastudios.zchat.api.event.MentionEvent
import co.xenastudios.zchat.api.event.PrivateMessageEvent
import co.xenastudios.zchat.bukkit.modern.legacyChoices
import co.xenastudios.zchat.config.ZChatConfig
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin

/**
 * zChat on Paper, Folia, Spigot and CraftBukkit, 1.8.8 on: the shared plugin on platform-bukkit,
 * loaded by the core's Java shim once the stdlib is there. It wires the shared code to the server:
 *
 * - `config.yml` is read here, as the plugin enables, so its commands are registered with the
 *   server's own; the family's settings, `lang/` and the remembered choices off the server thread.
 * - Chat comes through the platform's chat bridge, at `LOW` (Paper's `AsyncChatEvent` on Paper
 *   1.17+ and Folia, Bukkit's `AsyncPlayerChatEvent` elsewhere).
 * - Joins and quits tell zChat who's online; 3.x's choices kept in a player's persistent data
 *   (1.14 on) are brought in as they join.
 * - The API's events are fired as Bukkit events too, and the API is in the services manager.
 */
public class ZChatBukkit(private val plugin: JavaPlugin) : PluginEntry, Listener {

    private var platform: BukkitPlatform? = null
    private var zchat: ZChatPlugin? = null
    private var server: BukkitChatServer? = null
    private var persistentData = false

    override fun enable() {
        val started = System.nanoTime()
        val platform = BukkitPlatform.create(plugin, development = java.lang.Boolean.getBoolean("xena.development"))
        val family = platform.family
        val cache = PlayerCache(family.board, PlayerStore.InFile(family.folder), platform.scheduler, platform.clock, platform.log, ZChatPlugin.PLUGIN_ID)
        platform.record(cache)
        val server = BukkitChatServer(plugin, platform)
        val files = JvmFiles(plugin.dataFolder.toPath(), family, LangFolder.shipped(plugin.javaClass.classLoader, JvmFiles.LANGUAGES), platform.log)
        val zchat = ZChatPlugin(
            platform, server, files, cache, { priority -> platform.chat(priority) },
            registrar = platform.commands,
            permissionRegistrar = platform.permissions,
            lookup = cache.asLookup(),
            about = platform::about,
            // -Dxena.debug.paste=false writes debug reports to the plugin's folder instead (boot tests do).
            paste = if (System.getProperty("xena.debug.paste") == "false") null else MclogsPaste(),
            version = plugin.description.version,
        )
        this.platform = platform
        this.zchat = zchat
        this.server = server
        persistentData = platform.server.atLeast(1, 14)
        platform.commands.feedback = zchat.texts.feedback
        bridge(zchat)
        plugin.server.servicesManager.register(ZChatApi::class.java, zchat, plugin, ServicePriority.Normal)
        plugin.server.pluginManager.registerEvents(this, plugin)
        plugin.server.pluginManager.registerEvents(server, plugin)

        // The config now, so the commands are the server's from the start; the rest off the thread.
        val config = files.config()
        zchat.updates = platform.checkUpdates(ZChatPlugin.PLUGIN_ID, zchat.admin.updates, ZChatConfig.updates.settings(config.config)) {
            zchat.texts.translations
        }
        platform.metrics(BSTATS)
        platform.scheduler.now(Where.Global) {
            zchat.start(config)
            platform.scheduler.run(Where.Async) {
                cache.load()
                zchat.settings.open()?.let(platform.log::warn)
                val loaded = files.translations(config.config, config.problems)
                platform.scheduler.run(Where.Global) {
                    zchat.apply(loaded)
                    // Anyone already online, such as after the plugin is loaded on a running server.
                    for (player in Bukkit.getOnlinePlayers()) platform.scheduler.now(Where.on(player.ref)) { joined(player) }
                }
            }
        }
        val millis = (System.nanoTime() - started) / 1_000_000
        platform.log.info("zChat ${zchat.version} on ${platform.name}: ${platform.threading} threading, text as ${platform.text}, chat through ${platform.chatPath}, enabled in $millis ms")
    }

    override fun disable() {
        val platform = platform ?: return
        HandlerList.unregisterAll(this as Listener)
        server?.let { HandlerList.unregisterAll(it) }
        zchat?.stop()
        plugin.server.servicesManager.unregisterAll(plugin)
        platform.close()
        this.platform = null
        zchat = null
        server = null
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public fun onJoin(event: PlayerJoinEvent) {
        joined(event.player)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public fun onQuit(event: PlayerQuitEvent) {
        zchat?.quit(event.player.ref)
    }

    private fun joined(player: Player) {
        val zchat = zchat ?: return
        val ref = player.ref
        if (persistentData) {
            val (hidden, spy) = legacyChoices(plugin, player)
            zchat.imported(ref, hidden, spy)
        }
        zchat.joined(ref, player.name)
    }

    private companion object {
        // bstats.org/plugin/bukkit/zChat/21533, 3.x's.
        const val BSTATS = 21533
    }

    // The API's events as Bukkit events, made only when a Bukkit plugin listens.
    private fun bridge(zchat: ZChatPlugin) {
        val bukkit = plugin.server
        fun async() = !Bukkit.isPrimaryThread()
        fun player(ref: PlayerRef?): Player? = ref?.online(bukkit)
        fun sender(ref: PlayerRef?): CommandSender? = if (ref == null) bukkit.consoleSender else player(ref)
        zchat.bus.bridge(ChatMessageEvent.TYPE) { event ->
            if (BukkitChatMessageEvent.getHandlerList().registeredListeners.isEmpty()) return@bridge
            val player = player(event.player) ?: return@bridge
            bukkit.pluginManager.callEvent(BukkitChatMessageEvent(async(), player, event))
        }
        zchat.bus.bridge(ChatMessageSentEvent.TYPE) { event ->
            if (BukkitChatMessageSentEvent.getHandlerList().registeredListeners.isEmpty()) return@bridge
            val player = player(event.player) ?: return@bridge
            bukkit.pluginManager.callEvent(BukkitChatMessageSentEvent(async(), player, event))
        }
        zchat.bus.bridge(MentionEvent.TYPE) { event ->
            if (BukkitMentionEvent.getHandlerList().registeredListeners.isEmpty()) return@bridge
            val player = player(event.mentioned) ?: return@bridge
            bukkit.pluginManager.callEvent(BukkitMentionEvent(player, player(event.sender), event))
        }
        zchat.bus.bridge(PrivateMessageEvent.TYPE) { event ->
            if (BukkitPrivateMessageEvent.getHandlerList().registeredListeners.isEmpty()) return@bridge
            val from = sender(event.sender) ?: return@bridge
            val to = if (event.network) null else sender(event.recipient)
            bukkit.pluginManager.callEvent(BukkitPrivateMessageEvent(async(), from, to, event))
        }
        zchat.bus.bridge(FilterMatchEvent.TYPE) { event ->
            if (BukkitFilterMatchEvent.getHandlerList().registeredListeners.isEmpty()) return@bridge
            val player = player(event.player) ?: return@bridge
            bukkit.pluginManager.callEvent(BukkitFilterMatchEvent(async(), player, event))
        }
    }
}
