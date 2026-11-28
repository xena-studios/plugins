package co.xenastudios.core.bukkit.world

import co.xenastudios.core.common.world.ChangeCause
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Directional
import org.bukkit.block.data.Waterlogged
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.entity.TNTPrimed
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockBurnEvent
import org.bukkit.event.block.BlockEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockFromToEvent
import org.bukkit.event.block.BlockIgniteEvent
import org.bukkit.event.block.BlockMultiPlaceEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.BlockPistonRetractEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.block.LeavesDecayEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.player.PlayerBucketEmptyEvent
import org.bukkit.event.player.PlayerBucketFillEvent
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.util.concurrent.TimeUnit
import org.bukkit.block.data.BlockData as BukkitData

// The block changes, one listener object per WorldHook so each unregisters alone
// (BukkitWorldHooks.start). Every handler sees cancelled events too, reads Bukkit's flag into the
// change and writes the listeners' decision back.

private val AIR: BukkitData by lazy(LazyThreadSafetyMode.PUBLICATION) { Material.AIR.createBlockData() }
private val FIRE: BukkitData by lazy(LazyThreadSafetyMode.PUBLICATION) { Material.FIRE.createBlockData() }

/** Blocks players place and break, fires they light, buckets they empty and fill, farmland they trample. */
internal class PlayerBlocks(private val hooks: BukkitWorldHooks) : Listener {
    init {
        hooks.on(this, BlockPlaceEvent::class.java, handle = ::place)
        hooks.on(this, BlockBreakEvent::class.java, handle = ::broke)
        hooks.on(this, BlockIgniteEvent::class.java, handle = ::ignite)
        hooks.on(this, PlayerBucketEmptyEvent::class.java, handle = ::empty)
        hooks.on(this, PlayerBucketFillEvent::class.java, handle = ::fill)
        // A player's EntityChangeBlockEvent (trampling farmland) is theirs; EntityBlocks hears the rest.
        hooks.on(this, EntityChangeBlockEvent::class.java) { event -> if (event.entity is Player) EntityBlocks.handle(hooks, event) }
    }

    private fun place(event: BlockPlaceEvent) {
        val player = event.player
        var cancelled = event.isCancelled
        // Beds, doors and tall plants are placed whole: each of their blocks is a change.
        val replaced = if (event is BlockMultiPlaceEvent) event.replacedBlockStates else listOf(event.blockReplacedState)
        for (state in replaced) {
            val block = state.block
            val change = hooks.change(block, state.blockData, block.blockData, ChangeCause.PLAYER, player, null, cancelled)
            cancelled = change.isCancelled
        }
        event.isCancelled = cancelled
    }

    private fun broke(event: BlockBreakEvent) {
        val block = event.block
        val change = hooks.change(block, block.blockData, AIR, ChangeCause.PLAYER, event.player, null, event.isCancelled)
        event.isCancelled = change.isCancelled
        if (!change.isCancelled) hooks.recent?.broke(block, event.player, null)
    }

    private fun ignite(event: BlockIgniteEvent) {
        val player = event.player ?: return
        val block = event.block
        event.isCancelled = hooks.change(block, block.blockData, FIRE, ChangeCause.PLAYER, player, null, event.isCancelled).isCancelled
    }

    private fun empty(event: PlayerBucketEmptyEvent) {
        val clicked = event.blockClicked
        val bucket = event.bucket
        val clickedData = clicked.blockData
        // Water into a block that takes it (a slab, stairs) fills that block; anything else goes beside it.
        val (block, before, after) = if (clickedData is Waterlogged && bucket == Material.WATER_BUCKET) {
            val wet = clickedData.clone() as Waterlogged
            wet.isWaterlogged = true
            Triple(clicked, clickedData, wet)
        } else {
            val beside = clicked.getRelative(event.blockFace)
            val data = beside.blockData
            val after = if (data is Waterlogged && bucket == Material.WATER_BUCKET) {
                (data.clone() as Waterlogged).also { it.isWaterlogged = true }
            } else {
                fluidOf(bucket).createBlockData()
            }
            Triple(beside, data, after)
        }
        event.isCancelled = hooks.change(block, before, after, ChangeCause.PLAYER, event.player, null, event.isCancelled).isCancelled
    }

    private fun fill(event: PlayerBucketFillEvent) {
        val block = event.blockClicked
        val before = block.blockData
        val after = if (before is Waterlogged && before.isWaterlogged) (before.clone() as Waterlogged).also { it.isWaterlogged = false } else AIR
        event.isCancelled = hooks.change(block, before, after, ChangeCause.PLAYER, event.player, null, event.isCancelled).isCancelled
    }

