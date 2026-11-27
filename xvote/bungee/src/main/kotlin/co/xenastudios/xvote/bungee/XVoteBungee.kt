package co.xenastudios.xvote.bungee

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.javaUuid
import co.xenastudios.core.api.toPlayerRef
import co.xenastudios.core.bungee.BungeePlatform
import co.xenastudios.core.common.family.PlayerCache
import co.xenastudios.core.common.family.PlayerStore
import co.xenastudios.core.common.scheduler.Task
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.text.Component
import co.xenastudios.core.jvm.admin.MclogsPaste
import co.xenastudios.core.jvm.db.Drivers
import co.xenastudios.core.jvm.i18n.LangFolder
import co.xenastudios.core.loader.PluginEntry
import co.xenastudios.core.loader.bungee.BungeeBootstrap
import co.xenastudios.xvote.FileImports
import co.xenastudios.xvote.JvmDatabase
import co.xenastudios.xvote.JvmFiles
import co.xenastudios.xvote.JvmZones
import co.xenastudios.xvote.VoteServer
import co.xenastudios.xvote.XVotePlugin
import co.xenastudios.xvote.config.XVoteConfig
import co.xenastudios.xvote.receiver.ReceiverHost
import net.md_5.bungee.api.connection.ProxiedPlayer
import net.md_5.bungee.api.event.PlayerDisconnectEvent
import net.md_5.bungee.api.event.PostLoginEvent
import net.md_5.bungee.api.event.ServerConnectedEvent
import net.md_5.bungee.api.plugin.Listener
import net.md_5.bungee.event.EventHandler
import net.md_5.bungee.event.EventPriority
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * xVote on BungeeCord and Waterfall: the network's one receiver, as on Velocity ([co.xenastudios.xvote]'s
 * decision, decisions/2026-11-27-xvote-2.md). Its own command is `/xvoteproxy` (`/xvp`). Made on the
 * plugin's own thread right after enable.
 */
public class XVoteBungee(private val bootstrap: BungeeBootstrap) : PluginEntry, Listener {
    private var platform: BungeePlatform? = null

    @Volatile
    private var xvote: XVotePlugin? = null

    override fun enable() {
        val started = System.nanoTime()
        val platform = BungeePlatform.create(bootstrap, development = java.lang.Boolean.getBoolean("xena.development"))
        val family = platform.family
        val cache = PlayerCache(family.board, PlayerStore.InFile(family.folder), platform.scheduler, platform.clock, platform.log, XVotePlugin.PLUGIN_ID)
        platform.record(cache)
        this.platform = platform
        platform.server.pluginManager.registerListener(bootstrap, this)
        platform.scheduler.run(Where.Global, action = Make(platform, cache))
        val millis = (System.nanoTime() - started) / 1_000_000
        platform.log.info("xVote ${bootstrap.description.version} on ${platform.name}: receiving the network's votes, enabled in $millis ms")
    }

    // A class, not a lambda (decisions/2026-11-25-a-lighter-core.md).
    private inner class Make(private val platform: BungeePlatform, private val cache: PlayerCache) : (Task) -> Unit {
        override fun invoke(task: Task) {
            xvote = try {
                make(platform, cache)
            } catch (e: Throwable) {
                platform.log.error("xVote couldn't start.", e)
                return
            }
            for (player in platform.server.players) {
                xvote?.joined(player.uniqueId.toPlayerRef(), player.name)
                player.server?.info?.name?.let { xvote?.switched(player.uniqueId.toPlayerRef(), it) }
            }
        }
    }

    private fun make(platform: BungeePlatform, cache: PlayerCache): XVotePlugin {
        val family = platform.family
        val folder = bootstrap.dataFolder.toPath()
        val files = JvmFiles(folder, family, LangFolder.shipped(javaClass.classLoader, JvmFiles.LANGUAGES), platform.log)
        val config = files.config()
        val drivers = Drivers(javaClass.classLoader, platform.driverFetcher())
        val plugins = folder.toAbsolutePath().parent
        var made: XVotePlugin? = null
        val receiver = ReceiverHost(folder, { vote -> made?.accept(vote) }, platform.log, "plugins/${folder.fileName}/rsa/public.key", plugins, files::useReceiver, "xvoteproxy")
        val xvote = XVotePlugin(
            platform, BungeeVoteServer(platform, cache), files, cache, platform.players, cache,
            opener = JvmDatabase(family, folder, plugins.resolve("Xena"), drivers, platform.log),
            registrar = platform.commands,
            permissionRegistrar = platform.permissions,
            about = platform::about,
            paste = if (System.getProperty("xena.debug.paste") == "false") null else MclogsPaste(),
            receiver = receiver,
            imports = FileImports(plugins, folder, drivers, platform.log, files::useReceiver),
            channel = if (config.config[XVoteConfig.network.enabled]) platform.proxyChannel(XVotePlugin.PLUGIN_ID) else null,
            zones = JvmZones,
            version = bootstrap.description.version,
        )
        made = xvote
        platform.commands.feedback = xvote.texts.feedback
        xvote.start(config)
        xvote.open()
        platform.scheduler.run(Where.Async) { _ ->
            cache.load()
            val loaded = files.translations(config.config, config.problems)
            platform.scheduler.run(Where.Global) { _ -> xvote.apply(loaded) }
        }
        xvote.updates = platform.checkUpdates(XVotePlugin.PLUGIN_ID, xvote.admin.updates, XVoteConfig.updates.settings(config.config)) { xvote.texts.translations }
        return xvote
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public fun onPostLogin(event: PostLoginEvent) {
        xvote?.joined(event.player.uniqueId.toPlayerRef(), event.player.name)
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public fun onConnected(event: ServerConnectedEvent) {
        xvote?.switched(event.player.uniqueId.toPlayerRef(), event.server.info.name)
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public fun onDisconnect(event: PlayerDisconnectEvent) {
        xvote?.quit(event.player.uniqueId.toPlayerRef(), event.player.name)
    }

    override fun disable() {
        val platform = platform ?: return
        platform.server.pluginManager.unregisterListener(this)
        val done = CountDownLatch(1)
        platform.scheduler.run(Where.Global) { _ ->
            try {
                xvote?.stop()?.get(10, TimeUnit.SECONDS)
            } catch (e: Exception) {
                platform.log.warn("Stopping, xVote's database didn't finish in time: $e")
            } finally {
                done.countDown()
            }
        }
        done.await(15, TimeUnit.SECONDS)
        platform.close()
        this.platform = null
        xvote = null
    }
}

/** xVote's [VoteServer] on BungeeCord: the network's players; no items, sounds or console rewards, which are the servers'. */
internal class BungeeVoteServer(private val platform: BungeePlatform, private val cache: PlayerCache) : VoteServer {
    private val network = platform.server

    override val proxy: Boolean get() = true

    private fun player(ref: PlayerRef): ProxiedPlayer? = network.getPlayer(ref.javaUuid)

    override fun online(): List<PlayerRef> = network.players.map { it.uniqueId.toPlayerRef() }

    override fun name(player: PlayerRef): String? = player(player)?.name

    override fun send(player: PlayerRef, message: Component) {
        player(player)?.let { platform.text.send(it, message) }
    }

    override fun console(message: Component) {
        platform.text.send(network.console, message)
    }

    override fun hasPermission(player: PlayerRef, node: String): Boolean = player(player)?.hasPermission(node) ?: false

    override fun dispatch(line: String) {
        network.pluginManager.dispatchCommand(network.console, line)
    }

    override fun known(name: String): Pair<PlayerRef, String>? = cache.find(name)?.let { it.player to it.name }
}
