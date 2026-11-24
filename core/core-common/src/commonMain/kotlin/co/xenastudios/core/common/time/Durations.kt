package co.xenastudios.core.common.time

import co.xenastudios.core.common.Outcome
import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Lengths of time as players and configs write them: `30m`, `2w3d`, `1d 2h 30m`, `1.5h`,
 * `250ms`. A number, then a unit; parts add up in any order, with or without spaces between
 * them. Units, in any case: `ms`, `s`, `m`, `h`, `d`, `w`, `mo` (30 days) and `y` (365
 * days). Anything over 100 years is refused.
 *
 * Reads everything today's plugins' `Durations` reads, and what [write] writes.
 */
public object Durations {

    /** The longest length of time [parse] accepts: 100 years, as today's plugins allow. */
    public val MAX: Duration = (100L * 365 * DAY).milliseconds

    private const val SECOND = 1_000L
    private const val MINUTE = 60 * SECOND
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR
    private const val WEEK = 7 * DAY

    // Longest first, so "mo" wins over "m" and "ms" over "m".
    private val units: List<Pair<String, Long>> = listOf(
        "ms" to 1L,
        "mo" to 30 * DAY,
        "s" to SECOND,
        "m" to MINUTE,
        "h" to HOUR,
        "d" to DAY,
        "w" to WEEK,
        "y" to 365 * DAY,
    )

    // What format and write show, largest first. Months and years aren't: they're
    // approximate, and "5w" reads back exactly.
    private val shown: List<Pair<String, Long>> = listOf(
        "w" to WEEK, "d" to DAY, "h" to HOUR, "m" to MINUTE, "s" to SECOND,
    )

    /** Reads a length of time, or says what's wrong with it and where. Never throws. */
    public fun parse(input: String): Outcome<Duration, DurationError> {
        val text = input
        var i = 0
        var millis = 0.0
        var parts = 0
        fun err(kind: DurationError.Kind, at: Int, found: String = "") =
            Outcome.Err(DurationError(input, kind, at, found))
        while (i < text.length) {
            if (text[i].isWhitespace()) {
                i++
                continue
            }
            val numberStart = i
            while (i < text.length && (text[i] in '0'..'9' || text[i] == '.')) i++
            if (i == numberStart) return err(DurationError.Kind.EXPECTED_NUMBER, i, text.substring(i, i + 1))
            val number = text.substring(numberStart, i)
            val amount = number.toDoubleOrNull()
            if (amount == null) return err(DurationError.Kind.BAD_NUMBER, numberStart, number)
            val unitStart = i
            while (i < text.length && text[i].isLetter()) i++
            val unit = text.substring(unitStart, i).lowercase()
            if (unit.isEmpty()) return err(DurationError.Kind.MISSING_UNIT, unitStart)
            val size = units.firstOrNull { it.first == unit }?.second
                ?: return err(DurationError.Kind.UNKNOWN_UNIT, unitStart, unit)
            millis += amount * size
            if (millis > MAX.inWholeMilliseconds) return err(DurationError.Kind.TOO_LONG, numberStart, number + unit)
            parts++
        }
        if (parts == 0) return err(DurationError.Kind.EMPTY, 0)
        return Outcome.Ok(millis.roundToLong().milliseconds)
    }

    /**
     * A short form for messages: `2w 3d`, `4h 30m`, `12s`. The [units] largest units that
     * aren't zero, stopping at the first zero after them, so a week and three hours is
     * `1w`, not `1w 3h`. Under a second, or negative, is `0s`. At least one unit is shown.
     */
    public fun format(duration: Duration, units: Int = 2): String {
        val most = units.coerceAtLeast(1)
        var rest = if (duration.isNegative()) 0L else duration.inWholeMilliseconds
        val out = StringBuilder()
        var count = 0
        for ((name, size) in shown) {
            if (count == most) break
            val amount = rest / size
            if (amount > 0) {
                if (out.isNotEmpty()) out.append(' ')
                out.append(amount).append(name)
                rest -= amount * size
                count++
            } else if (count > 0) {
                break
            }
        }
        return if (out.isEmpty()) "0s" else out.toString()
    }

    /**
     * The exact form a config keeps: `1d2h30m`, `1w`, `1s500ms`. [parse] reads it back to the
     * same length, to the millisecond. Negative is `0s`.
     */
    public fun write(duration: Duration): String {
        var rest = if (duration.isNegative()) 0L else duration.inWholeMilliseconds
        if (rest == 0L) return "0s"
        val out = StringBuilder()
        for ((name, size) in shown) {
            if (rest >= size) {
                out.append(rest / size).append(name)
                rest %= size
            }
        }
        if (rest > 0) out.append(rest).append("ms")
        return out.toString()
    }
}

/** Why [Durations.parse] refused [input]: the [kind] of mistake, at [index], on the text [found]. */
public data class DurationError(
    public val input: String,
    public val kind: Kind,
    public val index: Int,
    public val found: String,
) {
    /** The kinds of mistake, for a message in the reader's language. */
    public enum class Kind {
        /** Nothing but spaces, or nothing at all. */
        EMPTY,

        /** Something other than a number where a number should start, such as `h2`. */
        EXPECTED_NUMBER,

        /** Digits and points that aren't a number, such as `1.2.3h`. */
        BAD_NUMBER,

        /** A number with no unit after it, such as `30`. */
        MISSING_UNIT,

        /** A unit that isn't one, such as `5x`. */
        UNKNOWN_UNIT,

        /** Over [Durations.MAX]. */
        TOO_LONG,
    }
}
