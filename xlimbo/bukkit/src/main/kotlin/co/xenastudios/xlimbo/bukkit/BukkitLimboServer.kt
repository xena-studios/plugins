package co.xenastudios.xlimbo.bukkit

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.bukkit.BukkitPlatform
import co.xenastudios.core.bukkit.online
import co.xenastudios.core.bukkit.ref
import co.xenastudios.core.common.config.Config
import co.xenastudios.core.common.family.PlayerCache
import co.xenastudios.core.common.text.Component
import co.xenastudios.xlimbo.LimboServer
import co.xenastudios.xlimbo.Switch
import co.xenastudios.xlimbo.XLimboPlugin
import co.xenastudios.xlimbo.api.ReturnRoute
import co.xenastudios.xlimbo.api.XLimbo
import co.xenastudios.xlimbo.config.XLimboConfig
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.plugin.java.JavaPlugin
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * xLimbo's [LimboServer] on Paper: the world ([LimboWorld]), the proxy's plugin channel, teleports
 * through xTeleport or Paper's own, and a listener per switch ([Listeners]). Paper only: every
 * call here is Paper's API, and the server thread is the main thread.
 */
internal class BukkitLimboServer(
    private val plugin: JavaPlugin,
    private val platform: BukkitPlatform,
    private val cache: PlayerCache,
) : LimboServer {
    private val server = plugin.server

    /** The plugin itself, for Bukkit's calls that take it (hiding players, their schedulers). */
    val xlimboPlugin: JavaPlugin get() = plugin
    val world = LimboWorld(plugin, platform.log)
    lateinit var xlimbo: XLimboPlugin

    /** Where each player was before they were sent into limbo on this server, for `destination: previous`. */
    private val previous = ConcurrentHashMap<UUID, Location>()

    /** What each player had before limbo on this server put them in adventure mode, given back as they leave. */
    private val before = ConcurrentHashMap<UUID, Before>()

    class Before(val mode: GameMode, val allowFlight: Boolean, val flying: Boolean)

    @Volatile
    var teleport: XTeleportBridge? = null

    @Volatile
    var vanish: XVanishBridge? = null

    val route: ReturnRoute get() = xlimbo.config[XLimboConfig.sendBack.via]

    private fun player(ref: PlayerRef): Player? = ref.online(server)

    override fun online(): List<PlayerRef> = server.onlinePlayers.map { it.ref }

    override fun name(player: PlayerRef): String? = player(player)?.name

    override fun isInLimbo(player: PlayerRef): Boolean = world.isLimbo(player(player)?.world)

    override fun isReady(): Boolean = world.world != null

    override fun hasPermission(player: PlayerRef, node: String): Boolean = player(player)?.hasPermission(node) == true

    override fun send(player: PlayerRef, message: Component) {
        player(player)?.let { platform.text.send(it, message) }
    }

    override fun actionBar(player: PlayerRef, message: Component) {
        val drawn = platform.text.renderer.draw(message) as? net.kyori.adventure.text.Component ?: return
        player(player)?.sendActionBar(drawn)
    }

    override fun clearChat(player: PlayerRef) {
        player(player)?.sendMessage(CLEAR)
    }

    override fun equip(player: PlayerRef, config: Config) {
        val p = player(player) ?: return
        equip(p, config)
    }

    /** Creative (or, sent in on this server, adventure) and flight, as the config says. */
    fun equip(p: Player, config: Config) {
        val flight = config[XLimboConfig.player.enableFlight]
        if (route == ReturnRoute.TELEPORT) {
            // A player of this server keeps nothing from limbo: no creative, whose items they'd carry
            // back. Adventure can't break or place either; what they had is given back as they leave.
            before.putIfAbsent(p.uniqueId, Before(p.gameMode, p.allowFlight, p.isFlying))
            if (config[XLimboConfig.player.setCreative] && p.gameMode != GameMode.ADVENTURE) p.gameMode = GameMode.ADVENTURE
        } else if (config[XLimboConfig.player.setCreative] && p.gameMode != GameMode.CREATIVE) {
            p.gameMode = GameMode.CREATIVE
        }
        if (flight) {
            p.allowFlight = true
            p.isFlying = true
        }
    }

    /** A player leaving limbo on this server gets back what they had before it, or the server's default game mode if that's not known. */
    fun unequip(p: Player) {
        if (route != ReturnRoute.TELEPORT) return
        val was = before.remove(p.uniqueId)
        p.gameMode = was?.mode ?: server.defaultGameMode
        p.allowFlight = was?.allowFlight ?: (p.gameMode == GameMode.CREATIVE || p.gameMode == GameMode.SPECTATOR)
        p.isFlying = p.allowFlight && (was?.flying ?: false)
    }

    fun forget(player: UUID) {
        before.remove(player)
    }

    override fun connect(player: PlayerRef, server: String) {
        val p = player(player) ?: return
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use {
            it.writeUTF("Connect")
            it.writeUTF(server)
        }
        p.sendPluginMessage(plugin, CHANNEL, bytes.toByteArray())
    }

    override fun teleportBack(player: PlayerRef, destination: String): Pending<Boolean> {
        val p = player(player) ?: return CompletableFuture.completedFuture(false)
        val bridge = teleport
        val name = if (destination == "previous") {
            val place = previous[p.uniqueId]?.takeIf { it.world != null && server.getWorld(it.world.uid) != null }
            if (place != null) return if (bridge != null) bridge.teleport(p.uniqueId, place) else p.teleportAsync(place)
            "spawn"
        } else {
            destination
        }
        if (bridge != null) return bridge.teleport(p.uniqueId, name)
        // Without xTeleport: the main world's spawn, and nowhere else to know of.
        if (name == "spawn") return p.teleportAsync(server.worlds.first().spawnLocation)
        platform.log.warn("send-back.destination $destination needs xTeleport, which isn't installed; ${p.name} stays in limbo.")
        return CompletableFuture.completedFuture(false)
    }

    override fun enterLimbo(player: PlayerRef): Pending<Boolean> {
        val p = player(player) ?: return CompletableFuture.completedFuture(false)
        val spawn = world.spawn() ?: return CompletableFuture.completedFuture(false)
        remember(p, p.location)
        val moved = p.teleportAsync(spawn)
        if (route == ReturnRoute.PROXY) {
            // On a limbo server a world change isn't an arrival (the listener's for this server's own
            // worlds), so this one is said here.
            return moved.thenApply { ok -> ok.also { if (it) platform.scheduler.run(co.xenastudios.core.common.scheduler.Where.Global) { xlimbo.arrived(player, joined = false) } } }
        }
        return moved
    }

    /** Where [player] was, as they're sent into limbo: for `destination: previous`. Not a place in limbo itself. */
    fun remember(player: Player, from: Location) {
        if (!world.isLimbo(from.world)) previous[player.uniqueId] = from.clone()
    }

    fun forgetPlace(player: UUID) {
        previous.remove(player)
    }

    @Volatile
    private var syncPending = false

    // xLimbo enables before the server's worlds load (load: STARTUP), so a world in bukkit.yml can
    // use its generator; its own world can only be made once they're loaded, on the first tick.
    // Bukkit's scheduler keeps running while an empty server is paused (#67).
    override fun sync(config: Config) {
        if (server.worlds.isEmpty()) {
            if (!syncPending) {
                syncPending = true
                platform.scheduler.run(co.xenastudios.core.common.scheduler.Where.Global) {
                    syncPending = false
                    world.sync(WorldSettings.of(xlimbo.config, platform.log))
                }
            }
            return
        }
        world.sync(WorldSettings.of(config, platform.log))
    }

    override fun listen(switch: Int, plugin: XLimboPlugin): AutoCloseable {
        if (switch == Switch.CLEANUP) {
            val seconds = plugin.config[XLimboConfig.protections.entities.cleanupIntervalSeconds]
            val task = platform.scheduler.run(co.xenastudios.core.common.scheduler.Where.Global, kotlin.time.Duration.parse("${seconds}s"), kotlin.time.Duration.parse("${seconds}s")) {
                Listeners.sweep(world)
            }
            return AutoCloseable { task.cancel() }
        }
        val listener: Listener = Listeners.of(switch, this)
        server.pluginManager.registerEvents(listener, this.plugin)
        if (listener is Listeners.HideOthers) listener.start()
        return AutoCloseable {
            HandlerList.unregisterAll(listener)
            if (listener is Listeners.HideOthers) listener.stop()
        }
    }

    override fun vanishedFrom(viewer: PlayerRef?, target: PlayerRef): Boolean {
        if (viewer == target) return false
        val bridge = vanish
        if (bridge != null) {
            val t = target.javaUuid()
            if (!bridge.vanished(t)) return false
            return viewer == null || !bridge.canSee(viewer.javaUuid(), t)
        }
        // Without xVanish's levels, the family's cache: hidden from all but xvanish.see.
        if (!cache.isVanished(target)) return false
        return viewer == null || !hasPermission(viewer, XVANISH_SEE)
    }

    /** The `xlimbo` destination xLimbo gives xTeleport: the limbo's floor, once the pre-event allows it. */
    fun destinationFor(uuid: UUID): Location? {
        val ref = PlayerRef.parseOrNull(uuid.toString()) ?: return null
        val p = player(ref) ?: return null
        if (world.isLimbo(p.world)) return null
        val spawn = world.spawn() ?: return null
        if (!xlimbo.mayEnter(ref)) return null
        remember(p, p.location)
        return spawn
    }

    private fun PlayerRef.javaUuid(): UUID = UUID.fromString(toString())

    companion object {
        const val CHANNEL = "BungeeCord"
        const val XVANISH_SEE = "xvanish.see"
        val NAME = XLimbo.DESTINATION
        private val CLEAR = net.kyori.adventure.text.Component.text(List(100) { " " }.joinToString("\n"))
    }
}
