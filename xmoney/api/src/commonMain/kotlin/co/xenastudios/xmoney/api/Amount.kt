package co.xenastudios.xmoney.api

import kotlin.jvm.JvmField
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic

/**
 * An amount of money, exactly: a whole number of ten-thousandths ([units]), never a floating
 * point number, so adding and taking away never drift. Every balance and every change is one.
 *
 * Four decimal places, whatever the currency's decimals (those are for showing amounts), so a
 * price split in two costs what it would whole. Make one with [of], [parse] or [ofUnits]; from a
 * `double` or Java's `BigDecimal`, say which way to round ([Rounding]): round down what you pay
 * in and up what you take, and rounding never makes money.
 *
 * Negative only as a change (a [co.xenastudios.xmoney.api.event.BalanceChangedEvent]'s, money
 * leaving); balances and the amounts the API takes are zero or more.
 *
 * @since 2.0.0
 */
public class Amount private constructor(
    /** The amount in ten-thousandths: `12.5` is `125000`. */
    public val units: Long,
) : Comparable<Amount> {

    /** Whether it's below zero: money leaving, in a change. */
    public val isNegative: Boolean get() = units < 0

    /** Whether it's zero. */
    public val isZero: Boolean get() = units == 0L

    /** This plus [other]. Throws [ArithmeticException] if that's past what a `long` of units holds. */
    public operator fun plus(other: Amount): Amount = ofUnits(addExact(units, other.units))

    /** This minus [other]. */
    public operator fun minus(other: Amount): Amount = ofUnits(addExact(units, -other.units))

    /** The same amount, the other way: a deposit's change is a withdrawal's negated. */
    public operator fun unaryMinus(): Amount = ofUnits(-units)

    override fun compareTo(other: Amount): Int = units.compareTo(other.units)

    override fun equals(other: Any?): Boolean = other is Amount && other.units == units

    override fun hashCode(): Int = units.hashCode()

    /**
     * The amount as a plain number with every place it has and at least [decimals]: `12.50` with
     * 2, `0.995` whatever the decimals. No grouping, a dot for the decimals.
     */
    @JvmOverloads
    public fun toPlainString(decimals: Int = 0): String {
        require(decimals in 0..SCALE) { "decimals are 0 to $SCALE" }
        val negative = units < 0
        val abs = if (negative) -units else units
        val whole = abs / ONE
        var fraction = (abs % ONE).toString().padStart(SCALE, '0').trimEnd('0')
        if (fraction.length < decimals) fraction = fraction.padEnd(decimals, '0')
        return (if (negative) "-" else "") + whole + if (fraction.isEmpty()) "" else ".$fraction"
    }

    /** The amount as a `double`, for APIs that only speak doubles (Vault 1). Exact up to about a billion. */
    public fun toDouble(): Double = units / ONE.toDouble()

    /** [toPlainString]: `12.5`, `0.995`. */
    override fun toString(): String = toPlainString()

    public companion object {
        /** Decimal places an amount holds. */
        public const val SCALE: Int = 4

        /** Units in one whole coin. */
        public const val ONE: Long = 10_000

        /** The most any balance can be, in whole coins: 100 trillion. */
        public const val MAX_WHOLE: Long = 100_000_000_000_000L

        /** [MAX_WHOLE] in units. Two of them still add up inside a `long`. */
        public const val MAX_UNITS: Long = MAX_WHOLE * ONE

        /** Nothing. */
        @JvmField
        public val ZERO: Amount = Amount(0)

        /** The amount of [units] ten-thousandths. */
        @JvmStatic
        public fun ofUnits(units: Long): Amount = if (units == 0L) ZERO else Amount(units)

        /** [whole] coins. */
        @JvmStatic
        public fun of(whole: Long): Amount = ofUnits(multiplyExact(whole, ONE))

        /**
         * A `double` as an amount, rounded to four places as [rounding] says, or `null` if it isn't
         * a number, is infinite or is past [MAX_WHOLE] either way. Its first 15 significant digits
         * are taken as the number it stands for (all a double holds exactly), so `0.1 + 0.2` is
         * `0.3`, not `0.3001` rounded up; from ten billion up its own digits are kept, since 15
         * digits are coarser than a unit there.
         */
        @JvmStatic
        public fun ofDouble(value: Double, rounding: Rounding): Amount? {
            if (value.isNaN() || value.isInfinite() || value > MAX_WHOLE || value < -MAX_WHOLE) return null
            return parse(Decimals.ofDouble(value), rounding)
        }

        /**
         * An amount written as a plain number (`12.5`, `-3`, `0.00001`, `1e3`), rounded to four
         * places as [rounding] says, or `null` if it isn't one or is past [MAX_WHOLE] either way.
         */
        @JvmStatic
        @JvmOverloads
        public fun parse(text: String, rounding: Rounding = Rounding.EXACT): Amount? {
            val trimmed = text.trim()
            val units = plainUnits(trimmed) ?: Decimals.units(trimmed, SCALE, rounding) ?: return null
            if (units > MAX_UNITS || units < -MAX_UNITS) return null
            return ofUnits(units)
        }

        // The plain forms, `12`, `12.5`, `0.0001`, worked out here: exact whatever the rounding.
        // Anything else (a sign, an exponent, places to round) goes to Decimals, which loads only
        // then, so reading a config of plain amounts doesn't load it.
        private fun plainUnits(text: String): Long? {
            val dot = text.indexOf('.')
            val wholeEnd = if (dot < 0) text.length else dot
            val places = if (dot < 0) 0 else text.length - dot - 1
            if (wholeEnd == 0 || wholeEnd > 15 || (dot >= 0 && (places == 0 || places > SCALE))) return null
            var units = 0L
            for (i in 0 until wholeEnd) {
                val digit = text[i] - '0'
                if (digit !in 0..9) return null
                units = units * 10 + digit
            }
            // Past the most an amount is: Decimals says so (and the long can't overflow below it).
            if (units > MAX_WHOLE) return null
            units *= ONE
            var step = ONE
            for (i in wholeEnd + 1 until text.length) {
                val digit = text[i] - '0'
                if (digit !in 0..9) return null
                step /= 10
                units += digit * step
            }
            return units
        }

        private fun addExact(a: Long, b: Long): Long {
            val r = a + b
            if (((a xor r) and (b xor r)) < 0) throw ArithmeticException("an amount past what a long of units holds")
            return r
        }

        private fun multiplyExact(a: Long, b: Long): Long {
            if (a != 0L && (a > Long.MAX_VALUE / b || a < Long.MIN_VALUE / b)) throw ArithmeticException("an amount past what a long of units holds")
            return a * b
        }
    }
}

