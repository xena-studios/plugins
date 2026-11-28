package co.xenastudios.core.common.world

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.WorldRef
import co.xenastudios.core.api.event.Cancellable
import co.xenastudios.core.api.event.Event
import co.xenastudios.core.api.event.EventType
import kotlin.jvm.JvmField
import kotlin.jvm.JvmStatic

/**
 * What changed a block, which decides which [WorldHooks] listeners hear it: a listener names
 * the causes it wants, and the platform listens only for those (a plugin that logs only what
 * players do never hears a flowing fluid).
 */
public enum class ChangeCause {
    /** A player placed, broke or used a block: by hand, with a bucket, flint and steel, bone meal. */
    PLAYER,

    /** An explosion: TNT ([BlockChange.player] is who lit it, if a player did), a creeper, a bed. */
    EXPLOSION,

    /** Fire burnt the block away. Fire spreading into air isn't a change listeners hear. */
    FIRE,

    /** A piston pushed or pulled blocks: the spots they arrive at, and those they leave empty. */
    PISTON,

    /** Water or lava flowed into a block and destroyed it (a torch, a crop). Flowing into air or itself isn't heard. */
    FLUID,

    /** An entity changed the block: an enderman, a wither, falling sand landing, a mob trampling farmland. */
    ENTITY,

    /** Leaves decayed. */
    DECAY,
}

/**
 * A block state as data, on any platform: what [type] of block, with which properties
 * ([data]). Made by the platform for each change, around the platform's own object
 * ([native]); [data] is worked out only when it's read, so a listener that checks only the
 * [type] doesn't pay for the rest.
 */
public abstract class BlockData {
    /** The block's id, `minecraft:oak_stairs`. */
    public abstract val type: String

    /**
     * The whole state as Minecraft writes it, `minecraft:oak_stairs[facing=north,half=bottom]`:
     * what a block logger stores, and what the platform reads back to set it again.
     */
    public abstract val data: String

    /** Whether it's air (any kind of it): a block broken leaves air. */
    public abstract val isAir: Boolean

    /** The platform's own object: Bukkit's `BlockData`, Minecraft's `BlockState`. For a plugin's own adapter. */
    public abstract val native: Any

    override fun toString(): String = data
}

/**
 * An item, on any platform: its [type], and the platform's own stack ([native], one of it),
 * which a plugin's adapter saves the way that platform does.
 */
public abstract class ItemData {
    /** The item's id, `minecraft:diamond`. */
    public abstract val type: String

    /** The platform's own stack, of one: Bukkit's `ItemStack`, Minecraft's `ItemStack`. A copy, the listener's to keep. */
    public abstract val native: Any

    override fun toString(): String = type
}

/**
 * A block is about to change, or has (see below), at [x] [y] [z] in [world], from [before] to
 * [after], because of [cause]. Heard through [WorldHooks.onBlockChange], on the thread that
 * owns the place (its region's on Folia, the server thread elsewhere), so a listener may read
 * the world there. One event per block: an explosion is one for each block it destroys, a
 * piston one for each spot a block arrives at or leaves.
 *
 * **Who did it.** [player] is the player behind it, if one is: who placed or broke it, lit
 * the TNT, emptied the bucket. Otherwise [source] names what did it: an entity's type
 * (`minecraft:creeper`, `minecraft:tnt`, `minecraft:enderman`), a fluid (`minecraft:water`),
 * a block (`minecraft:fire`, `minecraft:piston`, `minecraft:red_bed`). One of the two is set.
 *
 * **Before or after.** A change to air (a break, a burn, a block an explosion destroys) is
 * heard while the block, and whatever it holds (a chest's items, a sign's text), is still
 * there, so a listener can read it. A place may already be in the world as it's heard: on
 * Bukkit the block is put down before plugins hear it, and put back if one stops it.
 *
 * **Stopping it.** [isCancelled] stops the change where the platform can stop it cleanly
 * ([cancellable]); elsewhere setting it does nothing. Listeners at
 * [co.xenastudios.core.api.event.EventPriority.MONITOR] see what was decided, and must not
 * change it. Where a change can be stopped: DECISIONS.md, "The world hooks".
 *
 * Don't keep the event: what it says is worth copying, its [nativeWorld] isn't.
 */
