package co.xenastudios.xlimits.bukkit

import co.xenastudios.core.api.Location
import co.xenastudios.core.api.event.EventListener
import co.xenastudios.core.api.event.EventPriority
import co.xenastudios.core.api.event.Subscription
import co.xenastudios.core.bukkit.BukkitPlatform
import co.xenastudios.core.bukkit.ref
import co.xenastudios.core.bukkit.toCore
import co.xenastudios.core.common.world.BlockChange
import co.xenastudios.core.common.world.ChangeCause
import co.xenastudios.core.common.world.EntityChange
import co.xenastudios.core.common.world.EntityChangeKind
import co.xenastudios.core.common.world.EntityDeath
import co.xenastudios.core.common.world.EntitySpawn
import co.xenastudios.xlimits.Category
import co.xenastudios.xlimits.LimitsView
import co.xenastudios.xlimits.Switch
import co.xenastudios.xlimits.api.ChunkRef
import org.bukkit.Material
import org.bukkit.entity.Entity
import org.bukkit.entity.FallingBlock
import org.bukkit.entity.Player
import org.bukkit.entity.Tameable
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockRedstoneEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityPortalEvent
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.event.EventPriority as BukkitPriority

/**
 * xLimits' listeners on Bukkit, one set per [Switch], started only while its limit is on. Spawns,
 * blocks, tames and deaths are the core's world hooks; the rest are xLimits' own, as no other
 * plugin needs them.
 */
internal object Listeners {
    fun start(switch: Int, view: LimitsView, plugin: JavaPlugin, server: BukkitLimitsServer, platform: BukkitPlatform): AutoCloseable {
        val hooks = server.hooks
        val paths = server.paths
        return when (switch) {
            Switch.ENTITIES -> Subscribed(hooks.onEntitySpawn(EventPriority.HIGH, listener = Spawns(view, server)))
            Switch.CHUNKS -> registered(plugin, if (paths.entitiesLoad) co.xenastudios.xlimits.bukkit.modern.EntitiesLoaded(view) else ChunkLoads(view))
            Switch.BLOCKS -> Subscribed(
                hooks.onBlockChange(setOf(ChangeCause.PLAYER), EventPriority.HIGH, listener = Blocks(view, placing = true)),
                // What else takes blocks away, once it has.
                hooks.onBlockChange(
                    setOf(ChangeCause.EXPLOSION, ChangeCause.FIRE, ChangeCause.PISTON, ChangeCause.ENTITY), EventPriority.MONITOR,
                    listener = Blocks(view, placing = false),
                ),
            )
            Switch.REDSTONE -> registered(plugin, Redstone(view))
            Switch.TNT -> {
                val falling = registered(plugin, Falling(view))
                val primes = when {
                    paths.bukkitTntPrime -> registered(plugin, co.xenastudios.xlimits.bukkit.modern.TntPrimes(view))
                    paths.paperTntPrime -> registered(plugin, co.xenastudios.xlimits.bukkit.modern.PaperTntPrimes(view))
                    // Spigot before 1.19.4: the TNT entity, refused as it spawns, and the block put back.
                    else -> Subscribed(hooks.onEntitySpawn(EventPriority.HIGH, listener = LegacyTnt(view, server)))
                }
                Both(falling, primes)
            }
            Switch.PORTALS -> registered(plugin, Portals(view, server))
            else -> Subscribed(
                hooks.onEntityChange(EventPriority.HIGH, listener = Tames(view, server)),
                hooks.onEntityDeath(Deaths(view)),
            )
        }
    }

    private fun registered(plugin: JavaPlugin, listener: Listener): AutoCloseable {
        plugin.server.pluginManager.registerEvents(listener, plugin)
        return AutoCloseable { HandlerList.unregisterAll(listener) }
    }

    private class Subscribed(private vararg val subscriptions: Subscription) : AutoCloseable {
        override fun close() = subscriptions.forEach { it.cancel() }
    }

    private class Both(private val a: AutoCloseable, private val b: AutoCloseable) : AutoCloseable {
        override fun close() {
            a.close()
            b.close()
        }
    }

    /** A block's place, as the core's [Location]. */
    fun location(change: BlockChange): Location = Location(change.world, change.x + 0.5, change.y.toDouble(), change.z + 0.5)
}

