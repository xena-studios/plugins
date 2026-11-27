package co.xenastudios.xvote.velocity

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.javaUuid
import co.xenastudios.core.api.toPlayerRef
import co.xenastudios.core.common.family.PlayerCache
import co.xenastudios.core.common.family.PlayerStore
import co.xenastudios.core.common.scheduler.Task
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.text.Component
import co.xenastudios.core.jvm.admin.MclogsPaste
import co.xenastudios.core.jvm.db.Drivers
import co.xenastudios.core.jvm.i18n.LangFolder
import co.xenastudios.core.loader.PluginEntry
import co.xenastudios.core.loader.velocity.VelocityBootstrap
import co.xenastudios.core.velocity.VelocityPlatform
import co.xenastudios.xvote.DeferredImports
import co.xenastudios.xvote.JvmDatabase
import co.xenastudios.xvote.JvmFiles
import co.xenastudios.xvote.JvmZones
import co.xenastudios.xvote.VoteServer
import co.xenastudios.xvote.XVotePlugin
import co.xenastudios.xvote.config.XVoteConfig
import co.xenastudios.xvote.receiver.ReceiverHost
import com.velocitypowered.api.event.EventHandler
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.connection.PostLoginEvent
import com.velocitypowered.api.event.player.ServerConnectedEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * xVote on Velocity: the network's one receiver (decisions/2026-11-27-xvote-2.md). It receives every
 * vote, stores and counts it in the database the servers share, and tells the server the player is
 * on, which pays it; a player who's offline is paid by the first server they join. It never pays
 * anything itself. Its own command is `/xvoteproxy` (`/xvp`), so a player's `/xvote` still reaches
 * their server. The plugin is made on its own thread right after enable, as xBans' is
 * (decisions/2026-11-27-xbans-on-a-proxy-starts-on-its-own-thread.md): a vote arriving before it's
 * made can't, as the receiver starts only once the database is open.
 */
public class XVoteVelocity(private val bootstrap: VelocityBootstrap) : PluginEntry {
    private var platform: VelocityPlatform? = null
    private val handlers = ArrayList<Pair<Class<*>, EventHandler<*>>>()

    @Volatile
    private var xvote: XVotePlugin? = null

    override fun enable() {
        val started = System.nanoTime()
        val platform = VelocityPlatform.create(bootstrap, development = java.lang.Boolean.getBoolean("xena.development"))
        val family = platform.family
        val cache = PlayerCache(family.board, PlayerStore.InFile(family.folder), platform.scheduler, platform.clock, platform.log, XVotePlugin.PLUGIN_ID)
        platform.record(cache)
        this.platform = platform
        register(platform, PostLoginEvent::class.java, Joined(this))
        register(platform, ServerConnectedEvent::class.java, Switched(this))
        register(platform, DisconnectEvent::class.java, Quit(this))
        platform.scheduler.run(Where.Global, action = Make(platform, cache))
        val millis = (System.nanoTime() - started) / 1_000_000
        platform.log.info("xVote ${platform.container.description.version.orElse(XVotePlugin.VERSION)} on ${platform.name}: receiving the network's votes, enabled in $millis ms")
    }

    // A class, not a lambda (decisions/2026-11-25-a-lighter-core.md).
    private inner class Make(private val platform: VelocityPlatform, private val cache: PlayerCache) : (Task) -> Unit {
        override fun invoke(task: Task) {
            xvote = try {
                make(platform, cache)
            } catch (e: Throwable) {
                platform.log.error("xVote couldn't start.", e)
                return
            }
            // Players who joined before it was made.
            for (player in platform.server.allPlayers) {
                xvote?.joined(player.uniqueId.toPlayerRef(), player.username)
                player.currentServer.ifPresent { xvote?.switched(player.uniqueId.toPlayerRef(), it.serverInfo.name) }
            }
        }
    }

