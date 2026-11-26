package co.xenastudios.xmoney

import co.xenastudios.xmoney.api.Amount
import co.xenastudios.xmoney.api.Currency
import co.xenastudios.xmoney.config.CurrencySettings

/**
 * How the currency looks and how amounts are typed, from the config's `currency` section.
 * Immutable, so any thread may use it; a reload makes a new one. Amounts are [Amount.units].
 */
public class CurrencyFormat(public val settings: CurrencySettings) {
    private val suffixes = if (settings.shortSuffixes.size == 4) settings.shortSuffixes else listOf("k", "M", "B", "T")

    /** Places amounts are shown with. */
    public val decimals: Int get() = settings.decimals

    /** The API's view of it. */
    public val currency: Currency
        get() = made ?: Currency(ID, settings.singular, settings.plural, settings.symbol, settings.decimals).also { made = it }

    // Made when the API's caller first asks.
    private var made: Currency? = null

    /** The amount as the config shows it: `$1,234.50`, or `1,234.50 dollars`. */
    public fun format(units: Long): String = settings.format
        .replace("{amount}", grouped(units))
        .replace("{symbol}", settings.symbol)
        .replace("{name}", if (units == Amount.ONE) settings.singular else settings.plural)

    /** The bare number with the currency's decimals (rounded down) and no grouping: `1234.50`, for placeholders. */
    public fun plain(units: Long): String {
        val negative = units < 0
        val abs = if (negative) -units else units
        val whole = abs / Amount.ONE
        val fraction = (abs % Amount.ONE).toString().padStart(Amount.SCALE, '0').substring(0, decimals)
        return (if (negative) "-" else "") + whole + if (decimals == 0) "" else ".$fraction"
    }

    /** The number with grouping and the currency's separators: `1,234.50`. */
    public fun grouped(units: Long): String {
        val text = plain(units)
        val negative = text.startsWith("-")
        val body = if (negative) text.substring(1) else text
        val dot = body.indexOf('.')
        val whole = if (dot < 0) body else body.substring(0, dot)
        val fraction = if (dot < 0) "" else body.substring(dot + 1)
        val out = StringBuilder(text.length + 8)
        if (negative) out.append('-')
        out.append(group(whole))
        if (fraction.isNotEmpty()) out.append(decimalMark()).append(fraction)
        return out.toString()
    }

    /** A count, such as accounts or rows, grouped with the thousands separator: `1,234`. */
    public fun count(n: Long): String = (if (n < 0) "-" else "") + group(if (n < 0) (-n).toString() else n.toString())

    private fun group(digits: String): String {
        val out = StringBuilder(digits.length + 6)
        val lead = if (digits.length % 3 == 0) 3 else digits.length % 3
        out.append(digits, 0, lead)
        var i = lead
        while (i < digits.length) {
            out.append(settings.thousandsSeparator).append(digits, i, i + 3)
            i += 3
        }
        return out.toString()
    }

    /** Short, for scoreboards: `950`, `1.2k`, `3.4M`. Rounded down, so 1,999 is 1.9k, never 2k. */
    public fun shortForm(units: Long): String {
        val negative = units < 0
        val whole = (if (negative) -units else units) / Amount.ONE
        if (whole < 1000) return (if (negative) "-" else "") + whole
        var tier = -1
        var scaled = whole
        var divisor = 1L
        while (scaled >= 1000 && tier < suffixes.size - 1) {
            scaled /= 1000
            divisor *= 1000
            tier++
        }
        val tenths = whole / (divisor / 10)
        val number = if (tenths % 10 == 0L) (tenths / 10).toString() else "${tenths / 10}${decimalMark()}${tenths % 10}"
        return (if (negative) "-" else "") + number + suffixes[tier]
    }

    /**
     * The decimal mark amounts are typed with: the decimal separator, or a dot when that's blank
     * or the same as the thousands separator (which would make `1,000` ambiguous).
     */
    private fun decimalMark(): String {
        val mark = settings.decimalSeparator
        return if (mark.isEmpty() || mark == settings.thousandsSeparator) "." else mark
    }

    /** The amount as [parse] reads it back, whatever the separators: for commands built to be clicked, like /pay's confirm. */
    public fun typed(units: Long): String {
        val plain = plain(units)
        return if (decimalMark() == ".") plain else plain.replace(".", decimalMark())
    }

    /** Why [parse] refused an amount. */
    public enum class Problem { INVALID, TOO_PRECISE, TOO_LARGE }

    /** What [parse] made of what was typed: [units], or the [problem]. */
    public class Parsed(public val units: Long, public val problem: Problem?)

    /**
     * An amount as a player types it: `250`, `1,000`, `12.5`, `1.5k`, `2m`. The thousands
     * separator only counts between groups of three digits, so `1,5` is refused rather than read
     * as 15; the decimal separator marks the decimals, and so does a dot when the dot isn't the
     * thousands separator. A suffix multiplies by a thousand, a million, a billion or a trillion.
     * More decimals than the currency has are refused, never rounded.
     */
    public fun parse(typed: String): Parsed {
        var text = typed.trim()
        var power = 0
        if (text.isNotEmpty()) {
            power = when (text.last().lowercaseChar()) {
                'k' -> 3
                'm' -> 6
                'b' -> 9
                't' -> 12
                else -> 0
            }
            if (power > 0) text = text.dropLast(1)
        }
        var mark = decimalMark()
        val thousands = settings.thousandsSeparator
        var at = text.lastIndexOf(mark)
        if (at < 0 && mark != "." && thousands != ".") {
            at = text.lastIndexOf('.')
            mark = "."
        }
        var whole = if (at < 0) text else text.substring(0, at)
        val fraction = if (at < 0) null else text.substring(at + mark.length)
        if (thousands.isNotEmpty() && thousands in whole) {
            if (!Regex("\\d{1,3}(" + Regex.escape(thousands) + "\\d{3})+").matches(whole)) return INVALID
            whole = whole.replace(thousands, "")
        }
        if (!DIGITS.matches(whole) || (fraction != null && !DIGITS_ONE.matches(fraction)) || (whole.isEmpty() && fraction == null)) return INVALID
        // The value in units: whole and fraction digits, times 10^power.
        val digits = (whole.ifEmpty { "0" } + (fraction ?: "")).trimStart('0').ifEmpty { "0" }
        val scale = (fraction?.length ?: 0) - power
        // The places it really has: 1.50 has one, 0.000 none.
        val places = if (digits == "0") 0 else maxOf(0, scale - (digits.length - digits.trimEnd('0').length))
        if (places > decimals) return Parsed(0, Problem.TOO_PRECISE)
        val shift = Amount.SCALE - scale
        if (digits.length + shift > 19) return Parsed(0, Problem.TOO_LARGE)
        val units = if (shift >= 0) (digits + "0".repeat(shift)).toLong() else digits.dropLast(-shift).ifEmpty { "0" }.toLong()
        if (units > Amount.MAX_UNITS) return Parsed(0, Problem.TOO_LARGE)
        return Parsed(units, null)
    }

    public companion object {
        /** The currency's id, `default`: the only one in 2.0. */
        public const val ID: String = "default"

        private val INVALID = Parsed(0, Problem.INVALID)
        private val DIGITS = Regex("\\d*")
        private val DIGITS_ONE = Regex("\\d+")
    }
}
