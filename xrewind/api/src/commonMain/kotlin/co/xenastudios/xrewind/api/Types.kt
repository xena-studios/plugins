package co.xenastudios.xrewind.api

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.WorldRef
import kotlin.jvm.JvmStatic
import kotlin.time.Instant

/**
 * Who made a change: a player, or a source such as `#xplots` or `#tnt`, which has no player.
 * Lookups find it by name: `u:Steve` or `u:#xplots`.
 *
 * @since 2.0.0
 */
public class Actor private constructor(
    /** The player, or `null` for a source. */
    public val player: PlayerRef?,
    /** The player's name, or the source's, starting with `#`. */
    public val name: String,
) {
    init {
        require(name.isNotBlank() && name.length <= 64) { "An actor's name is 1 to 64 characters" }
        require((player == null) == name.startsWith("#")) {
            if (player == null) "A source's name starts with #, such as #xplots" else "A player's name doesn't start with #"
        }
    }

    /** Whether it's a player rather than a source. */
    public val isPlayer: Boolean get() = player != null

    override fun equals(other: Any?): Boolean = other is Actor && other.player == player && other.name == name

    override fun hashCode(): Int = (player?.hashCode() ?: 0) * 31 + name.hashCode()

    override fun toString(): String = name

    public companion object {
        /** A player, by their uuid and name. */
        @JvmStatic
        public fun player(player: PlayerRef, name: String): Actor = Actor(player, name)

        /** A source that isn't a player, such as your plugin: `xplots` and `#xplots` are the same. */
        @JvmStatic
        public fun source(name: String): Actor = Actor(null, if (name.startsWith("#")) name else "#$name")
    }
}

/** A kind of logged change, as `a:` in `/xrewind lookup` names them. @since 2.0.0 */
public enum class Action {
    /** A block broken or removed (`a:-block`). */
    BREAK,

    /** A block placed or changed (`a:+block`). */
    PLACE,

    /** A block used: a door, lever or button (`a:click`). */
    CLICK,

    /** Items put into a container (`a:+container`). */
    ADD,

    /** Items taken out of a container (`a:-container`). */
    REMOVE,

    /** An entity killed (`a:kill`). */
    KILL,

    /** A sign written (`a:sign`). */
    SIGN,

    /** A chat message (`a:chat`). */
    CHAT,

    /** A command (`a:command`). */
    COMMAND,

    /** A player joined (`a:+session`). */
    JOIN,

    /** A player quit (`a:-session`). */
    QUIT,
}

/**
 * One logged change, as a lookup returns it.
 *
 * @since 2.0.0
 */
public class Entry(
    /** When it happened, to the second. */
    public val time: Instant,
    /** Who did it: a player's name, or a source such as `#tnt`. */
    public val actor: String,
    /** The world's name. */
    public val world: String,
    public val x: Int,
    public val y: Int,
    public val z: Int,
    public val action: Action,
    /**
     * What it was about: for a block change, the block's state before (`minecraft:oak_stairs[facing=east]`);
     * for a container change, the item's type; for a kill, the entity's type; for a sign, chat
     * message or command, the text; for a join or quit, empty.
     */
    public val target: String,
    /** For a block change, the block's state after; otherwise `null`. */
    public val after: String?,
    /** For a container change, how many items; otherwise 0. */
    public val amount: Int,
    /** Whether a rollback has undone it. */
    public val rolledBack: Boolean,
) {
    override fun toString(): String = "Entry($time $actor $action $target at $world $x $y $z)"
}

/**
 * Whether a block is in the region a rollback or lookup keeps to, beyond its [Query.Builder.area]:
 * a plot's shape. Called off the server's threads, once per block, while a plan is worked out.
 *
 * @since 2.0.0
 */
public fun interface BlockFilter {
    public fun includes(world: String, x: Int, y: Int, z: Int): Boolean
}

/**
 * Whether a change is logged at all: [XRewindApi.registerFilter]. Called on the thread the change
 * happens on, for every change, so keep it quick. [actor] is a player's name or a source's (`#tnt`).
 *
 * @since 2.0.0
 */
public fun interface LogFilter {
    public fun logs(world: WorldRef, x: Int, y: Int, z: Int, actor: String): Boolean
}