/** New entities against their chunk's caps, and farm slowing at the same moment. */
private class Spawns(private val view: LimitsView, private val server: BukkitLimitsServer) : EventListener<EntitySpawn> {
    override fun on(event: EntitySpawn) {
        val entity = event.nativeEntity as? Entity ?: return
        if (entity is Player) return
        val kind = server.kinds.of(entity)
        // TNT and falling blocks have caps of their own (Switch.TNT), and items none.
        if (kind.categories == 0 || kind.isIn(Category.TNT or Category.FALLING)) return
        val at = Location(event.world, event.x + 0.5, event.y.toDouble(), event.z + 0.5)
        if (!view.spawning(kind, at, co.xenastudios.xlimits.SpawnRoutes.of(event.reason, kind), event.player)) event.isCancelled = true
    }
}

/** Blocks placed against their caps ([placing]), or removed by anything else. */
private class Blocks(private val view: LimitsView, private val placing: Boolean) : EventListener<BlockChange> {
    override fun on(event: BlockChange) = co.xenastudios.xlimits.BlockChanges.on(view, event, Listeners.location(event), placing)
}

/** Before 1.17, a chunk's entities load with it. */
private class ChunkLoads(private val view: LimitsView) : Listener {
    @EventHandler(priority = BukkitPriority.MONITOR)
    fun loaded(event: ChunkLoadEvent) {
        val c = event.chunk
        view.chunkLoaded(ChunkRef(c.world.ref, c.x, c.z))
    }

    @EventHandler(priority = BukkitPriority.MONITOR)
    fun unloaded(event: ChunkUnloadEvent) {
        val c = event.chunk
        view.chunkUnloaded(ChunkRef(c.world.ref, c.x, c.z))
    }
}

/** Redstone changing power: a block that changes too often is a clock, stopped and broken. */
private class Redstone(private val view: LimitsView) : Listener {
    @EventHandler(priority = BukkitPriority.HIGH)
    fun change(event: BlockRedstoneEvent) {
        if (event.oldCurrent == event.newCurrent) return
        if (!view.redstone(event.block.location.toCore())) event.newCurrent = event.oldCurrent
    }
}

/** Sand, gravel and concrete powder starting to fall. */
private class Falling(private val view: LimitsView) : Listener {
    @EventHandler(priority = BukkitPriority.HIGH, ignoreCancelled = true)
    fun fall(event: EntityChangeBlockEvent) {
        if (event.entity !is FallingBlock || event.to != Material.AIR) return
        if (!view.falling(event.block.location.toCore())) event.isCancelled = true
    }
}

/** Before 1.19.4 on Spigot: primed TNT refused as it spawns, and the TNT put back as a block. */
private class LegacyTnt(private val view: LimitsView, private val server: BukkitLimitsServer) : EventListener<EntitySpawn> {
    override fun on(event: EntitySpawn) {
        val entity = event.nativeEntity as? Entity ?: return
        if (!server.kinds.of(entity).isIn(Category.TNT)) return
        val at = entity.location
        if (view.priming(at.toCore(), null)) return
        event.isCancelled = true
        val block = at.block
        if (block.type == Material.AIR) block.type = Material.TNT
    }
}

/** Entities other than players, and what they ride, going through a portal. */
private class Portals(private val view: LimitsView, private val server: BukkitLimitsServer) : Listener {
    @EventHandler(priority = BukkitPriority.HIGH, ignoreCancelled = true)
    fun portal(event: EntityPortalEvent) {
        val entity = event.entity
        if (entity is Player || entity.passengers.any { it is Player }) return
        if (!view.portal(server.kinds.of(entity), event.from.toCore())) event.isCancelled = true
    }
}

/** Players taming mobs, against their pet cap. */
private class Tames(private val view: LimitsView, private val server: BukkitLimitsServer) : EventListener<EntityChange> {
    override fun on(event: EntityChange) {
        if (event.kind != EntityChangeKind.TAMED) return
        val owner = event.player ?: return
        val entity = event.nativeEntity as? Entity ?: return
        if (!view.taming(owner, server.kinds.of(entity), server.at(entity), entity.uniqueId.toString())) event.isCancelled = true
    }
}

/** Tamed mobs dying: off their owner's count. */
private class Deaths(private val view: LimitsView) : EventListener<EntityDeath> {
    override fun on(event: EntityDeath) {
        val entity = event.nativeEntity as? Tameable ?: return
        if (entity.isTamed) view.petGone(entity.uniqueId.toString())
    }
}
