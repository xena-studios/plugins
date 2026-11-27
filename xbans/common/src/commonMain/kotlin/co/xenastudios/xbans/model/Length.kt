package co.xenastudios.xbans.model

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.config.ConfigType
import kotlin.math.roundToLong

/**
 * How long a punishment lasts, as staff type it the LiteBans way: `30m`, `1d2h`, `1.5h`, `2mo`,
 * `1y`, `5 days`, or `perm`. Units are `s m h d w mo y`; a month is 30 days and a year 365.
 * 1.x's rules, unchanged.
 */
public class Length(
    /** How long, in milliseconds; 0 means permanent. */
    public val millis: Long,
) : Comparable<Length> {
    public val permanent: Boolean get() = millis == 0L

    /** Whether this is longer than [other], permanent being longest. */
    override fun compareTo(other: Length): Int {
        if (permanent || other.permanent) return permanent.compareTo(other.permanent)
        return millis.compareTo(other.millis)
    }

    override fun equals(other: Any?): Boolean = other is Length && other.millis == millis

    override fun hashCode(): Int = millis.hashCode()

    override fun toString(): String = typed()

    /** As typed: `permanent`, or the short form such as `30d`, `2w` or `1d12h`. */
    public fun typed(): String {
        if (permanent) return "permanent"
        // One unit when one fits exactly (14d is 2w, 365d is 1y; months are left as days, 30d).
        for (u in intArrayOf(0, 2, 3)) {
            if (millis % SIZES[u] == 0L) return "${millis / SIZES[u]}${UNITS[u]}"
        }
        val out = StringBuilder()
        var left = millis
        for (u in 3 until SIZES.size) {
            if (left >= SIZES[u]) {
                out.append(left / SIZES[u]).append(UNITS[u])
                left %= SIZES[u]
            }
        }
        return if (out.isEmpty()) "1s" else out.toString()
    }

    /** As messages show it: the two largest units, `7d`, `1mo`, `1d 12h`. */
    public fun shown(): String = format(millis)

    public companion object {
        public val PERMANENT: Length = Length(0)

        /** The longest a punishment can last before it counts as permanent. */
        public const val MAX_MILLIS: Long = 100L * 365 * 86_400_000L

        private val SIZES = longArrayOf(365 * 86_400_000L, 30 * 86_400_000L, 604_800_000L, 86_400_000L, 3_600_000L, 60_000L, 1_000L)
        private val UNITS = arrayOf("y", "mo", "w", "d", "h", "m", "s")

        /** A length of time in milliseconds as messages show it: the two largest units, such as `2mo 3d`. */
        public fun format(millis: Long): String {
            var left = maxOf(0, millis)
            val out = StringBuilder()
            var shown = 0
            var u = 0
            while (u < SIZES.size && shown < 2) {
                val amount = left / SIZES[u]
                if (amount > 0) {
                    if (out.isNotEmpty()) out.append(' ')
                    out.append(amount).append(UNITS[u])
                    left -= amount * SIZES[u]
                    shown++
                }
                u++
            }
            return if (out.isEmpty()) "0s" else out.toString()
        }

        /** Whether [word] is one of the ways to say "permanent". */
        public fun isPermanentWord(word: String): Boolean = when (word.lowercase()) {
            "perm", "permanent", "permanently", "forever", "never", "-1", "duration:permanent", "duration:perm" -> true
            else -> false
        }

        /** What [read] gives for a length past 100 years: not one, and not to be taken as a reason either. */
        public val TOO_LONG: Length = Length(-1)

        /** Reads a length: `null` if the text isn't one (a reason can start there), [TOO_LONG] past 100 years. */
        public fun read(text: String): Length? {
            var input = text.trim().lowercase()
            if (input.startsWith("duration:")) input = input.substring("duration:".length)
            if (isPermanentWord(input)) return PERMANENT
            if (input.isEmpty() || !input[0].isDigit() && input[0] != '.') return null
            var total = 0.0
            var i = 0
            while (i < input.length) {
                val start = i
                while (i < input.length && (input[i].isDigit() || input[i] == '.')) i++
                if (i == start || i == input.length) return null
                val amount = input.substring(start, i).toDoubleOrNull() ?: return null
                val unitStart = i
                while (i < input.length && input[i].isLetter()) i++
                val unit = unit(input.substring(unitStart, i))
                if (unit == 0L) return null
                total += amount * unit
            }
            if (total < 1000) return null
            if (total > MAX_MILLIS) return TOO_LONG
            return Length(total.roundToLong())
        }

        /** [read]'s length, or `null`; too long is `null` too. */
        public fun parse(text: String): Length? = read(text)?.takeIf { it !== TOO_LONG }

        /** A unit's length in milliseconds, long and short names alike, or 0 if it isn't one. */
        internal fun unit(unit: String): Long = when (unit) {
            "s", "sec", "secs", "second", "seconds" -> 1_000L
            "m", "min", "mins", "minute", "minutes" -> 60_000L
            "h", "hr", "hrs", "hour", "hours" -> 3_600_000L
            "d", "day", "days" -> 86_400_000L
            "w", "wk", "wks", "week", "weeks" -> 604_800_000L
            "mo", "mon", "month", "months" -> 30 * 86_400_000L
            "y", "yr", "yrs", "year", "years" -> 365 * 86_400_000L
            else -> 0L
        }

        /** Whether [word] is a unit of time on its own, for `5 days` typed with a space. */
        public fun isUnit(word: String): Boolean = unit(word.lowercase()) > 0

        /** A length in the config, written as typed (`7d`, `permanent`). */
        public val TYPE: ConfigType<Length> = ConfigType.scalar("length", co.xenastudios.xbans.Startup.writeLength, co.xenastudios.xbans.Startup.readLength)

        internal fun typeRead(raw: String): Outcome<Length, String> = when (val read = read(raw)) {
            TOO_LONG -> Outcome.Err("is more than 100 years; write permanent instead")
            null -> Outcome.Err("isn't a length of time, such as 7d, 1d12h, 1mo or permanent")
            else -> Outcome.Ok(read)
        }
    }
}
