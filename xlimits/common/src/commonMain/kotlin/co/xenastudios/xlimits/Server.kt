package co.xenastudios.xlimits

import co.xenastudios.core.api.Location
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.text.Component
import co.xenastudios.xlimits.api.ChunkRef
import co.xenastudios.core.common.world.BlockChange
import co.xenastudios.core.common.world.SpawnReason
import co.xenastudios.xlimits.api.SlowAction
import co.xenastudios.xlimits.api.SpawnRoute

/**
 * What xLimits asks of the server it runs on: written by each adapter (Bukkit, Fabric). The
 * shared code decides what's over a limit and when; the adapter counts, listens and changes
 * mobs. Each call says the thread it's on: a chunk's is the thread that owns it (Folia's region).
 */
public interface LimitsServer {
    /** What this server can do, found once at startup. */
    public val features: Features

    /** The players online now. Any thread. */
    public fun online(): Collection<PlayerRef>

    /** How many players are online. Any thread. */
    public fun onlineCount(): Int

    /** [player]'s name, if they're online. Any thread. */
    public fun name(player: PlayerRef): String?

    /** Whether online [player] has [node]. Any thread. */
    public fun hasPermission(player: PlayerRef, node: String): Boolean

    /** Sends [message] to online [player]. Any thread. */
    public fun send(player: PlayerRef, message: Component)

    /** Sends [message] to the console. Any thread. */
    public fun console(message: Component)

    /** Where online [player] is. On [player]'s thread. */
    public fun location(player: PlayerRef): Location?

    /**
     * Counts the entities in [chunk] into [census] ([Census.add] for each), or `false` if the chunk
     * isn't loaded. On the thread that owns [chunk]. The one full look at a chunk's entities
     * xLimits makes, at most about once a second per chunk.
     */
    public fun census(chunk: ChunkRef, census: Census): Boolean

    /**
     * Counts the blocks in [chunk] whose ids are in [ids], and calls [done] with them on the thread
     * that owns [chunk], then or later: block entities (hoppers, spawners) straight from the
     * chunk, plain blocks from a snapshot counted off the server's threads. The count is of the
     * chunk as it is now, including a block whose placing is being checked if the server has
     * already put it there ([Features.placedBeforeCheck]). `null` if it isn't loaded.
     */
    public fun countBlocks(chunk: ChunkRef, ids: Set<String>, done: (Map<String, Int>?) -> Unit)

    /**
     * Slows ([slow] `true`) or restores the mobs in [chunk] that [select] picks, by [actions],
     * marking each it changes with [co.xenastudios.xlimits.api.XLimits.SLOWED_TAG] (and restoring
     * only those marked). Never named, tamed or leashed mobs; villagers only with [villagers].
     * Returns how many it changed. On the thread that owns [chunk].
     */
    public fun slow(chunk: ChunkRef, select: (EntityKind) -> Boolean, actions: Set<SlowAction>, villagers: Boolean, slow: Boolean): Int

    /**
     * Restores every slowed mob in every loaded chunk (`/xlimits restore`), each chunk on its own
     * thread, and calls [done] with how many, on any thread.
     */
    public fun restoreAll(done: (Int) -> Unit)

    /**
     * The busiest loaded chunks by [what] ([Busiest]): every loaded chunk, or with [around], those
     * within a few chunks of that player. Each chunk is counted on its own thread; [done] gets the
     * results on any thread. On Folia, without [around], the chunks around every online player.
     */
    public fun busiest(what: Int, around: PlayerRef?, done: (List<ChunkStat>) -> Unit)

    /** Breaks the block at [location] as if mined, so it drops as an item (a redstone clock). On its thread. */
    public fun breakBlock(location: Location)

    /** Teleports online [player] to [location] (on top of the highest block there); `false` if its world isn't loaded. Any thread. */
    public fun teleport(player: PlayerRef, location: Location): Boolean

    /** The TPS where [chunk] is: the server's, or on Folia its region's. `null` where the server can't say. On [chunk]'s thread. */
    public fun tps(chunk: ChunkRef): Double?

    /** The server's milliseconds per tick, averaged over the last few seconds. `null` where it can't say (Folia, Spigot). Any thread. */
    public fun mspt(): Double?

    /** The server's own settings next to xLimits' limits in [chunk]'s world, as a name and its value each, for `/xlimits here`. */
    public fun serverSettings(chunk: ChunkRef): List<Pair<String, String>>

    /** Whether a plugin called [name] is installed and enabled (xRewind, for the alerts' button). Any thread. */
    public fun hasPlugin(name: String): Boolean

    /** Starts the listener for [switch] ([Switch]), until it's closed. Asks [view]. */
    public fun listen(switch: Int, view: LimitsView): AutoCloseable