/**
 * Which way an amount with more than four decimal places is rounded to four.
 *
 * @since 2.0.0
 */
public enum class Rounding {
    /** Refused: [Amount.parse] answers `null` for `0.00001`. */
    EXACT,

    /** Towards zero: for what's paid in, so rounding never makes money. */
    DOWN,

    /** Away from zero: for what's taken, so a positive withdrawal never takes nothing. */
    UP,

    /** To the nearest, a half away from zero: for amounts people type. */
    HALF_UP,
}

/** Plain decimal text to units, by hand: common code has no `BigDecimal`. */
internal object Decimals {
    private val NUMBER = Regex("([+-]?)(\\d*)(?:\\.(\\d*))?(?:[eE]([+-]?\\d{1,4}))?")

    /** [text] as a whole number of 10^-[scale], rounded as [rounding] says, or `null`. */
    fun units(text: String, scale: Int, rounding: Rounding): Long? {
        val match = NUMBER.matchEntire(text) ?: return null
        val (sign, wholeText, fractionText, exponentText) = match.destructured
        if (wholeText.isEmpty() && fractionText.isEmpty()) return null
        val digits = (wholeText + fractionText).trimStart('0')
        // Where the decimal point is, counted from the right of the digits.
        val pointFromRight = fractionText.length - (exponentText.toIntOrNull() ?: 0)
        if (digits.isEmpty()) return 0
        // Move the point so the digits are units: pointFromRight == scale.
        val shift = scale - pointFromRight
        val kept: String
        val dropped: String
        if (shift >= 0) {
            if (digits.length + shift > 19) return null
            kept = digits + "0".repeat(shift)
            dropped = ""
        } else {
            val cut = -shift
            if (cut >= digits.length) {
                kept = ""
                dropped = digits
            } else {
                kept = digits.substring(0, digits.length - cut)
                dropped = digits.substring(digits.length - cut)
            }
        }
        if (kept.length > 19) return null
        var value = if (kept.isEmpty()) 0L else kept.toLongOrNull() ?: return null
        val rest = dropped.trimEnd('0')
        if (rest.isNotEmpty()) {
            value += when (rounding) {
                Rounding.EXACT -> return null
                Rounding.DOWN -> 0
                Rounding.UP -> 1
                Rounding.HALF_UP -> if (dropped[0] >= '5') 1 else 0
            }
        }
        return if (sign == "-") -value else value
    }

