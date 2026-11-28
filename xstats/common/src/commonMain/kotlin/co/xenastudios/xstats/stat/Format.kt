package co.xenastudios.xstats.stat

import co.xenastudios.xstats.api.StatUnit
import kotlin.math.abs

/**
 * Numbers as players read them: `12,345`, `3h 12m`, `1.2 km`, `4.5 hearts`. The same text goes to
 * chat, placeholders and the website, so they always agree: 1.x's, written the same way whatever
 * the reader's language.
 */
public object Format {
    public fun value(stat: StatDef, value: Long): String = value(stat.unit, stat.decimals, value)

    public fun value(unit: StatUnit, decimals: Int, value: Long): String = when (unit) {
        StatUnit.COUNT -> if (decimals == 0) whole(value) else places(value, decimals)
        StatUnit.TIME -> time(value)
        StatUnit.DISTANCE -> distance(value)
        StatUnit.DAMAGE -> oneDecimal(value, 20) + if (value == 20L) " heart" else " hearts"
    }

    /** `1,234,567`. */
    public fun whole(value: Long): String {
        if (value == Long.MIN_VALUE) return "-9,223,372,036,854,775,808"
        val digits = abs(value).toString()
        val out = StringBuilder(digits.length + digits.length / 3 + 1)
        if (value < 0) out.append('-')
        for ((i, c) in digits.withIndex()) {
            if (i > 0 && (digits.length - i) % 3 == 0) out.append(',')
            out.append(c)
        }
        return out.toString()
    }

    /** [value] holding [decimals] places as a whole number: 1234 with 2 is `12.34`. */
    public fun places(value: Long, decimals: Int): String {
        var scale = 1L
        repeat(decimals) { scale *= 10 }
        val negative = value < 0
        val magnitude = if (negative) -value else value
        val fraction = (magnitude % scale).toString().padStart(decimals, '0')
        return (if (negative) "-" else "") + whole(magnitude / scale) + "." + fraction
    }

    /** [value] divided by [by], to at most one place, halves to even as 1.x's `#,##0.#` did. */
    private fun oneDecimal(value: Long, by: Long): String {
        val negative = value < 0
        val scaled = abs(value) * 10
        var tenths = scaled / by
        val rest = scaled % by
        if (rest * 2 > by || rest * 2 == by && tenths % 2 == 1L) tenths++
        val whole = (if (negative && tenths > 0) "-" else "") + whole(tenths / 10)
        val decimal = tenths % 10
        return if (decimal == 0L) whole else "$whole.$decimal"
    }

    /** Seconds as `45s`, `12m`, `3h 12m` or `2d 4h`. */
    public fun time(seconds: Long): String {
        if (seconds < 60) return "${seconds}s"
        val minutes = seconds / 60
        if (minutes < 60) return "${minutes}m"
        val hours = minutes / 60
        if (hours < 24) return "${hours}h ${minutes % 60}m"
        return whole(hours / 24) + "d " + hours % 24 + "h"
    }

    /** Centimetres as `350 m` or `1.2 km`. */
    public fun distance(centimetres: Long): String {
        val metres = centimetres / 100
        if (metres < 1000) return "$metres m"
        return (if (metres < 100_000) oneDecimal(metres, 1000) else whole(metres / 1000)) + " km"
    }

    /** Kills per death, to two places: `2.50`, or the kills when there are no deaths. */
    public fun ratio(kills: Long, deaths: Long): String {
        if (deaths == 0L) return "$kills.00"
        val hundredths = (kills * 1000 / deaths + 5) / 10
        return "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}"
    }
}

/** Numbers out of placeholder text: `1,234.56`, `$12.5k`, `§a42`, `&#FF0000 42`. */
public object Numbers {
    /**
     * Colour and format codes, which hold digits that aren't the number: hex colours (`&#RRGGBB`,
     * `{#RRGGBB}`, Spigot's `&x&R&R&G&G&B&B`), MiniMessage and other tags in angle brackets, then
     * legacy codes (`§a`, `&l`).
     */
    private val CODES = Regex("(?i)[§&]#[0-9a-f]{6}|\\{#[0-9a-f]{6}}|[§&]x(?:[§&][0-9a-f]){6}|<[^<>]*>|[§&][0-9a-fk-orx]")

    /**
     * The first number, and a k, m, b or t right after it (no space) that isn't the start of a word:
     * `12.5k` is 12,500, while `5 blocks`, `12 kills` and `3mins` are 5, 12 and 3.
     */
    private val NUMBER = Regex("(?i)(-?)(\\d[\\d,]*(?:\\.\\d+)?|\\.\\d+)([kmbt](?![a-z]))?")

    /**
     * The number in a placeholder's text, with [decimals] places kept as a whole number (`"12.34"`
     * with 2 decimals is 1234), rounded half up, or `null` if there's no number or it's too big.
     */
    public fun parse(text: String?, decimals: Int): Long? {
        if (text == null) return null
        val match = NUMBER.find(CODES.replace(text, "")) ?: return null
        val negative = match.groupValues[1] == "-"
        val number = match.groupValues[2].replace(",", "")
        val shift = decimals + when (match.groupValues[3].lowercase()) {
            "k" -> 3
            "m" -> 6
            "b" -> 9
            "t" -> 12
            else -> 0
        }
        val point = number.indexOf('.')
        val whole = if (point < 0) number else number.substring(0, point)
        val fraction = if (point < 0) "" else number.substring(point + 1)
        val digits: String
        var roundUp = false
        if (fraction.length <= shift) {
            digits = whole + fraction + "0".repeat(shift - fraction.length)
        } else {
            digits = whole + fraction.substring(0, shift)
            roundUp = fraction[shift] >= '5'
        }
        var value = (digits.ifEmpty { "0" }).trimStart('0').ifEmpty { "0" }.let { if (it.length > 18) return null else it.toLong() }
        if (roundUp) value += 1
        return if (negative) -value else value
    }
}