    /** What a server has. */
    public class Features(
        /** The switches this server has listeners for ([Switch]); the rest do nothing here. */
        public val switches: Set<Int>,
        /** The farm actions this server can do; the others are left out with a warning. */
        public val actions: Set<SlowAction>,
        /** Whether a block is already in the world when its placing is checked (Bukkit), or not yet (Fabric). */
        public val placedBeforeCheck: Boolean,
        /** Whether a load profile is per region (Folia) rather than the server's. */
        public val perRegion: Boolean,
        /** What this server can't do, for `/xlimits debug` and the startup warnings, in English. */
        public val gaps: List<String> = emptyList(),
    )
}

/** The switches the adapters listen for, each only while its feature is on. */
public object Switch {
    /** Spawns, breeding and placed entities: calls [LimitsView.spawning]. */
    public const val ENTITIES: Int = 0

    /** Chunks loading: calls [LimitsView.chunkLoaded], for farm slowing and pets. */
    public const val CHUNKS: Int = 1

    /** Blocks placed and removed: [LimitsView.placing] and [LimitsView.removed]. */
    public const val BLOCKS: Int = 2

    /** Redstone changing: [LimitsView.redstone]. */
    public const val REDSTONE: Int = 3

    /** TNT priming and blocks starting to fall: [LimitsView.priming] and [LimitsView.falling]. */
    public const val TNT: Int = 4

    /** Entities through portals: [LimitsView.portal]. */
    public const val PORTALS: Int = 5

    /** Taming and tamed mobs dying: [LimitsView.taming] and [LimitsView.petGone]. */
    public const val PETS: Int = 6

    /** Each switch's name, as `/xlimits debug` shows it. */
    public val NAMES: List<String> = listOf("entities", "chunks", "blocks", "redstone", "tnt", "portals", "pets")
}

/** What `/xlimits top` and `near` sort by. */
public object Busiest {
    public const val ENTITIES: Int = 0
    public const val BLOCKS: Int = 1
    public const val REDSTONE: Int = 2

    public val NAMES: List<String> = listOf("entities", "blocks", "redstone")
}

/** One chunk in `/xlimits top`: its entities, its block entities (hoppers, chests, spawners), and its redstone changes lately. */
public class ChunkStat(public val chunk: ChunkRef, public val entities: Int, public val blocks: Int, public val redstone: Int = 0) {
    /** The count [what] ([Busiest]) sorts by. */
    public fun of(what: Int): Int = when (what) {
        Busiest.BLOCKS -> blocks
        Busiest.REDSTONE -> redstone
        else -> entities
    }
}

/**
 * A kind of entity as xLimits groups it: its Minecraft [id] (`cow`, `zombified_piglin`) and the
 * built-in groups it's in ([Category]). Each adapter makes one per entity type, once, and passes
 * the same object every time, so what it matches is worked out once per config.
 */
public class EntityKind(public val id: String, public val categories: Int) {
    // The rules it matches, worked out once per config ([Rules.generation]); any thread may
    // work them out, and each publishes a whole, immutable answer.
    @kotlin.concurrent.Volatile
    internal var matched: Rules.Matched? = null

    /** Whether it's in [category]. */
    public fun isIn(category: Int): Boolean = categories and category != 0

    override fun toString(): String = id
}

/** The built-in entity groups, as [EntityKind.categories] bits, and their names in the config. */
public object Category {
    public const val ANIMAL: Int = 1
    public const val MONSTER: Int = 2
    public const val VILLAGER: Int = 4
    public const val WATER: Int = 8
    public const val AMBIENT: Int = 16
    public const val GOLEM: Int = 32
    public const val VEHICLE: Int = 64
    public const val ARMOR_STAND: Int = 128
    public const val ITEM_FRAME: Int = 256

    /** Primed TNT, for `tnt.primed`. */
    public const val TNT: Int = 512

    /** A falling block, for `tnt.falling`. */
    public const val FALLING: Int = 1024

    /** A player: never counted. */
    public const val PLAYER: Int = 2048

    /** The groups' names, as the config writes them, and their bits. */
    public val GROUPS: Map<String, Int> = linkedMapOf(
        "animals" to ANIMAL, "monsters" to MONSTER, "villagers" to VILLAGER, "water" to WATER, "ambient" to AMBIENT,
        "golems" to GOLEM, "vehicles" to VEHICLE, "armor_stands" to ARMOR_STAND, "item_frames" to ITEM_FRAME,
    )
}

/**
 * What an adapter's [LimitsServer.census] fills: how many of each [EntityKind] are in a chunk,
 * and its tamed mobs and their owners. Reused, so a count allocates nothing once it's warm.
 */
public class Census {
    // Per kind: how many, and how many of them farm slowing marked.
    internal val kinds = HashMap<EntityKind, IntArray>()
    internal val pets = ArrayList<Pet>()
    internal var total = 0

