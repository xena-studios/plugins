package co.xenastudios.xrewind.log

import co.xenastudios.core.common.world.BlockData
import co.xenastudios.core.common.world.ItemData
import co.xenastudios.xrewind.api.Actor
import kotlin.time.Instant

/** The action numbers stored in each table: 1.x's, never renumbered. */
public object Actions {
    // block.action
    public const val BREAK: Int = 0
    public const val PLACE: Int = 1
    public const val CLICK: Int = 2

    // container.action
    public const val REMOVE: Int = 0
    public const val ADD: Int = 1

    // entity.action
    public const val KILL: Int = 0

    // text.kind
    public const val SIGN: Int = 0
    public const val CHAT: Int = 1
    public const val COMMAND: Int = 2

    // session.action
    public const val QUIT: Int = 0
    public const val JOIN: Int = 1
}

/**
 * Times as xRewind stores them (1.x's): whole seconds since the start of 2026, UTC, which a
 * signed 32-bit number holds from 1958 to 2094, so a time is four bytes on every row.
 */
public object StoredTime {
    /** 2026-01-01T00:00:00Z in Unix seconds. */
    public const val EPOCH: Long = 1_767_225_600L

    public fun of(instant: Instant): Int = fromUnixSeconds(instant.epochSeconds)

    public fun fromUnixSeconds(seconds: Long): Int = (seconds - EPOCH).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()

    public fun toInstant(stored: Int): Instant = Instant.fromEpochSeconds(stored + EPOCH)

    /** [now] minus [seconds], held at the earliest time a row can have, so a huge age reaches everything rather than wrapping round. */
    public fun ago(now: Int, seconds: Long): Int = (now - seconds).coerceAtLeast(Int.MIN_VALUE.toLong()).toInt()
}

/** A block state given as text, for what a plugin or an import logs: the platform's own [BlockData] otherwise. */
public class TextState(override val data: String) : BlockData() {
    override val type: String = data.substringBefore('[')
    override val isAir: Boolean = type == "minecraft:air" || type == "minecraft:cave_air" || type == "minecraft:void_air"
    override val native: Any get() = data
}

/**
 * One thing that happened, as a listener hands it over: references to what the event already
 * had, nothing worked out. Turning names, worlds and states into ids, and items into bytes, is
 * the writer's job, on its own thread, so a listener costs almost nothing.
 */
public sealed class Record {
    /** The world it happened in, or `null` for one that isn't a change. */
    public abstract val world: String?

    /**
     * A block changed from [before] to [after]. [contents] is what it held before (a chest's
     * items, a sign's text) as the platform writes it, for a rollback to put back; `null` for
     * most blocks.
     */
    public class Block(
        public val time: Int, public val actor: Actor, override val world: String, public val x: Int, public val y: Int, public val z: Int,
        public val action: Int, public val before: BlockData, public val after: BlockData, public val contents: ByteArray? = null,
        public val rolledBack: Boolean = false,
    ) : Record()

    /**
     * [amount] of an item went into ([Actions.ADD]) or out of a container. [item] is the
     * platform's stack of one ([ItemData]) or its bytes already; [material] its type.
     */
    public class Container(
        public val time: Int, public val actor: Actor, override val world: String, public val x: Int, public val y: Int, public val z: Int,
        public val action: Int, public val material: String, public val item: ItemData?, public val itemBytes: ByteArray?,
        public val amount: Int, public val rolledBack: Boolean = false,
    ) : Record()

    /** An entity of [type] killed. */
    public class Kill(
        public val time: Int, public val actor: Actor, override val world: String, public val x: Int, public val y: Int, public val z: Int,
        public val type: String,
    ) : Record()

    /** Text: a sign written, a chat message, a command. */
    public class Text(
        public val time: Int, public val actor: Actor, override val world: String, public val x: Int, public val y: Int, public val z: Int,
        public val kind: Int, public val message: String,
    ) : Record()

    /** A player joined or quit at this spot. */
    public class Session(
        public val time: Int, public val actor: Actor, override val world: String, public val x: Int, public val y: Int, public val z: Int,
        public val action: Int,
    ) : Record()

    /**
     * Rows a rollback undid ([rolledBack] true) or a restore put back, in `{p}block` or
     * `{p}container`. Through the queue, so the writer stays the only thing that writes.
     */
    public class Mark(public val table: String, public val ids: LongArray, public val rolledBack: Boolean) : Record() {
        override val world: String? get() = null
    }
}
