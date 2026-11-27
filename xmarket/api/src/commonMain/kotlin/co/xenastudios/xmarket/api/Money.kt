package co.xenastudios.xmarket.api

import kotlin.jvm.JvmStatic

/**
 * An amount of a shop's currency, exactly: a whole number of [units], each 1/10,000 of one
 * (xMoney's own scale), so prices never pick up a floating point's error. Money, an item
 * currency (diamonds) and experience levels are all counted this way; the last two are whole
 * numbers when they're paid.
 *
 * From Java: `Money.of(5)`, `Money.parse("2.50")`, `money.getUnits()`.
 *
 * @since 1.0.0
 */
public class Money private constructor(
    /** How many 1/10,000ths. */
    public val units: Long,
) : Comparable<Money> {
    public val isZero: Boolean get() = units == 0L
    public val isNegative: Boolean get() = units < 0

    public operator fun plus(other: Money): Money = ofUnits(add(units, other.units))
    public operator fun minus(other: Money): Money = ofUnits(add(units, -other.units))

    /** This, [count] times. */
    public fun times(count: Long): Money = ofUnits(multiply(units, count))

    /** This scaled by [percent] percent (100 is the same), rounded down to a unit. */
    public fun percent(percent: Long): Money = ofUnits(scale(units, percent, 100, up = false))

    /** This scaled by [percent] percent, rounded up to a unit. */
    public fun percentUp(percent: Long): Money = ofUnits(scale(units, percent, 100, up = true))

    /** This split [parts] ways, rounded down ([up] false) or up to a unit. */
    public fun divide(parts: Long, up: Boolean): Money = ofUnits(scale(units, 1, parts, up))

    /** The whole number of ones, rounded down. */
    public val whole: Long get() = if (units >= 0) units / ONE else -((-units + ONE - 1) / ONE)

    /** The whole number of ones, rounded up. */
    public val wholeUp: Long get() = if (units >= 0) (units + ONE - 1) / ONE else -(-units / ONE)

    override fun compareTo(other: Money): Int = units.compareTo(other.units)
    override fun equals(other: Any?): Boolean = other is Money && other.units == units
    override fun hashCode(): Int = units.hashCode()

    /** As a plain number with as many decimals as it needs, up to four: `2.5`, `120`, `0.0125`. */
    override fun toString(): String {
        val negative = units < 0
        val abs = if (negative) -units else units
        val whole = abs / ONE
        val fraction = (abs % ONE).toString().padStart(SCALE, '0').trimEnd('0')
        return (if (negative) "-" else "") + whole + if (fraction.isEmpty()) "" else ".$fraction"
    }

    /** With exactly [decimals] decimals (0 to 4), rounded down: `2.50`. */
    public fun toPlainString(decimals: Int): String {
        val d = decimals.coerceIn(0, SCALE)
        val negative = units < 0
        val abs = if (negative) -units else units
        val whole = abs / ONE
        val fraction = (abs % ONE).toString().padStart(SCALE, '0').substring(0, d)
        return (if (negative) "-" else "") + whole + if (d == 0) "" else ".$fraction"
    }

    public companion object {
        public const val SCALE: Int = 4
        public const val ONE: Long = 10_000

        /** The most a price or total may be: 10^14 ones, as xMoney's. */
        public const val MAX_UNITS: Long = 100_000_000_000_000L * ONE

        public val ZERO: Money = Money(0)

        @JvmStatic
        public fun ofUnits(units: Long): Money = if (units == 0L) ZERO else Money(units.coerceIn(-MAX_UNITS, MAX_UNITS))

        @JvmStatic
        public fun of(whole: Long): Money = ofUnits(multiply(whole, ONE))

        /**
         * A number as written, `12`, `2.5` or `0.0001`, or `null` if it isn't one or has more
         * than four decimals. A leading `-` is allowed.
         */
        @JvmStatic
        public fun parse(text: String): Money? {
            val t = text.trim()
            if (t.isEmpty() || t.length > 24) return null
            val negative = t.startsWith('-')
            val body = if (negative || t.startsWith('+')) t.substring(1) else t
            val dot = body.indexOf('.')
            val wholePart = if (dot < 0) body else body.substring(0, dot)
            val fractionPart = if (dot < 0) "" else body.substring(dot + 1)
            if (wholePart.isEmpty() && fractionPart.isEmpty()) return null
            if (!wholePart.all { it in '0'..'9' } || !fractionPart.all { it in '0'..'9' }) return null
            if (fractionPart.trimEnd('0').length > SCALE) return null
            val whole = if (wholePart.isEmpty()) 0L else wholePart.toLongOrNull() ?: return null
            if (whole > MAX_UNITS / ONE) return null
            val fraction = fractionPart.take(SCALE).padEnd(SCALE, '0').toLong()
            val units = whole * ONE + fraction
            return ofUnits(if (negative) -units else units)
        }

        private fun add(a: Long, b: Long): Long {
            val r = a + b
            if (((a xor r) and (b xor r)) < 0) return if (a > 0) MAX_UNITS else -MAX_UNITS
            return r.coerceIn(-MAX_UNITS, MAX_UNITS)
        }

        private fun multiply(a: Long, b: Long): Long {
            if (a == 0L || b == 0L) return 0
            val r = a * b
            if (r / b != a || (a == Long.MIN_VALUE && b == -1L)) return if ((a > 0) == (b > 0)) MAX_UNITS else -MAX_UNITS
            return r.coerceIn(-MAX_UNITS, MAX_UNITS)
        }

        // a * n / d, exactly, rounded down or up (away from zero for up), without overflowing
        // where the result fits.
        private fun scale(a: Long, n: Long, d: Long, up: Boolean): Long {
            require(d > 0) { "can't divide by $d" }
            val negative = (a < 0) != (n < 0)
            val abs = if (a < 0) -a else a
            val num = if (n < 0) -n else n
            val q = abs / d
            val r = abs % d
            // (q*d + r) * num / d = q*num + r*num/d
            val whole = multiply(q, num)
            val partNum = r * num
            var part = partNum / d
            if (up && partNum % d != 0L) part++
            val total = add(whole, part)
            return if (negative) -total else total
        }
    }
}
