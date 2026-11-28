package co.xenastudios.neoskript.model

import co.xenastudios.core.api.Location
import co.xenastudios.core.api.WorldRef

/**
 * The game as NeoSkript's syntax sees it, the same on every platform: what an expression reads,
 * what an effect changes and which events a trigger hears. Each platform's adapter makes one at
 * startup, having looked once at what the server can do; nothing is decided per call.
 *
 * **Values are the platform's own objects.** A player is Bukkit's `Player` on Paper and
 * Minecraft's `ServerPlayer` on Fabric, passed around as [Any], and these interfaces are the
 * operations on them. There's no wrapper per value: a script's values cost what the platform's
 * do, compare as the platform's do, and on Bukkit stay what Skript's add-ons (SkBee) expect.
 * Where a platform has no object for something, its adapter makes a small one (a block on
 * Fabric is a level and a position).
 *
 * **Types and places are neutral.** Kinds of block, item and entity are [GameType]s by today's
 * id; places are the core's [Location] and [WorldRef]; text is a [String] in the spike (the
 * core's `Component` in N2).
 *
 * **Threads.** Every read and change here is made on the thread that owns the value
 * ([Threads.owns]); the compiled script checks once per trigger, and only where the platform
 * has more than one ([Threads.regions]).
 */
public interface Game {
    /** The server software, `Paper 1.21.11` or `Fabric 1.21.11`. */
    public val platform: String

    public val blockTypes: Types<BlockType>
    public val itemTypes: Types<ItemType>
    public val entityTypes: Types<EntityType>

    public val worlds: Worlds
    public val blocks: Blocks
    public val items: Items
    public val entities: Entities
    public val players: Players
    public val events: Events
    public val threads: Threads
}

/** Worlds: Bukkit's `World`, Minecraft's `ServerLevel`. */
public interface Worlds {
    public fun isWorld(value: Any?): Boolean

    /** The world's lasting name, for the core's places and for variables. */
    public fun ref(world: Any): WorldRef

    /** The loaded world [ref] names, or `null`. */
    public fun byRef(ref: WorldRef): Any?

    /** The world's name as scripts print it: `world`, `minecraft:overworld`. */
    public fun name(world: Any): String

    /** The worlds, the main one first. */
    public fun all(): List<Any>
}

/**
 * Blocks in the world, by place: Bukkit's `Block`; on Fabric the adapter's own (a level and a
 * position). Equal when their places are.
 */
public interface Blocks {
    public fun isBlock(value: Any?): Boolean

    /** The block at [x] [y] [z] in [world] (`block at`). */
    public fun at(world: Any, x: Int, y: Int, z: Int): Any

    /** The block at [location], or `null` if its world isn't loaded. */
    public fun at(location: Location): Any?

    /** What kind of block it is (`type of block`). */
    public fun type(block: Any): BlockType

    /** Makes it a [type] in its default state (`set block to stone`), as a player would see it change. */
    public fun setType(block: Any, type: BlockType)

    /** Its corner (Skript's `location of block` is its middle: NeoSkript's own syntax adds the half). */
    public fun location(block: Any): Location

    public fun world(block: Any): Any
}

/** Item stacks: Bukkit's `ItemStack`, Minecraft's `ItemStack`. Mutable, as both platforms' are. */
public interface Items {
    public fun isItem(value: Any?): Boolean

    /** A new stack of [amount] [type] (`16 dirt`). */
    public fun create(type: ItemType, amount: Int): Any

    public fun type(item: Any): ItemType

    /** How many (`item amount`). */
    public fun amount(item: Any): Int

    public fun setAmount(item: Any, amount: Int)
}

/**
 * Entities: Bukkit's `Entity`, Minecraft's `Entity`. A player is one too, and also a
 * [Players] value.
 */
public interface Entities {
    public fun isEntity(value: Any?): Boolean

    /** Whether it has health: a mob, an animal, a player. */
    public fun isLiving(value: Any?): Boolean

    /** Its UUID, as text: what the core's `Where.On` and `PlayerRef` take. */
    public fun uuid(entity: Any): String

    public fun type(entity: Any): EntityType

    /** Where it is, facing where it faces (`location of`). */
    public fun location(entity: Any): Location

    /** Its world (`world of`). */
    public fun world(entity: Any): Any

    /** Its name (`name of`): a player's name, a mob's custom name or its type's name. */
    public fun name(entity: Any): String

    /** Its health (`health of`), in Minecraft's points (Skript's hearts are half of these). */
    public fun health(entity: Any): Double

    public fun setHealth(entity: Any, health: Double)

    /** Damages it by [amount] points, as a generic hit would: platforms fire their damage event for it. */
    public fun damage(entity: Any, amount: Double)

    /** Kills it (`kill`): a living entity dies, with its death event; anything else is removed. */
    public fun kill(entity: Any)

    /**
     * Moves it to [to] (`teleport`). On a server with regions the move may finish on another
     * thread, later; [done] hears whether it did.
     */
    public fun teleport(entity: Any, to: Location, done: ((Boolean) -> Unit)? = null)

    /** Whether it's still in the world (not dead, not removed, not logged out). */
    public fun isValid(entity: Any): Boolean

    /** A new entity of [type] at [location]: for the spike's checks (`spawn`). */
    public fun spawn(type: EntityType, location: Location): Any?
}

/** Players: Bukkit's `Player`, Minecraft's `ServerPlayer`. */
public interface Players {
    public fun isPlayer(value: Any?): Boolean

    public fun name(player: Any): String

    /** Sends [text] to them (`send`). */
    public fun send(player: Any, text: String)

    /** Gives them [item] (`give`): into their inventory, what doesn't fit dropped at their feet. */
    public fun give(player: Any, item: Any)

    /** What's in their main hand (`tool`, `held item`), or `null` for nothing. */
    public fun heldItem(player: Any): Any?

    /** Everyone online. */
    public fun online(): List<Any>
}

/**
 * Which thread may touch what. On Paper and Fabric one thread owns everything; on Folia each
 * region of a world has its own, and an entity's is its region's.
 */
public interface Threads {
    /** Whether there's more than one owner: only then does a script need to check [owns]. */
    public val regions: Boolean

    /** Whether this thread may touch [value]: an entity, a block, a location or a world. */
    public fun owns(value: Any): Boolean

    /** Runs [action] on the thread that owns [value], soon (now never: a trigger carries on). */
    public fun runOn(value: Any, action: () -> Unit)
}