    /** One entity of [kind]: [slowed] if it has farm slowing's tag; [owner] and [id] for a tamed one. */
    public fun add(kind: EntityKind, slowed: Boolean = false, owner: PlayerRef? = null, id: String? = null) {
        if (kind.isIn(Category.PLAYER)) return
        total++
        val box = kinds.getOrPut(kind) { IntArray(2) }
        box[0]++
        if (slowed) box[1]++
        if (owner != null && id != null) pets += Pet(owner, id, kind.id)
    }

    internal class Pet(val owner: PlayerRef, val id: String, val type: String)
}

/**
 * What the adapters' listeners ask, on the thread that owns the place their event is at. Each
 * answers from memory, counting a chunk at most about once a second; `true` lets the thing
 * happen, `false` refuses it (the adapter cancels its event).
 */
public interface LimitsView {
    /** A new entity of [kind] at [location], by [route] ([co.xenastudios.xlimits.api.SpawnRoute]), and the [player] who caused it if any. */
    public fun spawning(kind: EntityKind, location: Location, route: co.xenastudios.xlimits.api.SpawnRoute, player: PlayerRef?): Boolean

    /** [chunk] loaded with its entities: farm slowing is checked, and its pets are seen. */
    public fun chunkLoaded(chunk: ChunkRef)

    /** [chunk] unloaded: its counts are forgotten. */
    public fun chunkUnloaded(chunk: ChunkRef)

    /** [player] places a block of [block] (its id, `hopper`) at [location]. */
    public fun placing(block: String, location: Location, player: PlayerRef?): Boolean

    /** A block of [block] at [location] was broken, blown up or otherwise removed. */
    public fun removed(block: String, location: Location)

    /** A block of [block] came to be at [location] by other means than placing, such as a piston's push: counted, never refused. */
    public fun added(block: String, location: Location)

    /** Whether the block at [location] may change its power now; `false` breaks it (a clock). */
    public fun redstone(location: Location): Boolean

    /** TNT at [location] is about to be primed. */
    public fun priming(location: Location, player: PlayerRef?): Boolean

    /** The block at [location] is about to start falling. */
    public fun falling(location: Location): Boolean

    /** An entity of [kind] at [location] is about to go through a portal (never a player or what one rides). */
    public fun portal(kind: EntityKind, location: Location): Boolean

    /** [owner] is about to tame a mob of [kind] at [location]. */
    public fun taming(owner: PlayerRef, kind: EntityKind, location: Location, pet: String): Boolean

    /** The tamed mob [pet] died or was released. Any thread. */
    public fun petGone(pet: String)
}

/** The core's world hooks' spawn reasons as xLimits' routes, for every adapter. */
public object SpawnRoutes {
    public fun of(reason: SpawnReason, kind: EntityKind): SpawnRoute = when (reason) {
        SpawnReason.NATURAL -> SpawnRoute.NATURAL
        SpawnReason.SPAWNER -> SpawnRoute.SPAWNER
        SpawnReason.BREEDING -> SpawnRoute.BREEDING
        SpawnReason.EGG -> SpawnRoute.EGG
        SpawnReason.SPAWN_EGG -> SpawnRoute.SPAWN_EGG
        SpawnReason.DISPENSER -> SpawnRoute.DISPENSER
        SpawnReason.PLACED -> SpawnRoute.PLACED
        // A vehicle, armour stand or item frame comes to be by being placed, by a player or a dispenser.
        else -> if (kind.isIn(Category.VEHICLE or Category.ARMOR_STAND or Category.ITEM_FRAME)) SpawnRoute.PLACED else SpawnRoute.OTHER
    }

    /** A block id without its namespace or state: `hopper`. */
    public fun blockId(type: String): String = type.substringAfter(':').substringBefore('[')
}

/** The core's world hooks' block changes as xLimits counts them, for every adapter. */
public object BlockChanges {
    /**
     * [event] at [at], from the hook for players' changes ([placing]: checked against the caps,
     * and refused past them) or from the one for everything else (counted after the fact).
     *
     * A block arrives wherever what's there after isn't air and isn't what was there: over air,
     * and over a fluid or a block the new one replaces (a snow layer, grass, a fern), which the
     * core's [BlockChange.isPlace] leaves out. What it replaced is counted off.
     */
    public fun on(view: LimitsView, event: BlockChange, at: Location, placing: Boolean) {
        val before = event.before
        val after = event.after
        if (event.isCancelled || before.type == after.type) return
        if (placing && !after.isAir && !view.placing(SpawnRoutes.blockId(after.type), at, event.player)) {
            event.isCancelled = true
            return
        }
        if (!before.isAir) view.removed(SpawnRoutes.blockId(before.type), at)
        // A piston's push puts the block where it moves to.
        if (!placing && !after.isAir) view.added(SpawnRoutes.blockId(after.type), at)
    }
}
