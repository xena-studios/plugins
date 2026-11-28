package co.xenastudios.xstats.data

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.xstats.api.Period
import kotlin.concurrent.Volatile

/**
 * A player's numbers for one day, week or month. [key] says which one; a bucket from an earlier day
 * reads as empty, so nothing has to be cleared when a new day starts.
 */
public class Bucket(public val key: Int, public val values: LongArray) {
    public fun get(key: Int, index: Int): Long = if (key == this.key && index < values.size) values[index] else 0

    public companion object {
        public val NONE: LongArray = LongArray(0)
        public val EMPTY: Bucket = Bucket(-1, NONE)
    }
}

/**
 * Everything xStats keeps in memory for one player. Values are indexed by
 * [co.xenastudios.xstats.stat.StatDef.index].
 *
 * Only xStats' database thread changes it. Other threads (placeholders, commands, the web server)
 * read it without locking: a read can be one save behind, never wrong in kind, since a long is
 * written in one go and arrays only grow by being replaced.
 */
public class PlayerRecord(public val id: PlayerRef, name: String?) {
    /** The row id in the players table, or 0 until the first save stores them. */
    @Volatile public var dbId: Int = 0
    @Volatile public var name: String? = name
    @Volatile public var hidden: Boolean = false
    @Volatile public var excluded: Boolean = false
    @Volatile public var firstJoin: Long = 0
    @Volatile public var lastSeen: Long = 0

    /** All-time values: this server's, or the network's in network mode. */
    @Volatile public var values: LongArray = Bucket.NONE
    @Volatile public var day: Bucket = Bucket.EMPTY
    @Volatile public var week: Bucket = Bucket.EMPTY
    @Volatile public var month: Bucket = Bucket.EMPTY

    /** Whether they were online at the last reading. */
    @Volatile public var online: Boolean = false

    // xStats' database thread only.
    /** This server's own values (its row in the totals table), and the last reading of each. */
    internal var local: LongArray = Bucket.NONE
    internal var raw: LongArray = Bucket.NONE
    internal var localLoaded: Boolean = false

    /** What changed since the last save: rows to write. */
    internal var rowChanged: Boolean = false
    internal var changed: BooleanArray = BooleanArray(0)
    internal var pendingDaily: LongArray = Bucket.NONE

    // AFK-aware playtime.
    internal var yaw: Float = 0f
    internal var pitch: Float = 0f
    internal var lastSample: Long = 0

    /** When their values here were last read or set: a network's latest value is the one read last. */
    internal var lastRead: Long = 0

    /** `/stats private` couldn't be saved at once: the next save writes it. */
    internal var hidePending: Boolean = false
    internal var playMillis: Long = 0

    /** All-time value, or 0. Any thread. */
    public fun value(index: Int): Long {
        val all = values
        return if (index < all.size) all[index] else 0
    }

    /** A period's value, or 0 if they have none this period. Any thread. */
    public fun value(index: Int, period: Period, calendar: Calendar, today: Int): Long = when (period) {
        Period.ALL -> value(index)
        Period.DAILY -> day.get(calendar.key(period, today), index)
        Period.WEEKLY -> week.get(calendar.key(period, today), index)
        Period.MONTHLY -> month.get(calendar.key(period, today), index)
    }

    internal fun anyChanged(): Boolean {
        for (c in changed) if (c) return true
        return false
    }

    internal fun ensureWidth(width: Int) {
        if (values.size < width) values = values.copyOf(width)
        if (localLoaded && local.size < width) {
            local = local.copyOf(width)
            val grown = raw.copyOf(width)
            for (i in raw.size until width) grown[i] = -1
            raw = grown
        }
        if (changed.size < width) changed = changed.copyOf(width)
        if (pendingDaily.size < width) pendingDaily = pendingDaily.copyOf(width)
    }

    /** Starts this server's values: none read yet. */
    internal fun startLocal(width: Int) {
        local = LongArray(width)
        raw = LongArray(width) { -1 }
        localLoaded = true
    }

    /** Adds to a period's bucket, starting a new one when the day, week or month changed. */
    internal fun addPeriod(period: Period, key: Int, index: Int, delta: Long, width: Int) {
        val bucket = bucket(period)
        var values = if (bucket.key == key) bucket.values else LongArray(width)
        if (values.size <= index) values = values.copyOf(maxOf(width, index + 1))
        values[index] += delta
        if (bucket.key != key || bucket.values !== values) setBucket(period, Bucket(key, values))
    }

    internal fun bucket(period: Period): Bucket = when (period) {
        Period.DAILY -> day
        Period.WEEKLY -> week
        Period.MONTHLY -> month
        Period.ALL -> throw IllegalArgumentException("all time has no bucket")
    }

    internal fun setBucket(period: Period, bucket: Bucket) {
        when (period) {
            Period.DAILY -> day = bucket
            Period.WEEKLY -> week = bucket
            Period.MONTHLY -> month = bucket
            Period.ALL -> throw IllegalArgumentException("all time has no bucket")
        }
    }

    override fun toString(): String = "PlayerRecord($name, $id)"

    public companion object {
        /**
         * A player's name: Java names, and Bedrock players' through Floodgate, which start with its
         * prefix (a dot unless the owner changed it), such as `.Steve`.
         */
        public val NAME: Regex = Regex("[\\w.*+~-]{1,16}")
    }
}

/** Something that happened to a player, applied in order at the next save. */
public sealed class Event {
    public abstract val player: PlayerRef

    /** A player joined. [firstPlayed] is when the server says they first played, in epoch milliseconds. */
    public class Joined(
        override val player: PlayerRef,
        public val name: String,
        public val at: Long,
        public val firstPlayed: Long,
        public val yaw: Float,
        public val pitch: Float,
    ) : Event()

    /**
     * A reading of a player's stats, made on their own thread. [raw] holds readings by stat index,
     * in the unit xStats keeps, or [NOT_READ]; [quit] whether it's the reading made as they left.
     */
    public class Sample(
        override val player: PlayerRef,
        public val name: String,
        public val at: Long,
        public val raw: LongArray,
        public val yaw: Float,
        public val pitch: Float,
        public val excluded: Boolean,
        public val quit: Boolean,
    ) : Event() {
        public companion object {
            public const val NOT_READ: Long = Long.MIN_VALUE
        }
    }

    /** Another plugin added to a stat. */
    public class Increment(override val player: PlayerRef, public val stat: String, public val amount: Long) : Event()

    /**
     * Another plugin set a stat: once, or several times in a row between two saves. [first] is the
     * first value set, [value] the last, and [rise] how much it went up between them, which counts
     * towards today, this week and this month as if each had been applied.
     */
    public class Set(
        override val player: PlayerRef,
        public val stat: String,
        public val first: Long,
        public val value: Long,
        public val rise: Long,
    ) : Event()
}