public class BlockChange(
    /** The world it's in. */
    @JvmField public val world: WorldRef,
    /** The block's x. */
    @JvmField public val x: Int,
    /** The block's y. */
    @JvmField public val y: Int,
    /** The block's z. */
    @JvmField public val z: Int,
    /** What was there. */
    @JvmField public val before: BlockData,
    /** What will be (or is now) there. */
    @JvmField public val after: BlockData,
    /** What changed it. */
    @JvmField public val cause: ChangeCause,
    /** The player behind it, if a player is. */
    @JvmField public val player: PlayerRef?,
    /** Their name, as the server knows it, when [player] is set. */
    @JvmField public val playerName: String?,
    /** What did it when no player did, as an id: `minecraft:creeper`, `minecraft:water`, `minecraft:fire`. */
    @JvmField public val source: String?,
    /** Whether cancelling it stops it. */
    @JvmField public val cancellable: Boolean,
    /** The platform's own world: Bukkit's `World`, Minecraft's `ServerLevel`. For a plugin's own adapter. */
    @JvmField public val nativeWorld: Any,
) : Cancellable {

    private var cancelled = false

    override var isCancelled: Boolean
        get() = cancelled
        set(value) {
            if (cancellable) cancelled = value
        }

    /** Whether the block goes: [after] is air and [before] isn't. */
    public val isBreak: Boolean get() = after.isAir && !before.isAir

    /** Whether a block arrives: [before] is air (or a fluid a block replaces, as Minecraft does) and [after] isn't. */
    public val isPlace: Boolean get() = before.isAir && !after.isAir

    /** The chunk's x: 16 blocks to a chunk. */
    public val chunkX: Int get() = x shr 4

    /** The chunk's z. */
    public val chunkZ: Int get() = z shr 4

    override fun toString(): String = "BlockChange($cause ${player ?: source} $world $x $y $z $before -> $after)"

    public companion object {
        private val TYPES: Array<EventType<BlockChange>> =
            Array(ChangeCause.entries.size) { EventType.of("core:block_change_${ChangeCause.entries[it].name.lowercase()}") }

        /** The bus's event type for changes of [cause]: one per cause, so a listener hears only its own. */
        @JvmStatic
        public fun type(cause: ChangeCause): EventType<BlockChange> = TYPES[cause.ordinal]
    }
}

/**
 * Items went into or out of a container at [x] [y] [z]: [amount] of [item], positive put in,
 * negative taken out. Heard once it's happened, through [WorldHooks.onContainerChange].
 *
 * A player's visit is heard as it ends: what the container held is noted as they open it and
 * compared as they close it, so a visit is one event per kind of item that changed, not one
 * per click. Two players in one chest at once share the blame, as CoreProtect's do. A double
 * chest is the half Bukkit calls its left side (the one Minecraft makes first), on every
 * platform. Hoppers and droppers ([source] `minecraft:hopper`) are heard per move.
 */
public class ContainerChange(
    @JvmField public val world: WorldRef,
    @JvmField public val x: Int,
    @JvmField public val y: Int,
    @JvmField public val z: Int,
    /** The kind of item, one of it. */
    @JvmField public val item: ItemData,
    /** How many: positive put in, negative taken out. Never 0. */
    @JvmField public val amount: Int,
    /** The player who did it, if one did. */
    @JvmField public val player: PlayerRef?,
    /** Their name, when [player] is set. */
    @JvmField public val playerName: String?,
    /** What did it when no player did: `minecraft:hopper`. */
    @JvmField public val source: String?,
    /** The platform's own world. */
    @JvmField public val nativeWorld: Any,
) : Event {
    override fun toString(): String = "ContainerChange(${player ?: source} $world $x $y $z $amount $item)"

    public companion object {
        /** A player's change. */
        @JvmField
        public val TYPE: EventType<ContainerChange> = EventType.of("core:container_change")

        /** A hopper's or a dropper's move. */
        @JvmField
        public val HOPPER_TYPE: EventType<ContainerChange> = EventType.of("core:container_change_hopper")
    }
}

/** Why an entity is being spawned, as far as the platform can tell. */
public enum class SpawnReason {
    /** The world spawned it: mobs in the dark, animals in new chunks, a raid, a patrol. */
    NATURAL,

