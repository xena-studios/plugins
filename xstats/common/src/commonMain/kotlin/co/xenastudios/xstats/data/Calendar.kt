package co.xenastudios.xstats.data

import co.xenastudios.xstats.api.Period
import co.xenastudios.xstats.config.XStatsConfig

/** A time zone: how far ahead of UTC it is at an instant. On the JVM, `java.time`'s ([Zones]). */
public interface Zone {
    public val id: String

    /** Milliseconds ahead of UTC at [epochMillis]. */
    public fun offsetMillis(epochMillis: Long): Long
}

/** Time zones by id, from the platform: `java.time` on the JVM. */
public interface Zones {
    /** The server's own zone. */
    public val server: Zone

    /** The zone [id] names, or `null`. */
    public fun of(id: String): Zone?

    public companion object {
        /** UTC alone, for tests and where there are no zones. */
        public val UTC_ONLY: Zones = object : Zones {
            override val server: Zone = object : Zone {
                override val id: String get() = "UTC"
                override fun offsetMillis(epochMillis: Long): Long = 0
            }

            override fun of(id: String): Zone? = if (id == "UTC") server else null
        }
    }
}

/**
 * Days, weeks and months as numbers, in the config's time zone: a day is its epoch day, a week the
 * epoch day it starts on, a month `year * 12 + month - 1`, as 1.x stored them. Daily rows in the
 * database are keyed by day, monthly ones by month.
 */
public class Calendar(public val zone: Zone, public val weekStarts: XStatsConfig.WeekDay) {
    public fun today(millis: Long): Int = floorDiv(millis + zone.offsetMillis(millis), DAY_MS).toInt()

    public fun week(day: Int): Int = week(day, weekStarts)

    /**
     * The key a period's bucket has on a day: the day, its week or its month. Compared, not a
     * `when` over [Period], which (an enum of another module) would be a class of its own.
     */
    public fun key(period: Period, day: Int): Int =
        if (period === Period.DAILY) day else if (period === Period.WEEKLY) week(day) else if (period === Period.MONTHLY) month(day) else 0

    /** The earliest day the current week or month needs, for loading them from daily rows. */
    public fun earliestNeeded(day: Int): Int = minOf(week(day), firstDay(month(day)))

    /** Milliseconds since 1970 at the start of [day], here. */
    public fun startOf(day: Int): Long {
        val utc = day.toLong() * DAY_MS
        return utc - zone.offsetMillis(utc)
    }

    override fun equals(other: Any?): Boolean = other is Calendar && other.zone.id == zone.id && other.weekStarts == weekStarts

    override fun hashCode(): Int = zone.id.hashCode() * 31 + weekStarts.hashCode()

    public companion object {
        public const val DAY_MS: Long = 86_400_000L

        /** The day [day]'s week starts on: the last [weekStarts] on or before it. 1970-01-01 was a Thursday. */
        public fun week(day: Int, weekStarts: XStatsConfig.WeekDay): Int {
            val dayOfWeek = floorMod(day.toLong() + 3, 7).toInt()
            return day - floorMod((dayOfWeek - weekStarts.ordinal).toLong(), 7).toInt()
        }

        /** The month [day] is in, as `year * 12 + month - 1`. */
        public fun month(day: Int): Int {
            val (year, month) = civil(day.toLong())
            return year * 12 + month - 1
        }

        /** The first day of a month number. */
        public fun firstDay(month: Int): Int = days(floorDiv(month.toLong(), 12).toInt(), floorMod(month.toLong(), 12).toInt() + 1, 1)

        /** Year, month (1 to 12) and day of [epochDay], in the proleptic Gregorian calendar (Howard Hinnant's algorithm). */
        public fun civil(epochDay: Long): Triple<Int, Int, Int> {
            val z = epochDay + 719_468
            val era = floorDiv(z, 146_097)
            val doe = z - era * 146_097
            val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
            val y = yoe + era * 400
            val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
            val mp = (5 * doy + 2) / 153
            val d = doy - (153 * mp + 2) / 5 + 1
            val m = if (mp < 10) mp + 3 else mp - 9
            return Triple((if (m <= 2) y + 1 else y).toInt(), m.toInt(), d.toInt())
        }

        /** The epoch day of a date. */
        public fun days(year: Int, month: Int, day: Int): Int {
            val y = if (month <= 2) year - 1L else year.toLong()
            val era = floorDiv(y, 400)
            val yoe = y - era * 400
            val mp = (month + 9) % 12
            val doy = (153 * mp + 2) / 5 + day - 1
            val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
            return (era * 146_097 + doe - 719_468).toInt()
        }

        private fun floorDiv(a: Long, b: Long): Long {
            val q = a / b
            return if ((a % b != 0L) && ((a < 0) != (b < 0))) q - 1 else q
        }

        private fun floorMod(a: Long, b: Long): Long = a - floorDiv(a, b) * b
    }
}