    /**
     * A double as decimal text: its own shortest digits (Kotlin's `toString`, which is Java's
     * on the JVM), cut to 15 significant digits where that's finer than a unit, which takes off
     * the noise of binary fractions (`0.30000000000000004` is `0.3`).
     */
    fun ofDouble(value: Double): String {
        val plain = plain(value.toString())
        val negative = plain.startsWith("-")
        val body = if (negative) plain.substring(1) else plain
        val dot = body.indexOf('.')
        val whole = if (dot < 0) body else body.substring(0, dot)
        val fraction = if (dot < 0) "" else body.substring(dot + 1)
        val wholeDigits = whole.trimStart('0').length
        // 15 significant digits: from ten billion up they're coarser than a unit, so kept as they are.
        if (15 - wholeDigits <= Amount.SCALE) return plain
        val significantStart = if (wholeDigits > 0) 0 else fraction.indexOfFirst { it != '0' }.let { if (it < 0) return plain else it }
        val allowedFraction = if (wholeDigits > 0) 15 - wholeDigits else significantStart + 15
        if (fraction.length <= allowedFraction) return plain
        // Half even at the 15th digit, as BigDecimal's MathContext did in 1.x.
        val digits = (whole + fraction.substring(0, allowedFraction))
        val next = fraction.substring(allowedFraction)
        var number = digits.toList().map { it - '0' }.toMutableList()
        val first = next[0] - '0'
        val beyond = next.substring(1).any { it != '0' }
        val roundUp = first > 5 || (first == 5 && (beyond || number.last() % 2 == 1))
        if (roundUp) {
            var i = number.size - 1
            while (i >= 0) {
                if (number[i] == 9) { number[i] = 0; i-- } else { number[i]++; break }
            }
            if (i < 0) number = (listOf(1) + number).toMutableList()
        }
        val text = number.joinToString("")
        val wholeLen = text.length - allowedFraction
        return (if (negative) "-" else "") + text.substring(0, wholeLen).ifEmpty { "0" } + "." + text.substring(wholeLen)
    }

    // 1.0E-5 and 1.234E10 in plain digits.
    private fun plain(text: String): String {
        val e = text.indexOfFirst { it == 'E' || it == 'e' }
        if (e < 0) return text
        val exponent = text.substring(e + 1).toInt()
        val mantissa = text.substring(0, e)
        val negative = mantissa.startsWith("-")
        val body = if (negative) mantissa.substring(1) else mantissa
        val dot = body.indexOf('.')
        val digits = body.replace(".", "")
        val point = (if (dot < 0) body.length else dot) + exponent
        val out = when {
            point <= 0 -> "0." + "0".repeat(-point) + digits
            point >= digits.length -> digits + "0".repeat(point - digits.length)
            else -> digits.substring(0, point) + "." + digits.substring(point)
        }
        return (if (negative) "-" else "") + out.trimStart('0').let { if (it.startsWith(".") || it.isEmpty()) "0$it" else it }
    }
}