    /** A mob spawner (or a trial spawner). */
    SPAWNER,

    /** Two animals bred (or villagers did). */
    BREEDING,

    /** A thrown egg hatched. */
    EGG,

    /** A player used a spawn egg. */
    SPAWN_EGG,

    /** A dispenser put it out: a spawn egg, a boat, an armour stand. */
    DISPENSER,

    /** A player placed it: a boat, a minecart, an armour stand, an end crystal. */
    PLACED,

    /** A command: `/summon`. */
    COMMAND,

    /** Anything else: TNT primed, sand starting to fall, an item dropped, a projectile, a mob splitting. */
    OTHER,
}

/**
 * An entity of [type] is about to appear at [x] [y] [z] in [world], for [reason]. Heard
 * through [WorldHooks.onEntitySpawn], on the thread that owns the place, before it's in the
 * world: [isCancelled] stops it, and nothing appears. Entities loaded with their chunk aren't
 * spawned: they were already there.
 */
public class EntitySpawn(
    @JvmField public val world: WorldRef,
    @JvmField public val x: Int,
    @JvmField public val y: Int,
    @JvmField public val z: Int,
    /** The entity's type, `minecraft:zombie`. */
    @JvmField public val type: String,
    /** Why it's spawning. */
    @JvmField public val reason: SpawnReason,
    /** The player behind it (who used the egg, placed the boat), if the platform knows. */
    @JvmField public val player: PlayerRef?,
    /** The platform's own entity, not in the world yet. */
    @JvmField public val nativeEntity: Any,
) : Cancellable {
    override var isCancelled: Boolean = false

    /** The chunk's x. */
    public val chunkX: Int get() = x shr 4

    /** The chunk's z. */
    public val chunkZ: Int get() = z shr 4

    override fun toString(): String = "EntitySpawn($type $reason $world $x $y $z)"

    public companion object {
        @JvmField
        public val TYPE: EventType<EntitySpawn> = EventType.of("core:entity_spawn")
    }
}

/**
 * A living entity of [type] died at [x] [y] [z]: killed by [killer] if a player had hit it
 * (as Minecraft credits the kill), or by anything else. Heard once it has, through
 * [WorldHooks.onEntityDeath], on the thread that owns it.
 */
public class EntityDeath(
    @JvmField public val world: WorldRef,
    @JvmField public val x: Int,
    @JvmField public val y: Int,
    @JvmField public val z: Int,
    /** The entity's type, `minecraft:zombie`; `minecraft:player` for a player. */
    @JvmField public val type: String,
    /** The player credited with the kill, if any. */
    @JvmField public val killer: PlayerRef?,
    /** Their name, when [killer] is set. */
    @JvmField public val killerName: String?,
    /** The platform's own entity. */
    @JvmField public val nativeEntity: Any,
) : Event {
    override fun toString(): String = "EntityDeath($type by $killer $world $x $y $z)"

    public companion object {
        @JvmField
        public val TYPE: EventType<EntityDeath> = EventType.of("core:entity_death")
    }
}

/** What happens to an entity in an [EntityChange]. */
public enum class EntityChangeKind {
    /** A player tamed it: a wolf, a cat, a parrot, a horse. */
    TAMED,
}

/**
 * Something is about to happen to an entity of [type] at [x] [y] [z]: see [kind]. Heard
 * through [WorldHooks.onEntityChange], on the thread that owns it; [isCancelled] stops it.
 */
public class EntityChange(
    @JvmField public val world: WorldRef,
    @JvmField public val x: Int,
    @JvmField public val y: Int,
    @JvmField public val z: Int,
    /** The entity's type, `minecraft:wolf`. */
    @JvmField public val type: String,
    /** What's happening to it. */
    @JvmField public val kind: EntityChangeKind,
    /** The player doing it. */
    @JvmField public val player: PlayerRef?,
    /** Their name, when [player] is set. */
    @JvmField public val playerName: String?,
    /** The platform's own entity. */
    @JvmField public val nativeEntity: Any,
) : Cancellable {
    override var isCancelled: Boolean = false

    override fun toString(): String = "EntityChange($kind $type by $player $world $x $y $z)"

    public companion object {
        @JvmField
        public val TYPE: EventType<EntityChange> = EventType.of("core:entity_change")
    }
}
