package co.xenastudios.xbans.velocity

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.javaUuid
import co.xenastudios.core.api.toPlayerRef
import co.xenastudios.core.common.family.PlayerCache
import co.xenastudios.core.common.family.PlayerStore
import co.xenastudios.core.common.scheduler.Task
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.scheduler.later
import co.xenastudios.core.common.text.Component
import co.xenastudios.core.jvm.admin.MclogsPaste
import co.xenastudios.core.jvm.db.Drivers
import co.xenastudios.core.jvm.i18n.LangFolder
import co.xenastudios.core.loader.PluginEntry
import co.xenastudios.core.loader.velocity.VelocityBootstrap
import co.xenastudios.core.velocity.VelocityPlatform
import co.xenastudios.xbans.BansServer
import co.xenastudios.xbans.FileImports
import co.xenastudios.xbans.JvmDatabase
import co.xenastudios.xbans.JvmFiles
import co.xenastudios.xbans.Power
import co.xenastudios.xbans.XBansPlugin
import co.xenastudios.xbans.config.XBansConfig
import com.velocitypowered.api.event.EventHandler
import com.velocitypowered.api.event.EventTask
import com.velocitypowered.api.event.ResultedEvent
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.connection.LoginEvent
import com.velocitypowered.api.event.connection.PostLoginEvent
import com.velocitypowered.api.event.player.ServerConnectedEvent
import com.velocitypowered.api.event.player.ServerPreConnectEvent
import java.time.ZoneId
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/**
 * xBans on Velocity: banned players turned away at the network's door, before they reach any
 * server, and kicked off the network when a ban or kick is made anywhere on it
 * (decisions/2026-11-26-xbans-2.md). The shared plugin on platform-velocity, with the same config,
 * commands and database as a server's; the servers and the proxy share MySQL and network mode.
 * Its own command is `/xbansproxy` (`/xbp`), so a player's `/xbans reload` still reaches their
 * server.
 */
public class XBansVelocity(private val bootstrap: VelocityBootstrap) : PluginEntry {
    private var platform: VelocityPlatform? = null
    private val handlers = ArrayList<Pair<Class<*>, EventHandler<*>>>()

    // Made on the plugin's own thread, after enable, as zChat's relay is: reading config.yml and
    // the jar's translations and building the commands are most of what xBans adds to Velocity's
    // startup. Until it's made, a login waits for it, as it waits for the bans (Login).
    @Volatile
    private var xbans: XBansPlugin? = null
    private val made = CompletableFuture<XBansPlugin>()

    override fun enable() {
        val started = System.nanoTime()
        val platform = VelocityPlatform.create(bootstrap, development = java.lang.Boolean.getBoolean("xena.development"))
        val family = platform.family
        val cache = PlayerCache(family.board, PlayerStore.InFile(family.folder), platform.scheduler, platform.clock, platform.log, XBansPlugin.PLUGIN_ID)
        platform.record(cache)
        val server = VelocityBansServer(platform)
        this.platform = platform
        // The doors first, so nobody gets in between enable and the plugin being made.
        listen(platform, server)
        platform.scheduler.run(Where.Global, action = Make(platform, server, cache))
        val millis = (System.nanoTime() - started) / 1_000_000
        platform.log.info("xBans ${platform.container.description.version.orElse(XBansPlugin.VERSION)} on ${platform.name}: turning banned players away at the proxy, enabled in $millis ms")
    }

    // A class, not a lambda (decisions/2026-11-25-a-lighter-core.md).
    private inner class Make(private val platform: VelocityPlatform, private val server: VelocityBansServer, private val cache: PlayerCache) : (Task) -> Unit {
        override fun invoke(task: Task) {
            val xbans = try {
                make(platform, server, cache)
            } catch (e: Throwable) {
                // Nobody joins without it: every login is turned away, as with a database that won't open.
                platform.log.error("xBans couldn't start, and nobody can join until it does.", e)
                made.completeExceptionally(e)
                return
            }
            this@XBansVelocity.xbans = xbans
            made.complete(xbans)
        }
    }

