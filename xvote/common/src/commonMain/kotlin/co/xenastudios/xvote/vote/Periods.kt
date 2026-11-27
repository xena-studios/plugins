package co.xenastudios.xvote.vote

import co.xenastudios.xvote.config.XVoteConfig

/** A time zone's offset from UTC at a moment: on the JVM, `java.time`'s rules (`JvmZones`). */
public interface Zone {
    /** Its id, such as `Europe/London`, for telling two apart. */
    public val id: String

    /** The offset from UTC at [epochMillis], in milliseconds. */
    public fun offsetMillis(epochMillis: Long): Long

    public companion object {
        /** UTC, where there are no zones (and in tests). */
        public val UTC: Zone = object : Zone {
            override val id: String get() = "UTC"
            override fun offsetMillis(epochMillis: Long): Long = 0
        }
    }
}

/** Time zones by id: `server` is the server's own. */
public interface Zones {
    /** The server's own zone. */
    public val server: Zone

    /** The zone with [id], or `null` if there's none. */
    public fun of(id: String): Zone?

    public companion object {
        /** Only UTC. */
        public val UTC_ONLY: Zones = object : Zones {
            override val server: Zone get() = Zone.UTC
            override fun of(id: String): Zone? = if (id.equals("UTC", ignoreCase = true)) Zone.UTC else null
        }
    }
}

/**
 * Where days, weeks and months start, in the owner's time zone, as 1.x's `Periods` with
 * `java.time`, from a zone's offsets and the civil calendar. Each period has a key (a number that
 * changes when a new one starts), so a total kept with its key is known to be stale once the key
 * moves on, and no task has to reset totals at midnight.
 */
public class Periods(public val zone: Zone, public val weekStart: XVoteConfig.WeekDay) {

    /** Whether days and weeks start at the same moments under both. */
    public fun sameAs(other: Periods): Boolean = zone.id == other.zone.id && weekStart == other.weekStart

    /** The day number (days since 1970) of a moment, in this zone. */
    public fun day(millis: Long): Int = floorDiv(millis + zone.offsetMillis(millis), DAY).toInt()

    /** The day's start, in epoch milliseconds. */
    public fun dayStart(millis: Long): Long = start(day(millis).toLong())

    /** The next day's start: not always 24 hours on, where clocks change. */
    public fun dayEnd(millis: Long): Long = start(day(millis) + 1L)

    public fun weekStart(millis: Long): Long = start(weekDay(day(millis)).toLong())

    public fun monthStart(millis: Long): Long {
        val day = day(millis)
        return start((day - (civil(day.toLong())[2] - 1)).toLong())
    }

    /** Keys that change when a new day, week or month starts. */
    public fun dayKey(millis: Long): Int = day(millis)

    public fun weekKey(millis: Long): Int = weekDay(day(millis))

    public fun monthKey(millis: Long): Int {
        val date = civil(day(millis).toLong())
        return date[0] * 12 + date[1] - 1
    }

    // The day the week holding [day] started on.
    private fun weekDay(day: Int): Int {
        // 1970-01-01 was a Thursday: Monday is 0.
        val weekday = Math2.floorMod(day + 3, 7)
        return day - Math2.floorMod(weekday - weekStart.ordinal, 7)
    }

    // Midnight starting [day], in epoch milliseconds: the local midnight, less the offset there.
    private fun start(day: Long): Long {
        val local = day * DAY
        val guess = local - zone.offsetMillis(local)
        val offset = zone.offsetMillis(guess)
        val start = local - offset
        // A clock that changed at midnight: the day starts when it's first that day.
        return if (floorDiv(start + zone.offsetMillis(start), DAY) == day) start else local - zone.offsetMillis(local)
    }

    public companion object {
        private const val DAY = 86_400_000L

        /**
         * A streak after a vote on [day]: the same day keeps it, the next day adds one, a later
         * day starts again at 1. A vote counted late for an earlier day (a name matched after the
         * fact) leaves it as it is.
         */
        public fun nextStreak(lastDay: Int, streak: Int, day: Int): Int = when {
            streak <= 0 -> 1
            day <= lastDay -> streak
            day == lastDay + 1 -> streak + 1
            else -> 1
        }

        internal fun floorDiv(a: Long, b: Long): Long {
            val q = a / b
            return if ((a % b != 0L) && ((a < 0) != (b < 0))) q - 1 else q
        }

        /** The year, month (1 to 12) and day of the month of [days] since 1970-01-01: Howard Hinnant's algorithm. */
        internal fun civil(days: Long): IntArray {
            val z = days + 719_468
            val era = floorDiv(z, 146_097)
            val doe = z - era * 146_097
            val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
            val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
            val mp = (5 * doy + 2) / 153
            val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
            val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
            val y = (yoe + era * 400 + if (m <= 2) 1 else 0).toInt()
            return intArrayOf(y, m, d)
        }
    }
}

internal object Math2 {
    fun floorMod(a: Int, b: Int): Int = ((a % b) + b) % b
}