/**
 * What a lookup or a rollback is about: who, how far back, where and what. Built with [builder];
 * every part left out matches everything.
 *
 * @since 2.0.0
 */
public class Query private constructor(builder: Builder) {
    /** Players or sources, lowercase; empty for anyone. */
    public val users: List<String> = builder.users.toList()

    /** Players or sources left out. */
    public val excludedUsers: List<String> = builder.excludedUsers.toList()

    /** How far back, in seconds; 0 for no limit. */
    public val seconds: Long = builder.seconds

    /** Where: around a point, one world, everywhere, or a box. */
    public val area: Area = builder.area

    /** The world, for [Area.RADIUS], [Area.WORLD] and [Area.BOX]; `null` for [Area.GLOBAL]. */
    public val world: String? = builder.world

    /** The point a radius is around. */
    public val centerX: Int = builder.centerX
    public val centerY: Int = builder.centerY
    public val centerZ: Int = builder.centerZ

    /** The radius, in blocks, for [Area.RADIUS]. */
    public val radius: Int = builder.radius

    /** The box's corners, for [Area.BOX], smallest first. */
    public val minX: Int = builder.minX
    public val minY: Int = builder.minY
    public val minZ: Int = builder.minZ
    public val maxX: Int = builder.maxX
    public val maxY: Int = builder.maxY
    public val maxZ: Int = builder.maxZ

    /** What else a block must be in, a plot's shape; `null` for none. */
    public val filter: BlockFilter? = builder.filter

    /** The kinds of change; empty for blocks, containers and kills. */
    public val actions: Set<Action> = builder.actions.toSet()

    /** Block or item ids to include (`minecraft:oak_log`); empty for all. */
    public val blocks: Set<String> = builder.blocks.toSet()

    /** Block or item ids to leave out. */
    public val excludedBlocks: Set<String> = builder.excludedBlocks.toSet()

    /** The most entries a lookup returns. */
    public val limit: Int = builder.limit

    /** Where a query looks. */
    public enum class Area {
        /** [Query.radius] blocks around a point, in one world. */
        RADIUS,

        /** One whole world. */
        WORLD,

        /** Every world. */
        GLOBAL,

        /** Between two corners, in one world: a region. */
        BOX,
    }

    override fun toString(): String = "Query(users=$users, seconds=$seconds, area=$area, world=$world, actions=$actions, blocks=$blocks)"

    /** Builds a [Query]. Every part is optional; a rollback needs a [time]. */
    public class Builder internal constructor() {
        internal val users = LinkedHashSet<String>()
        internal val excludedUsers = LinkedHashSet<String>()
        internal var seconds = 0L
        internal var area = Area.GLOBAL
        internal var world: String? = null
        internal var centerX = 0
        internal var centerY = 0
        internal var centerZ = 0
        internal var radius = 0
        internal var minX = 0
        internal var minY = 0
        internal var minZ = 0
        internal var maxX = 0
        internal var maxY = 0
        internal var maxZ = 0
        internal var filter: BlockFilter? = null
        internal val actions = LinkedHashSet<Action>()
        internal val blocks = LinkedHashSet<String>()
        internal val excludedBlocks = LinkedHashSet<String>()
        internal var limit = 100

        /** Only these players or sources (`#tnt`). */
        public fun users(vararg names: String): Builder = apply { names.forEach { users += it.lowercase() } }

        /** Not these players or sources. */
        public fun excludeUsers(vararg names: String): Builder = apply { names.forEach { excludedUsers += it.lowercase() } }

        /** Only changes in the last [seconds]. */
        public fun time(seconds: Long): Builder = apply {
            require(seconds > 0) { "A time is at least a second" }
            this.seconds = seconds
        }

        /** Only within [blocks] of [x] [y] [z] in [world]. */
        public fun radius(world: WorldRef, x: Int, y: Int, z: Int, blocks: Int): Builder = apply {
            require(blocks >= 0) { "A radius isn't negative" }
            area = Area.RADIUS
            this.world = world.name
            centerX = x
            centerY = y
            centerZ = z
            radius = blocks
        }