    private companion object {
        // Powder snow's bucket from 1.17, by name so 1.13 doesn't need it.
        val POWDER_SNOW_BUCKET: Material? = Material.getMaterial("POWDER_SNOW_BUCKET")
        val POWDER_SNOW: Material? = Material.getMaterial("POWDER_SNOW")

        fun fluidOf(bucket: Material): Material = when {
            bucket == Material.LAVA_BUCKET -> Material.LAVA
            bucket == POWDER_SNOW_BUCKET && POWDER_SNOW != null -> POWDER_SNOW
            else -> Material.WATER
        }
    }
}

/** Blocks explosions destroy: an entity's (TNT, creepers, withers' skulls) and a block's (beds, anchors). */
internal class Explosions(private val hooks: BukkitWorldHooks) : Listener {
    init {
        hooks.on(this, EntityExplodeEvent::class.java) { event ->
            val entity = event.entity
            val player = (entity as? TNTPrimed)?.source as? Player
            explode(event, event.blockList(), player, Keys.of(entity.type))
        }
        hooks.on(this, BlockExplodeEvent::class.java) { event -> explode(event, event.blockList(), null, blockSource(event)) }
    }

    private fun <T> explode(event: T, blocks: MutableList<Block>, player: Player?, source: String) where T : Event, T : Cancellable {
        if (event.isCancelled) return
        // A block a listener keeps comes out of the explosion's list, and stays.
        val each = blocks.iterator()
        while (each.hasNext()) {
            val block = each.next()
            val change = hooks.change(block, block.blockData, AIR, ChangeCause.EXPLOSION, player, source, false)
            if (change.isCancelled) each.remove() else hooks.recent?.broke(block, player, source)
        }
    }

    // What blew up, where the server says (Paper 1.19.4+: the bed or anchor as it was); else "minecraft:explosion".
    private fun blockSource(event: BlockExplodeEvent): String {
        val state = EXPLODED?.let { handle -> runCatching { handle.invoke(event) as org.bukkit.block.BlockState }.getOrNull() }
        return state?.type?.takeIf { !Keys.isAir(it) }?.let(Keys::of) ?: "minecraft:explosion"
    }

    private companion object {
        val EXPLODED: MethodHandle? = try {
            MethodHandles.publicLookup().findVirtual(BlockExplodeEvent::class.java, "getExplodedBlockState", MethodType.methodType(org.bukkit.block.BlockState::class.java))
        } catch (e: ReflectiveOperationException) {
            null
        }
    }
}

/** Blocks fire burns away. */
internal class Burns(private val hooks: BukkitWorldHooks) : Listener {
    init {
        hooks.on(this, BlockBurnEvent::class.java) { event ->
            val block = event.block
            event.isCancelled = hooks.change(block, block.blockData, AIR, ChangeCause.FIRE, null, "minecraft:fire", event.isCancelled).isCancelled
        }
    }
}

/**
 * Blocks pistons move: each block's new spot gets it, and a spot it left that nothing moved
 * into becomes air, so putting every change back puts the blocks back. Stopping any of them
 * stops the piston.
 */
internal class Pistons(private val hooks: BukkitWorldHooks) : Listener {
    init {
        hooks.on(this, BlockPistonExtendEvent::class.java) { event -> move(event, event.blocks, facing(event.block)) }
        // Pulled blocks move back towards the piston.
        hooks.on(this, BlockPistonRetractEvent::class.java) { event -> move(event, event.blocks, facing(event.block).oppositeFace) }
    }

    private fun move(event: BlockEvent, blocks: List<Block>, direction: BlockFace) {
        event as Cancellable
        if (event.isCancelled || blocks.isEmpty()) return
        val source = Keys.of(event.block.type)
        val arriving = LinkedHashMap<Block, BukkitData>(blocks.size * 2)
        for (block in blocks) arriving[block.getRelative(direction)] = block.blockData
        var cancelled = false
        for ((block, data) in arriving) {
            cancelled = hooks.change(block, block.blockData, data, ChangeCause.PISTON, null, source, cancelled).isCancelled
        }
        for (block in blocks) {
            if (block !in arriving) cancelled = hooks.change(block, block.blockData, AIR, ChangeCause.PISTON, null, source, cancelled).isCancelled
        }
        event.isCancelled = cancelled
    }

    private fun facing(piston: Block): BlockFace = (piston.blockData as? Directional)?.facing ?: BlockFace.UP
}

/** Water and lava flowing into a block and destroying it. Spreading into air or into itself isn't a change. */
internal class Flows(private val hooks: BukkitWorldHooks) : Listener {
    init {
        hooks.on(this, BlockFromToEvent::class.java) { event ->
            // The busiest event there is: out at once unless something's in the way.
            val to = event.toBlock
            val type = to.type
            if (Keys.isAir(type) || type == Material.WATER || type == Material.LAVA) return@on
            val from = event.block
            val liquid = from.type
            if (liquid != Material.WATER && liquid != Material.LAVA) return@on
            val change = hooks.change(to, to.blockData, from.blockData, ChangeCause.FLUID, null, Keys.of(liquid), event.isCancelled)
            event.isCancelled = change.isCancelled
        }
    }
}