    private fun make(platform: VelocityPlatform, server: VelocityBansServer, cache: PlayerCache): XBansPlugin {
        val family = platform.family
        val folder = bootstrap.dataFolder()
        val files = JvmFiles(folder, family, LangFolder.shipped(javaClass.classLoader, JvmFiles.LANGUAGES), platform.log)
        val config = files.config()
        val drivers = Drivers(javaClass.classLoader, platform.driverFetcher())
        val plugins = folder.toAbsolutePath().parent
        val xbans = XBansPlugin(
            platform, server, files, cache, platform.players, cache,
            opener = JvmDatabase(family, folder, plugins.resolve("Xena"), drivers, platform.log),
            registrar = platform.commands,
            permissionRegistrar = platform.permissions,
            about = platform::about,
            paste = if (System.getProperty("xena.debug.paste") == "false") null else MclogsPaste(),
            imports = FileImports(plugins.parent ?: plugins, plugins, drivers, platform.log, listOf("litebans", "advancedban")),
            channel = if (config.config[XBansConfig.network.enabled]) platform.proxyChannel(XBansPlugin.PLUGIN_ID) else null,
            version = platform.container.description.version.orElse(XBansPlugin.VERSION),
        )
        platform.commands.feedback = xbans.texts.feedback
        xbans.start(config)
        xbans.open()
        platform.scheduler.run(Where.Async) { _ ->
            cache.load()
            val loaded = files.translations(config.config, config.problems)
            platform.scheduler.run(Where.Global) { _ -> xbans.apply(loaded) }
        }
        xbans.updates = platform.checkUpdates(XBansPlugin.PLUGIN_ID, xbans.admin.updates, XBansConfig.updates.settings(config.config)) { xbans.texts.translations }
        return xbans
    }

    private fun listen(platform: VelocityPlatform, server: VelocityBansServer) {
        // Last, so a plugin that allows logins later can't let a banned player back in.
        register(platform, LoginEvent::class.java, LAST, Login(platform, this, server))
        register(platform, ServerPreConnectEvent::class.java, LAST, Connect(this, server))
        register(platform, PostLoginEvent::class.java, FIRST, Joined(this, server))
        register(platform, ServerConnectedEvent::class.java, FIRST, Switched(this))
        register(platform, DisconnectEvent::class.java, LAST, Quit(this))
    }

    private fun <E : Any> register(platform: VelocityPlatform, type: Class<E>, order: Short, handler: EventHandler<E>) {
        platform.server.eventManager.register(platform.bootstrap, type, order, handler)
        handlers += type to handler
    }

    /**
     * The network's door: a banned player is turned away before any server sees them. Just after a
     * start it waits, off Velocity's threads, up to 10 seconds for the plugin to be made and its
     * bans read, and turns the player away if they aren't by then.
     */
    private class Login(private val platform: VelocityPlatform, private val entry: XBansVelocity, private val server: VelocityBansServer) : EventHandler<LoginEvent> {
        override fun execute(event: LoginEvent) {
            decide(event)
        }

        override fun executeAsync(event: LoginEvent): EventTask? {
            if (entry.xbans?.backend != null) {
                decide(event)
                return null
            }
            val waited = CompletableFuture<Unit>()
            entry.made.whenComplete { xbans, _ ->
                if (xbans == null) waited.complete(Unit) else xbans.ready.whenComplete { _, _ -> waited.complete(Unit) }
            }
            platform.scheduler.later(Where.Async, XBansPlugin.WAIT_SECONDS.seconds) { _ -> waited.complete(Unit) }
            return EventTask.resumeWhenComplete(waited.thenRun { decide(event) })
        }

        private fun decide(event: LoginEvent) {
            if (!event.result.isAllowed) return
            val player = event.player
            val ref = player.uniqueId.toPlayerRef()
            val xbans = entry.xbans
            if (xbans?.backend == null) {
                event.result = ResultedEvent.ComponentResult.denied(entry.notReady(xbans, ref, player.username, server))
                return
            }
            val ban = xbans.banOnJoin(ref, player.remoteAddress?.address?.hostAddress, null) ?: return
            event.result = ResultedEvent.ComponentResult.denied(server.draw(xbans.denied(ref, player.username, ban)))
        }
    }

    // Before the plugin is made there are no messages to draw the screen in.
    private fun notReady(xbans: XBansPlugin?, ref: PlayerRef, name: String, server: VelocityBansServer): net.kyori.adventure.text.Component =
        server.draw(xbans?.denied(ref, name, null) ?: Component.text(NOT_READY))

    /** A server's door: a player banned on that server's scope doesn't get onto it. */
    private class Connect(private val entry: XBansVelocity, private val server: VelocityBansServer) : EventHandler<ServerPreConnectEvent> {
        override fun execute(event: ServerPreConnectEvent) {
            val target = event.result.server.orElse(null) ?: return
            val player = event.player
            val ref = player.uniqueId.toPlayerRef()
            val xbans = entry.xbans
            if (xbans == null) {
                // Nobody gets past the login before it's made; should one have, they go nowhere.
                event.result = ServerPreConnectEvent.ServerResult.denied()
                return
            }
            val ban = xbans.banOnJoin(ref, player.remoteAddress?.address?.hostAddress, target.serverInfo.name) ?: return
            event.result = ServerPreConnectEvent.ServerResult.denied()
            val screen = server.draw(xbans.denied(ref, player.username, ban))
            // Nowhere else to go yet: off the network. Otherwise they stay where they are, told why.
            if (player.currentServer.isPresent) player.sendMessage(screen) else player.disconnect(screen)
        }
    }

