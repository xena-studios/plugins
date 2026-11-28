package co.xenastudios.xlimits.bukkit.modern

import co.xenastudios.core.bukkit.ref
import co.xenastudios.core.bukkit.toCore
import co.xenastudios.xlimits.LimitsView
import org.bukkit.Server
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.world.EntitiesLoadEvent
import org.bukkit.event.world.EntitiesUnloadEvent

// What only newer servers have, compiled against Paper's API. Each is used only once a check at
// startup has found what it needs (BukkitLimitsServer.Paths), so an old server never loads it.

/** 1.15 on: whether [entity] pathfinds and looks for targets. Returns whether it changed. */
internal fun aware(entity: LivingEntity, aware: Boolean): Boolean {
    val mob = entity as? Mob ?: return false
    if (mob.isAware == aware) return false
    mob.isAware = aware
    return true
}

/** 1.17 on: the lowest block height of [world]. */
internal fun minHeight(world: org.bukkit.World): Int = world.minHeight

/** Paper: the server's TPS over the last minute. */
internal fun tps(server: Server): Double = server.tps[0]

/** Paper: the server's milliseconds per tick, averaged. */
internal fun mspt(server: Server): Double = server.averageTickTime

/** Paper and Folia: teleports without blocking, from any thread. */
internal fun teleportAsync(player: Player, to: org.bukkit.Location) {
    player.teleportAsync(to)
}

/** 1.17 on: entities load after their chunk, so farm slowing and pets are checked as they do. */
internal class EntitiesLoaded(private val view: LimitsView) : Listener {
    @EventHandler(priority = EventPriority.MONITOR)
    fun loaded(event: EntitiesLoadEvent) {
        val c = event.chunk
        view.chunkLoaded(co.xenastudios.xlimits.api.ChunkRef(c.world.ref, c.x, c.z))
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun unloaded(event: EntitiesUnloadEvent) {
        val c = event.chunk
        view.chunkUnloaded(co.xenastudios.xlimits.api.ChunkRef(c.world.ref, c.x, c.z))
    }
}

/** 1.19.4 on (Spigot and Paper): TNT about to be primed, cancellable before it's an entity. */
internal class TntPrimes(private val view: LimitsView) : Listener {
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun prime(event: org.bukkit.event.block.TNTPrimeEvent) {
        val at = event.block.location.toCore()
        val player = (event.primingEntity as? Player)?.ref
        if (!view.priming(at, player)) event.isCancelled = true
    }
}

/** Paper 1.13 to 1.19.3: its own TNT prime event. */
@Suppress("DEPRECATION", "removal")
internal class PaperTntPrimes(private val view: LimitsView) : Listener {
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun prime(event: com.destroystokyo.paper.event.block.TNTPrimeEvent) {
        val at = event.block.location.toCore()
        val player = (event.primerEntity as? Player)?.ref
        if (!view.priming(at, player)) event.isCancelled = true
    }
}
