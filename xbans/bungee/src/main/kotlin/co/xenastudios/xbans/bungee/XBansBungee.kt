package co.xenastudios.xbans.bungee

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.javaUuid
import co.xenastudios.core.api.pendingOf
import co.xenastudios.core.api.toPlayerRef
import co.xenastudios.core.bungee.BungeePlatform
import co.xenastudios.core.common.family.PlayerCache
import co.xenastudios.core.common.family.PlayerStore
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.scheduler.later
import co.xenastudios.core.common.text.Component
import co.xenastudios.core.jvm.admin.MclogsPaste
import co.xenastudios.core.jvm.db.Drivers
import co.xenastudios.core.jvm.i18n.LangFolder
import co.xenastudios.core.loader.PluginEntry
import co.xenastudios.core.loader.bungee.BungeeBootstrap
import co.xenastudios.xbans.BansServer
import co.xenastudios.xbans.FileImports
import co.xenastudios.xbans.JvmDatabase
import co.xenastudios.xbans.JvmFiles
import co.xenastudios.xbans.Power
import co.xenastudios.xbans.XBansPlugin
import co.xenastudios.xbans.config.XBansConfig
import co.xenastudios.xbans.model.Entry
import net.md_5.bungee.api.chat.BaseComponent
import net.md_5.bungee.api.connection.ProxiedPlayer
import net.md_5.bungee.api.event.LoginEvent
import net.md_5.bungee.api.event.PlayerDisconnectEvent
import net.md_5.bungee.api.event.PostLoginEvent
import net.md_5.bungee.api.event.ServerConnectEvent
import net.md_5.bungee.api.event.ServerConnectedEvent
import net.md_5.bungee.api.plugin.Listener
import net.md_5.bungee.event.EventHandler
import net.md_5.bungee.event.EventPriority
import java.net.InetSocketAddress
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

/**
 * xBans on BungeeCord and Waterfall: banned players turned away at the network's door, before
 * they reach any server, and taken off the network when a ban or kick is made anywhere on it
 * (decisions/2026-11-26-xbans-2.md), as on Velocity. Its own command is `/xbansproxy` (`/xbp`).
 */
public class XBansBungee(private val bootstrap: BungeeBootstrap) : PluginEntry, Listener {
    private var platform: BungeePlatform? = null
    private var xbans: XBansPlugin? = null
    private var server: BungeeBansServer? = null

    override fun enable() {
        val started = System.nanoTime()
        val platform = BungeePlatform.create(bootstrap, development = java.lang.Boolean.getBoolean("xena.development"))
        val family = platform.family
        val cache = PlayerCache(family.board, PlayerStore.InFile(family.folder), platform.scheduler, platform.clock, platform.log, XBansPlugin.PLUGIN_ID)
        platform.record(cache)
        val folder = bootstrap.dataFolder.toPath()
        val files = JvmFiles(folder, family, LangFolder.shipped(javaClass.classLoader, JvmFiles.LANGUAGES), platform.log)
        val config = files.config()
        val drivers = Drivers(javaClass.classLoader, platform.driverFetcher())
        val plugins = folder.toAbsolutePath().parent
        val server = BungeeBansServer(platform)
        val xbans = XBansPlugin(
            platform, server, files, cache, platform.players, cache,
            opener = JvmDatabase(family, folder, plugins.resolve("Xena"), drivers, platform.log),
            registrar = platform.commands,
            permissionRegistrar = platform.permissions,
            about = platform::about,
            paste = if (System.getProperty("xena.debug.paste") == "false") null else MclogsPaste(),
            imports = FileImports(plugins.parent ?: plugins, plugins, drivers, platform.log, listOf("litebans", "advancedban")),
            channel = if (config.config[XBansConfig.network.enabled]) platform.proxyChannel(XBansPlugin.PLUGIN_ID) else null,
            version = bootstrap.description.version,
        )
        this.platform = platform
        this.xbans = xbans
        this.server = server
        platform.server.pluginManager.registerListener(bootstrap, this)
        platform.scheduler.run(Where.Global) { _ ->
            platform.commands.feedback = xbans.texts.feedback
            xbans.start(config)
            xbans.open()
        }
        platform.scheduler.run(Where.Async) { _ ->
            cache.load()
            val loaded = files.translations(config.config, config.problems)
            platform.scheduler.run(Where.Global) { _ -> xbans.apply(loaded) }
        }
        xbans.updates = platform.checkUpdates(XBansPlugin.PLUGIN_ID, xbans.admin.updates, XBansConfig.updates.settings(config.config)) { xbans.texts.translations }
        val millis = (System.nanoTime() - started) / 1_000_000
        platform.log.info("xBans ${xbans.version} on ${platform.name}: turning banned players away at the proxy, enabled in $millis ms")
    }

    // The network's door, last, so a plugin that allows logins later can't let a banned player back
    // in. Just after a start, it waits (without blocking BungeeCord's threads) up to 10 seconds for the bans.
    @EventHandler(priority = EventPriority.HIGHEST)
    public fun onLogin(event: LoginEvent) {
        val xbans = xbans ?: return
        if (event.isCancelled) return
        if (xbans.backend != null) return decide(event)
        val platform = platform ?: return
        event.registerIntent(bootstrap)
        val once = AtomicBoolean()
        val finish = Runnable {
            if (!once.compareAndSet(false, true)) return@Runnable
            try {
                decide(event)
            } finally {
                event.completeIntent(bootstrap)
            }
        }
        xbans.ready.whenComplete { _, _ -> finish.run() }
        platform.scheduler.later(Where.Async, XBansPlugin.WAIT_SECONDS.seconds) { _ -> finish.run() }
    }