    private fun make(platform: VelocityPlatform, cache: PlayerCache): XVotePlugin {
        val family = platform.family
        val folder = bootstrap.dataFolder()
        val files = JvmFiles(folder, family, LangFolder.shipped(javaClass.classLoader, JvmFiles.LANGUAGES), platform.log)
        val config = files.config()
        val drivers = Drivers(javaClass.classLoader, platform.driverFetcher())
        val plugins = folder.toAbsolutePath().parent
        val receiver = ReceiverHost(folder, platform.log, "plugins/${folder.fileName}/rsa/public.key", plugins, files, "xvoteproxy")
        val xvote = XVotePlugin(
            platform, VelocityVoteServer(platform, cache), files, cache, platform.players, cache,
            opener = JvmDatabase(family, folder, plugins.resolve("Xena"), drivers, platform.log),
            registrar = platform.commands,
            permissionRegistrar = platform.permissions,
            about = platform::about,
            paste = if (System.getProperty("xena.debug.paste") == "false") null else MclogsPaste(),
            receiver = receiver,
            imports = DeferredImports(plugins, folder, drivers, platform.log, files),
            channel = if (config.config[XVoteConfig.network.enabled]) platform.proxyChannel(XVotePlugin.PLUGIN_ID) else null,
            zones = JvmZones,
            version = platform.container.description.version.orElse(XVotePlugin.VERSION),
        )
        receiver.plugin = xvote
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

    private fun <E : Any> register(platform: VelocityPlatform, type: Class<E>, handler: EventHandler<E>) {
        platform.server.eventManager.register(platform.bootstrap, type, LAST, handler)
        handlers += type to handler
    }

    private class Joined(private val entry: XVoteVelocity) : EventHandler<PostLoginEvent> {
        override fun execute(event: PostLoginEvent) {
            entry.xvote?.joined(event.player.uniqueId.toPlayerRef(), event.player.username)
        }
    }

    private class Switched(private val entry: XVoteVelocity) : EventHandler<ServerConnectedEvent> {
        override fun execute(event: ServerConnectedEvent) {
            entry.xvote?.switched(event.player.uniqueId.toPlayerRef(), event.server.serverInfo.name)
        }
    }

    private class Quit(private val entry: XVoteVelocity) : EventHandler<DisconnectEvent> {
        override fun execute(event: DisconnectEvent) {
            entry.xvote?.quit(event.player.uniqueId.toPlayerRef(), event.player.username)
        }
    }

    override fun disable() {
        val platform = platform ?: return
        for ((_, handler) in handlers) platform.server.eventManager.unregister(platform.bootstrap, handler)
        handlers.clear()
        val done = CountDownLatch(1)
        // On the plugin's thread, so after Make: whatever it made is stopped.
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

    private companion object {
        // Velocity's numeric priority for PostOrder.LAST.
        const val LAST: Short = Short.MIN_VALUE
    }
}

/** xVote's [VoteServer] on Velocity: the network's players; no items, sounds or console rewards, which are the servers'. */
internal class VelocityVoteServer(private val platform: VelocityPlatform, private val cache: PlayerCache) : VoteServer {
    private val network = platform.server

    override val proxy: Boolean get() = true

    private fun player(ref: PlayerRef) = network.getPlayer(ref.javaUuid).orElse(null)

    override fun online(): List<PlayerRef> = network.allPlayers.map { it.uniqueId.toPlayerRef() }

    override fun name(player: PlayerRef): String? = player(player)?.username

    override fun send(player: PlayerRef, message: Component) {
        player(player)?.sendMessage(platform.text.renderer.draw(message))
    }

    override fun console(message: Component) {
        network.consoleCommandSource.sendMessage(platform.text.renderer.draw(message))
    }

    override fun hasPermission(player: PlayerRef, node: String): Boolean = player(player)?.let { platform.permissions.has(it, node) } ?: false

    override fun dispatch(line: String) {
        network.commandManager.executeAsync(network.consoleCommandSource, line)
    }

    override fun known(name: String): Pair<PlayerRef, String>? = cache.find(name)?.let { it.player to it.name }
}