    private class Joined(private val entry: XBansVelocity, private val server: VelocityBansServer) : EventHandler<PostLoginEvent> {
        override fun execute(event: PostLoginEvent) {
            val player = event.player
            val ref = player.uniqueId.toPlayerRef()
            val xbans = entry.xbans
            if (xbans == null) {
                player.disconnect(entry.notReady(null, ref, player.username, server))
                return
            }
            val ban = xbans.joined(ref, player.username, player.remoteAddress?.address?.hostAddress)
            if (ban != null || xbans.backend == null) player.disconnect(server.draw(xbans.denied(ref, player.username, ban)))
        }
    }

    private class Switched(private val entry: XBansVelocity) : EventHandler<ServerConnectedEvent> {
        override fun execute(event: ServerConnectedEvent) {
            entry.xbans?.switched(event.player.uniqueId.toPlayerRef(), event.server.serverInfo.name)
        }
    }

    private class Quit(private val entry: XBansVelocity) : EventHandler<DisconnectEvent> {
        override fun execute(event: DisconnectEvent) {
            entry.xbans?.quit(event.player.uniqueId.toPlayerRef())
        }
    }

    private companion object {
        // Velocity's numeric priorities (higher runs first): PostOrder.FIRST's and LAST's values.
        const val FIRST: Short = Short.MAX_VALUE
        const val LAST: Short = Short.MIN_VALUE

        // screens.not-ready's English, before there are translations to draw it in.
        const val NOT_READY = "The server is still starting.\nTry again in a few seconds."
    }

    override fun disable() {
        val platform = platform ?: return
        for ((_, handler) in handlers) platform.server.eventManager.unregister(platform.bootstrap, handler)
        handlers.clear()
        run {
            val done = CountDownLatch(1)
            // On the plugin's thread, so after Make: whatever it made is stopped.
            platform.scheduler.run(Where.Global) { _ ->
                try {
                    xbans?.stop()?.get(10, TimeUnit.SECONDS)
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
    }
}

/** xBans' [BansServer] on Velocity: the network's players, their addresses and permissions, disconnects from the whole network. */
internal class VelocityBansServer(private val platform: VelocityPlatform) : BansServer {
    private val network = platform.server
    private val zone = ZoneId.systemDefault()

    override val proxy: Boolean get() = true

    fun draw(component: Component): net.kyori.adventure.text.Component = platform.text.renderer.draw(component)

    private fun player(ref: PlayerRef) = network.getPlayer(ref.javaUuid).orElse(null)

    override fun online(): List<PlayerRef> = network.allPlayers.map { it.uniqueId.toPlayerRef() }

    override fun isOnline(player: PlayerRef): Boolean = player(player) != null

    override fun name(player: PlayerRef): String? = player(player)?.username

    override fun address(player: PlayerRef): String? = player(player)?.remoteAddress?.address?.hostAddress

    override fun send(player: PlayerRef, message: Component) {
        player(player)?.sendMessage(draw(message))
    }

    override fun console(message: Component) {
        network.consoleCommandSource.sendMessage(draw(message))
    }

    override fun kick(player: PlayerRef, screen: Component, quiet: Boolean) {
        player(player)?.disconnect(draw(screen))
    }

    override fun hasPermission(player: PlayerRef, node: String): Boolean = player(player)?.let { platform.permissions.has(it, node) } ?: false

    // Velocity answers one node at a time: 1 to 100, then the round numbers to 1,000.
    override fun weight(player: PlayerRef): Int {
        val online = player(player) ?: return 0
        for (n in WEIGHTS) if (online.getPermissionValue(Power.WEIGHT_PREFIX + n) == com.velocitypowered.api.permission.Tristate.TRUE) return n
        return 0
    }

    override fun dispatch(line: String) {
        network.commandManager.executeAsync(network.consoleCommandSource, line)
    }

    // Commands typed to a server pass through the proxy; mutes are each server's.
    override fun commandNames(label: String): List<String>? = null

    override fun lookup(name: String): Pending<Pair<PlayerRef, String>?> = co.xenastudios.core.api.pendingOf(
        network.getPlayer(name).orElse(null)?.let { it.uniqueId.toPlayerRef() to it.username },
    )

    override fun utcOffsetSeconds(epochMillis: Long): Int = zone.rules.getOffset(java.time.Instant.ofEpochMilli(epochMillis)).totalSeconds

    companion object {
        /** The weights asked for, highest first. */
        val WEIGHTS: IntArray = ((1000 downTo 101 step 50) + (100 downTo 1)).toIntArray()
    }
}
