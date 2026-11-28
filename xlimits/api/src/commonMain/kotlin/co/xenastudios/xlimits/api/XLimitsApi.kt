package co.xenastudios.xlimits.api

import co.xenastudios.core.api.Location
import co.xenastudios.core.api.WorldRef
import co.xenastudios.core.api.event.EventBus
import co.xenastudios.core.api.event.Subscription
import kotlin.concurrent.Volatile
import kotlin.jvm.JvmField
import kotlin.jvm.JvmStatic

/**
 * xLimits' developer API, the same on every platform: what's counted in a chunk against each
 * limit, the limits themselves, and limits of other plugins' own for an area (a plot, a claim,
 * an arena) through [LimitProvider]s.
 *
 * Get it with [XLimits.api]; on Bukkit it's also in the services manager. Counts are per chunk
 * and belong to the thread that owns the chunk (Folia's region), so [count] and [limit] are
 * asked there; every other call is any thread.
 *
 * @since 1.0.0
 */
public interface XLimitsApi {
    /** xLimits' version, such as `1.0.0`. */
    public val version: String

    /** Where xLimits' events are heard ([co.xenastudios.xlimits.api.event]). On Bukkit they're Bukkit events too. */
    public val events: EventBus

    /**
     * How many of [group] (an entity group such as `animals`, an entity type such as `cow`, a
     * block group or a block such as `hopper`) are in [chunk] now, as xLimits counts them: from
     * a count at most about a second old, plus what it allowed since. `-1` for a name no limit
     * uses, or a chunk that isn't loaded. On the thread that owns [chunk].
     */
    public fun count(chunk: ChunkRef, group: String): Int

    /**
     * The limit on [group] in [chunk] now: the config's, as the load profile on there changes
     * it, then as the [LimitProvider]s change it. [XLimits.NO_LIMIT] where there's none. On the
     * thread that owns [chunk].
     */
    public fun limit(chunk: ChunkRef, group: String): Int

    /** The names of every limit the config sets (entity groups and types, block groups and blocks). Any thread. */
    public fun limits(): List<String>

    /** The load profile on in [chunk] (`normal` when none is): one for the server, or on Folia, its region's. Any thread. */
    public fun activeProfile(chunk: ChunkRef): String

    /**
     * Adds [provider], asked for every limit xLimits checks until the returned subscription is
     * cancelled. Providers are asked in the order they were added, each given the limit the one
     * before it returned. Any thread.
     */
    public fun register(provider: LimitProvider): Subscription
}

/**
 * A plugin's own limits for an area: a bigger plot that allows a bigger farm, an arena with no
 * limits. Asked on the thread that owns [location], every time xLimits checks a limit there, so
 * it answers from memory.
 *
 * @since 1.0.0
 */
public fun interface LimitProvider {
    /**
     * The limit on [group] (an entity group or type, a block group or block, `tnt`,
     * `falling_blocks`, `portal_items` or `pets`) at [location]: [configured] to leave it as it
     * is, another number to change it, or [XLimits.NO_LIMIT] to exempt the area.
     */
    public fun limit(location: Location, group: String, configured: Int): Int
}

/**
 * A chunk: 16 by 16 blocks of a [world], at chunk coordinates [x] and [z]. What counts are kept
 * by, and what Folia's regions are made of. Immutable; equal when every part is.
 *
 * @since 1.0.0
 */
public class ChunkRef(
    public val world: WorldRef,
    public val x: Int,
    public val z: Int,
) {
    /** The block in the middle of the chunk at height [y], for asking about the chunk as a place. */
    public fun center(y: Double = 64.0): Location = Location(world, (x shl 4) + 8.0, y, (z shl 4) + 8.0)

    override fun equals(other: Any?): Boolean = other is ChunkRef && other.x == x && other.z == z && other.world == world

    override fun hashCode(): Int = (world.hashCode() * 31 + x) * 31 + z

    override fun toString(): String = "$world $x,$z"

    public companion object {
        /** The chunk [location] is in. */
        @JvmStatic
        public fun of(location: Location): ChunkRef = ChunkRef(location.world, location.chunkX, location.chunkZ)
    }
}

/** What a limit is on, in the events. */
public enum class LimitKind {
    /** Mobs, vehicles, armour stands and item frames per chunk. */
    ENTITY,

    /** Placed blocks per chunk. */
    BLOCK,

    /** Primed TNT per chunk. */
    TNT,

    /** Falling sand, gravel and concrete powder per chunk. */
    FALLING_BLOCK,

    /** Items and other entities through one portal, per few seconds. */
    PORTAL,

    /** Tamed mobs per owner. */
    PET,

    /** How often one redstone block changes, per few seconds. */
    REDSTONE,
}

/** How a new entity came to be, in [co.xenastudios.xlimits.api.event.LimitReachedEvent]. */
public enum class SpawnRoute {
    /** Natural spawning, raids and patrols. */
    NATURAL,

    /** A monster spawner or trial spawner. */
    SPAWNER,

    /** Two animals bred. */
    BREEDING,

    /** A thrown chicken egg. */
    EGG,

    /** A player used a spawn egg. */
    SPAWN_EGG,

    /** A dispenser: a spawn egg, a boat, a minecart or an armour stand. */
    DISPENSER,

    /** A player placed a boat, minecart, armour stand, item frame or painting. */
    PLACED,

    /** Anything else: iron golems, snow golems, withers, slimes splitting, plugins and commands. */
    OTHER,
}

/** What farm slowing does to the mobs of a crammed farm. */
public enum class SlowAction {
    /** They don't push each other: the usual cause of a crammed pen's cost. */
    NO_COLLISIONS,

    /** They stop pathfinding and looking for targets, but still breed, drop and can be killed. */
    NO_AI,

    /** They don't breed. */
    NO_BREEDING,
}

/**
 * Where to get xLimits' API: `XLimits.api()`, from Kotlin and Java alike.
 *
 * @since 1.0.0
 */
public object XLimits {
    /** What [XLimitsApi.limit] and a [LimitProvider] say for no limit at all. */
    public const val NO_LIMIT: Int = Int.MAX_VALUE

    /** Places blocks, uses spawn eggs and places vehicles past every limit. Nobody has it by default, operators included. */
    public const val BYPASS_PERMISSION: String = "xlimits.bypass"

    /**
     * The scoreboard tag on every mob farm slowing changed, so `/xlimits restore` and a restart
     * find them: `/tag @e[tag=xlimits.slowed] list`.
     */
    @JvmField
    public val SLOWED_TAG: String = "xlimits.slowed"

    @Volatile
    private var running: XLimitsApi? = null

    /**
     * xLimits' API.
     *
     * @throws IllegalStateException if xLimits isn't installed and enabled: add it to your
     *   plugin's `depend` (or `softdepend`, and check [isEnabled] first)
     */
    @JvmStatic
    public fun api(): XLimitsApi =
        running ?: throw IllegalStateException("xLimits isn't enabled. Add it to depend (or softdepend and check XLimits.isEnabled() first).")

    /** Whether xLimits is enabled, so [api] answers. */
    @JvmStatic
    public fun isEnabled(): Boolean = running != null

    /** Made available by xLimits as it's enabled, and taken away as it's disabled: not for other plugins. */
    @JvmStatic
    public fun register(api: XLimitsApi?) {
        running = api
    }
}