    private fun decide(event: LoginEvent) {
        val xbans = xbans ?: return
        val server = server ?: return
        val connection = event.connection
        val ref = connection.uniqueId?.toPlayerRef() ?: return
        val name = connection.name ?: ref.toString()
        val ban: Entry? = if (xbans.backend == null) null else xbans.banOnJoin(ref, (connection.socketAddress as? InetSocketAddress)?.address?.hostAddress, null) ?: return
        event.isCancelled = true
        @Suppress("DEPRECATION")
        event.setCancelReason(*server.draw(xbans.denied(ref, name, ban)))
    }

    // A server's door: a player banned on that server's scope doesn't get onto it.
    @EventHandler(priority = EventPriority.HIGHEST)
    public fun onConnect(event: ServerConnectEvent) {
        val xbans = xbans ?: return
        val server = server ?: return
        if (event.isCancelled) return
        val player = event.player
        val ref = player.uniqueId.toPlayerRef()
        val ban = xbans.banOnJoin(ref, address(player), event.target.name) ?: return
        event.isCancelled = true
        val screen = server.draw(xbans.denied(ref, player.name, ban))
        if (player.server == null) player.disconnect(*screen) else player.sendMessage(*screen)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public fun onPostLogin(event: PostLoginEvent) {
        val xbans = xbans ?: return
        val player = event.player
        val ref = player.uniqueId.toPlayerRef()
        val ban = xbans.joined(ref, player.name, address(player))
        if (ban != null || xbans.backend == null) player.disconnect(*server!!.draw(xbans.denied(ref, player.name, ban)))
    }

    @EventHandler
    public fun onConnected(event: ServerConnectedEvent) {
        xbans?.switched(event.player.uniqueId.toPlayerRef(), event.server.info.name)
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public fun onDisconnect(event: PlayerDisconnectEvent) {
        xbans?.quit(event.player.uniqueId.toPlayerRef())
    }

    private fun address(player: ProxiedPlayer): String? = (player.socketAddress as? InetSocketAddress)?.address?.hostAddress

    override fun disable() {
        val platform = platform ?: return
        platform.server.pluginManager.unregisterListener(this)
        xbans?.let { xbans ->
            val done = CountDownLatch(1)
            platform.scheduler.run(Where.Global) { _ ->
                try {
                    xbans.stop().get(10, TimeUnit.SECONDS)
                } catch (e: Exception) {
                    platform.log.warn("Stopping, xBans' database didn't finish saving in time: $e")
                } finally {
                    done.countDown()
                }
            }
            done.await(15, TimeUnit.SECONDS)
        }
        platform.close()
        this.platform = null
        xbans = null
        server = null
    }
}

/** xBans' [BansServer] on BungeeCord: the network's players, their addresses and permissions, disconnects from the whole network. */
internal class BungeeBansServer(private val platform: BungeePlatform) : BansServer {
    private val network = platform.server
    private val zone = ZoneId.systemDefault()

    override val proxy: Boolean get() = true

    fun draw(component: Component): Array<BaseComponent> = platform.text.renderer.draw(component).players

    private fun player(ref: PlayerRef): ProxiedPlayer? = network.getPlayer(ref.javaUuid)

    override fun online(): List<PlayerRef> = network.players.map { it.uniqueId.toPlayerRef() }

    override fun isOnline(player: PlayerRef): Boolean = player(player) != null

    override fun name(player: PlayerRef): String? = player(player)?.name

    override fun address(player: PlayerRef): String? = (player(player)?.socketAddress as? InetSocketAddress)?.address?.hostAddress

    override fun send(player: PlayerRef, message: Component) {
        player(player)?.let { platform.text.send(it, message) }
    }

    override fun console(message: Component) {
        platform.text.send(network.console, message)
    }

    override fun kick(player: PlayerRef, screen: Component, quiet: Boolean) {
        player(player)?.disconnect(*draw(screen))
    }

    // BungeeCord's checks go through PermissionCheckEvent, where the platform puts the defaults.
    override fun hasPermission(player: PlayerRef, node: String): Boolean = player(player)?.hasPermission(node) ?: false

    // One node at a time: 1 to 100, then the round numbers to 1,000.
    override fun weight(player: PlayerRef): Int {
        val online = player(player) ?: return 0
        val granted = online.permissions
        for (node in granted) {
            if (node.startsWith(Power.WEIGHT_PREFIX)) node.substring(Power.WEIGHT_PREFIX.length).toIntOrNull()?.let { return maxOf(it, best(online)) }
        }
        return best(online)
    }

    private fun best(online: ProxiedPlayer): Int {
        for (n in WEIGHTS) if (online.hasPermission(Power.WEIGHT_PREFIX + n)) return n
        return 0
    }

    override fun dispatch(line: String) {
        network.pluginManager.dispatchCommand(network.console, line)
    }

    override fun commandNames(label: String): List<String>? = null

    override fun lookup(name: String): Pending<Pair<PlayerRef, String>?> = pendingOf(network.getPlayer(name)?.let { it.uniqueId.toPlayerRef() to it.name })

    override fun utcOffsetSeconds(epochMillis: Long): Int = zone.rules.getOffset(java.time.Instant.ofEpochMilli(epochMillis)).totalSeconds

    companion object {
        val WEIGHTS: IntArray = ((1000 downTo 101 step 50) + (100 downTo 1)).toIntArray()
    }
}
