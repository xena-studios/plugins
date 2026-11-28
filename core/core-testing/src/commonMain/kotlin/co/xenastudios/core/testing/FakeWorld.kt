package co.xenastudios.core.testing

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.WorldRef
import co.xenastudios.core.api.event.EventPriority
import co.xenastudios.core.common.platform.Log
import co.xenastudios.core.common.world.BlockChange
import co.xenastudios.core.common.world.BlockData
import co.xenastudios.core.common.world.ChangeCause
import co.xenastudios.core.common.world.ContainerChange
import co.xenastudios.core.common.world.EntityChange
import co.xenastudios.core.common.world.EntityChangeKind
import co.xenastudios.core.common.world.EntityDeath
import co.xenastudios.core.common.world.EntitySpawn
import co.xenastudios.core.common.world.ItemData
import co.xenastudios.core.common.world.SpawnReason
import co.xenastudios.core.common.world.WorldHook
import co.xenastudios.core.common.world.WorldHooks

/**
 * The world hooks on the fake platform, for a plugin's tests: the test says what happens
 * ([change], [spawn], [death], [container], [tame]), the plugin's listeners hear it on the
 * test's thread as a platform would deliver it, and the result says whether it went through.
 * [started] is what the platform was asked to hear, so a test can check a module that's off
 * listens to nothing.
 *
 * ```kotlin
 * val world = FakeWorld(platform.log)
 * plugin.enable(world)
 * world.change(at = FakeWorld.at("world", 1, 64, 1), before = "minecraft:stone", after = "minecraft:air", player = alex, playerName = "Alex")
 * ```
 */
public class FakeWorld(
    log: Log,
    priority: EventPriority = EventPriority.NORMAL,
    /** The kinds of change this fake hears, every one unless a test says. */
    override val supported: Set<WorldHook> = WorldHook.entries.toSet(),
) : WorldHooks(priority, log) {

    private val starts = ArrayList<WorldHook>()

    /** What the platform was asked to start hearing, in order (a hook started again is listed again). */
    public val started: List<WorldHook> get() = starts

    /** What the platform hears now. */
    public val active: Set<WorldHook> get() = listening

    override fun start(hook: WorldHook) {
        starts += hook
    }

    override fun stop(hook: WorldHook) {}

    /** The world object listeners see as `nativeWorld`. */
    public val nativeWorld: Any = Any()

    /**
     * A block at [at] changes from [before] to [after] (states as text,
     * `minecraft:chest[facing=north]`) because of [cause]. Returns the event the listeners
     * heard, or `null` if none listens for [cause]; check its `isCancelled`.
     */
    public fun change(
        at: Spot,
        before: String,
        after: String,
        cause: ChangeCause = ChangeCause.PLAYER,
        player: PlayerRef? = null,
        playerName: String? = null,
        source: String? = null,
        cancellable: Boolean = true,
    ): BlockChange? {
        if (!hears(cause)) return null
        return fire(BlockChange(at.world, at.x, at.y, at.z, block(before), block(after), cause, player, playerName, source, cancellable, nativeWorld))
    }

    /**
     * [amount] of [item] go into (positive) or out of a container at [at], by [player] or, with
     * none, a hopper ([source]). Returns whether anything heard it.
     */
    public fun container(at: Spot, item: String, amount: Int, player: PlayerRef? = null, playerName: String? = null, source: String? = null): Boolean {
        if (!hears(if (player == null) WorldHook.CONTAINER_HOPPER else WorldHook.CONTAINER)) return false
        fire(ContainerChange(at.world, at.x, at.y, at.z, Item(item), amount, player, playerName, source, nativeWorld))
        return true
    }

    /** An entity of [type] is about to spawn at [at]. Returns the event, or `null` if nothing listens. */
    public fun spawn(at: Spot, type: String, reason: SpawnReason = SpawnReason.NATURAL, player: PlayerRef? = null): EntitySpawn? {
        if (!hears(WorldHook.ENTITY_SPAWN)) return null
        return fire(EntitySpawn(at.world, at.x, at.y, at.z, type, reason, player, Any()))
    }

    /** An entity of [type] died at [at], killed by [killer] if set. Returns whether anything heard it. */
    public fun death(at: Spot, type: String, killer: PlayerRef? = null, killerName: String? = null): Boolean {
        if (!hears(WorldHook.ENTITY_DEATH)) return false
        fire(EntityDeath(at.world, at.x, at.y, at.z, type, killer, killerName, Any()))
        return true
    }

    /** [player] is about to tame an entity of [type] at [at]. Returns the event, or `null` if nothing listens. */
    public fun tame(at: Spot, type: String, player: PlayerRef, playerName: String): EntityChange? {
        if (!hears(WorldHook.ENTITY_CHANGE)) return null
        return fire(EntityChange(at.world, at.x, at.y, at.z, type, EntityChangeKind.TAMED, player, playerName, Any()))
    }

    /** A block position in a world, for the methods above. */
    public class Spot(public val world: WorldRef, public val x: Int, public val y: Int, public val z: Int)

    /** A block state as the fake platform has it: [data] as given, its type the part before `[`. */
    public class Block(override val data: String) : BlockData() {
        override val type: String = data.substringBefore('[')
        override val isAir: Boolean = type == "minecraft:air" || type == "minecraft:cave_air" || type == "minecraft:void_air"
        override val native: Any get() = data
    }

    /** An item as the fake platform has it. */
    public class Item(override val type: String) : ItemData() {
        override val native: Any get() = type
    }

    public companion object {
        /** The spot [x] [y] [z] in the world called [world]. */
        public fun at(world: String, x: Int, y: Int, z: Int): Spot = Spot(WorldRef.of(world), x, y, z)

        /** The block state [data] as fake platform data. */
        public fun block(data: String): BlockData = Block(data)
    }
}
