package co.xenastudios.m0

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** A parse that failed, with where and why. Errors are values, never exceptions. */
data class ParseError(val input: String, val index: Int, val reason: String)

sealed interface Parsed<out T> {
    data class Ok<T>(val value: T) : Parsed<T>
    data class Err(val error: ParseError) : Parsed<Nothing>
}

/**
 * Durations as players and configs write them: `1d2h`, `90s`, `1w 3d`, `250ms`.
 * Units: `ms`, `s`, `m`, `h`, `d`, `w`, `mo` (30 days) and `y` (365 days); spaces between parts are allowed.
 */
object Durations {
    private val units: Map<String, Duration> = mapOf(
        "ms" to 1.milliseconds,
        "s" to 1.seconds,
        "m" to 1.minutes,
        "h" to 1.hours,
        "d" to 1.days,
        "w" to 7.days,
        "mo" to 30.days,
        "y" to 365.days,
    )

    fun parse(input: String): Parsed<Duration> {
        var i = 0
        var total = Duration.ZERO
        var parts = 0
        fun err(at: Int, why: String) = Parsed.Err(ParseError(input, at, why))
        while (i < input.length) {
            if (input[i] == ' ') { i++; continue }
            val numberStart = i
            while (i < input.length && input[i].isDigit()) i++
            if (i == numberStart) return err(i, "expected a number")
            val digits = input.substring(numberStart, i)
            if (digits.length > 9) return err(numberStart, "number too large")
            val amount = digits.toLong()
            val unitStart = i
            while (i < input.length && input[i].isLetter()) i++
            val unit = input.substring(unitStart, i).lowercase()
            val step = units[unit] ?: return err(unitStart, if (unit.isEmpty()) "missing unit" else "unknown unit '$unit'")
            total += step * amount.toDouble()
            parts++
        }
        return if (parts == 0) err(0, "empty duration") else Parsed.Ok(total)
    }

    /** The shortest form that [parse] reads back to the same value, such as `1d2h`. */
    fun format(duration: Duration): String {
        if (duration == Duration.ZERO) return "0s"
        var rest = duration.inWholeMilliseconds
        val out = StringBuilder()
        for ((name, step) in listOf("w" to 7.days, "d" to 1.days, "h" to 1.hours, "m" to 1.minutes, "s" to 1.seconds, "ms" to 1.milliseconds)) {
            val ms = step.inWholeMilliseconds
            if (rest >= ms) {
                out.append(rest / ms).append(name)
                rest %= ms
            }
        }
        return out.toString()
    }
}