        /** Only in [world]. */
        public fun world(world: WorldRef): Builder = apply {
            area = Area.WORLD
            this.world = world.name
        }

        /** In every world: the default. */
        public fun global(): Builder = apply {
            area = Area.GLOBAL
            world = null
        }

        /** Only between two corners in [world], both included: a plot's bounds. */
        public fun area(world: WorldRef, x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int): Builder = apply {
            area = Area.BOX
            this.world = world.name
            minX = minOf(x1, x2)
            minY = minOf(y1, y2)
            minZ = minOf(z1, z2)
            maxX = maxOf(x1, x2)
            maxY = maxOf(y1, y2)
            maxZ = maxOf(z1, z2)
        }

        /** Only blocks [filter] includes, on top of the rest: a plot's own shape. */
        public fun filter(filter: BlockFilter): Builder = apply { this.filter = filter }

        /** Only these kinds of change. */
        public fun actions(vararg kinds: Action): Builder = apply { actions += kinds }

        /** Only these blocks or items: `minecraft:oak_log`, or `oak_log`. */
        public fun blocks(vararg ids: String): Builder = apply { ids.forEach { blocks += key(it) } }

        /** Not these blocks or items. */
        public fun excludeBlocks(vararg ids: String): Builder = apply { ids.forEach { excludedBlocks += key(it) } }

        /** At most [entries] for a lookup (100 unless set; 1 to 10,000). */
        public fun limit(entries: Int): Builder = apply {
            require(entries in 1..10_000) { "A limit is 1 to 10,000" }
            limit = entries
        }

        public fun build(): Query = Query(this)

        private fun key(id: String): String = id.lowercase().let { if (':' in it) it else "minecraft:$it" }
    }

    public companion object {
        @JvmStatic
        public fun builder(): Builder = Builder()
    }
}

/** One block a [Plan] sets. @since 2.0.0 */
public class PlanBlock(
    public val world: String,
    public val x: Int,
    public val y: Int,
    public val z: Int,
    /** What the block becomes. */
    public val target: String,
) {
    override fun toString(): String = "$world $x $y $z -> $target"
}

/** Items a [Plan] puts into (positive [amount]) or takes out of a container. @since 2.0.0 */
public class PlanItem(
    public val world: String,
    public val x: Int,
    public val y: Int,
    public val z: Int,
    /** The item's type, `minecraft:diamond`. */
    public val item: String,
    public val amount: Int,
) {
    override fun toString(): String = "$world $x $y $z $amount $item"
}

/**
 * What a rollback, restore or undo would change, worked out from the log and not yet applied: a
 * preview. A block changed many times is set once. From [XRewindApi.planRollback],
 * [XRewindApi.planRestore] or a [RollbackResult.undo]; applied with [XRewindApi.apply]. Made by
 * xRewind only.
 *
 * @since 2.0.0
 */
public interface Plan {
    /** What kind of plan it is. */
    public enum class Kind {
        /** Puts things back as they were before the matching changes. */
        ROLLBACK,

        /** Makes the matching changes again, after a rollback. */
        RESTORE,

        /** Puts back what applying another plan changed. */
        UNDO,
    }

    public val kind: Kind

    /** The blocks it would set, in the order they'd be set. */
    public val blocks: List<PlanBlock>

    /** The items it would put into or take out of containers. */
    public val items: List<PlanItem>

    /** Logged states this server can't read (a block from an older Minecraft that's gone), left out. */
    public val unreadable: Int
}

/**
 * What applying a [Plan] did.
 *
 * @since 2.0.0
 */
public class RollbackResult(
    /** How many of the plan's changes were made. */
    public val applied: Int,
    /** How many couldn't be: states this server can't read, and worlds that aren't loaded. */
    public val skipped: Int,
    /** How long it took, start to finish, in milliseconds. */
    public val tookMillis: Long,
    /**
     * The plan that puts back what this one changed: apply it to undo. In a
     * [co.xenastudios.xrewind.api.event.RollbackFinishedEvent] for a command's rollback it's the
     * sender's `/xrewind undo`, and can't be applied.
     */
    public val undo: Plan,
) {
    override fun toString(): String = "RollbackResult(applied=$applied, skipped=$skipped, took=${tookMillis}ms)"
}