/** Blocks entities change: endermen, withers, ravagers, falling blocks, mobs trampling farmland. */
internal class EntityBlocks(private val hooks: BukkitWorldHooks, players: Boolean) : Listener {
    init {
        if (!players) hooks.on(this, EntityChangeBlockEvent::class.java) { event -> if (event.entity !is Player) handle(hooks, event) }
    }

    companion object {
        fun handle(hooks: BukkitWorldHooks, event: EntityChangeBlockEvent) {
            val entity: Entity = event.entity
            val player = entity as? Player
            val cause = if (player != null) ChangeCause.PLAYER else ChangeCause.ENTITY
            if (!hooks.heard(cause)) return
            val block = event.block
            val change = hooks.change(block, block.blockData, event.blockData, cause, player, Keys.of(entity.type), event.isCancelled)
            event.isCancelled = change.isCancelled
        }
    }
}

/** Leaves decaying. */
internal class Decays(private val hooks: BukkitWorldHooks) : Listener {
    init {
        hooks.on(this, LeavesDecayEvent::class.java) { event ->
            val block = event.block
            event.isCancelled = hooks.change(block, block.blockData, AIR, ChangeCause.DECAY, null, "minecraft:decay", event.isCancelled).isCancelled
        }
    }
}

/**
 * Paper's `BlockDestroyEvent`: a block that falls off another (a torch, a sign, a rail, a chain
 * of sugar cane) is put down to whoever broke the one beside it in the last second. Spigot has
 * no such event, so there it isn't heard.
 */
internal object FallingBlocks {
    private const val EVENT = "com.destroystokyo.paper.event.block.BlockDestroyEvent"

    fun register(hooks: BukkitWorldHooks): Listener? {
        val type = try {
            Class.forName(EVENT, false, hooks.plugin.server.javaClass.classLoader).asSubclass(BlockEvent::class.java)
        } catch (e: ReflectiveOperationException) {
            return null
        } catch (e: ClassCastException) {
            return null
        }
        val newState = try {
            MethodHandles.publicLookup().findVirtual(type, "getNewState", MethodType.methodType(BukkitData::class.java))
        } catch (e: ReflectiveOperationException) {
            return null
        }
        val listener = object : Listener {}
        hooks.on(listener, type) { event ->
            val recent = hooks.recent ?: return@on
            val block = event.block
            val by = recent.besides(block) ?: return@on
            val cause = if (by.player != null) ChangeCause.PLAYER else ChangeCause.EXPLOSION
            if (!hooks.heard(cause)) return@on
            val after = newState.invoke(event) as BukkitData
            val cancellable = event as Cancellable
            val change = hooks.change(block, block.blockData, after, cause, by.player, by.source, cancellable.isCancelled)
            cancellable.isCancelled = change.isCancelled
            if (!change.isCancelled) recent.broke(block, by.player, by.source)
        }
        return listener
    }
}

/** Blocks broken in the last second and by whom, for [FallingBlocks]. Safe from any thread. */
internal class RecentBreaks {
    class By(val player: Player?, val source: String?, val at: Long)

    private class Spot(val world: String, val x: Int, val y: Int, val z: Int) {
        override fun equals(other: Any?): Boolean = other is Spot && other.x == x && other.y == y && other.z == z && other.world == world
        override fun hashCode(): Int = ((x * 31 + y) * 31 + z) * 31 + world.hashCode()
    }

    private val recent = java.util.concurrent.ConcurrentHashMap<Spot, By>()

    @Volatile
    private var lastTidy = 0L

    fun broke(block: Block, player: Player?, source: String?) {
        val now = System.nanoTime()
        // At most once a second: a chain of TNT breaking thousands of blocks mustn't scan the map each time.
        if (recent.size >= TIDY_AT && now - lastTidy > KEEP) {
            lastTidy = now
            recent.values.removeIf { now - it.at > KEEP }
        }
        recent[Spot(block.world.name, block.x, block.y, block.z)] = By(player, source, now)
    }

    fun besides(block: Block): By? {
        if (recent.isEmpty()) return null
        val now = System.nanoTime()
        val world = block.world.name
        for (side in SIDES) {
            val found = recent[Spot(world, block.x + side[0], block.y + side[1], block.z + side[2])] ?: continue
            if (now - found.at <= KEEP) return found
        }
        return null
    }

    private companion object {
        val KEEP = TimeUnit.SECONDS.toNanos(1)
        const val TIDY_AT = 4_096
        val SIDES = arrayOf(intArrayOf(0, 1, 0), intArrayOf(0, -1, 0), intArrayOf(1, 0, 0), intArrayOf(-1, 0, 0), intArrayOf(0, 0, 1), intArrayOf(0, 0, -1))
    }
}
