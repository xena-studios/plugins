package co.xenastudios.core.common.i18n

import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.time.Duration

/**
 * Numbers, lengths of time and dates written for a reader's language: `1,234.5` in English,
 * `1.234,5` in German, `1 234,5` in French. A small table per launch language (en, de, es, fr,
 * pt-BR, ru, pl, zh-CN), from CLDR's, since `common` has no `java.text`; [of] falls back from
 * a region to its language, then to English.
 *
 * Grouping uses a no-break space (U+00A0) where CLDR says a space or a narrow no-break space:
 * every Minecraft client's font draws it, and chat never wraps a number there.
 */
public class Formats private constructor(
    /** The language these are for. */
    public val tag: LanguageTag,
    private val decimal: Char,
    private val group: Char,
    // Spanish and Polish don't group a four-digit number: 1234, but 12.345.
    private val minGrouping: Int,
    private val pluralRule: (Long) -> Plural,
    private val units: Array<Array<String>>,
    private val shortUnits: Array<String>,
    private val unitSeparator: String,
    private val shortUnitSeparator: String,
    // Whether a space goes between a number and its unit: `2 days`; `2w` in English's short form.
    private val unitSpace: Boolean,
    private val shortUnitSpace: Boolean,
    private val dateFormat: (year: Int, month: Int, day: Int) -> String,
    private val twelveHour: Boolean,
    private val dateTimeSeparator: String,
) {
    /** CLDR's plural categories for whole numbers, the ones the launch languages use. */
    public enum class Plural { ONE, FEW, MANY, OTHER }

    /** The plural category of [count] in this language: `1 day`, `2 days`; `2 дня`, `5 дней`. */
    public fun plural(count: Long): Plural = pluralRule.invoke(count)

    /** A whole number: `1,234,567`. */
    public fun number(value: Long): String {
        val negative = value < 0
        // Long.MIN_VALUE has no positive twin; its digits are written as they are.
        val digits = if (value == Long.MIN_VALUE) "9223372036854775808" else abs(value).toString()
        return (if (negative) "-" else "") + grouped(digits)
    }

    /** A whole number: `1,234`. */
    public fun number(value: Int): String = number(value.toLong())

    /**
     * A number with at most [maxDecimals] decimals and at least [minDecimals], rounded half
     * up: `number(1234.5)` is `1,234.5`; `number(2.0, minDecimals = 2)` is `2.00` (a balance).
     * Infinity and NaN are written as `∞` and `NaN`; a value beyond what a whole number holds
     * (about 9.2e18 over the decimals' scale) is written with fewer decimals.
     */
    public fun number(value: Double, maxDecimals: Int = 2, minDecimals: Int = 0): String {
        if (value.isNaN()) return "NaN"
        if (value.isInfinite()) return if (value < 0) "-∞" else "∞"
        val max = maxDecimals.coerceIn(0, 9)
        val min = minDecimals.coerceIn(0, max)
        var places = max
        var scale = POWERS[places]
        while (places > 0 && abs(value) * scale >= 9.0e18) scale = POWERS[--places]
        val scaled = (abs(value) * scale).roundToLong()
        val whole = scaled / scale
        var fraction = (scaled % scale).toString().padStart(places, '0')
        if (places == 0) fraction = ""
        var keep = fraction.length
        while (keep > min && fraction[keep - 1] == '0') keep--
        fraction = fraction.substring(0, keep)
        val negative = value < 0 && scaled != 0L
        val out = StringBuilder()
        if (negative) out.append('-')
        out.append(grouped(whole.toString()))
        if (fraction.isNotEmpty()) out.append(decimal).append(fraction)
        return out.toString()
    }

    private fun grouped(digits: String): String {
        if (digits.length < 3 + minGrouping) return digits
        val out = StringBuilder(digits.length + digits.length / 3)
        val first = digits.length % 3
        if (first > 0) out.append(digits, 0, first)
        var i = first
        while (i < digits.length) {
            if (out.isNotEmpty()) out.append(group)
            out.append(digits, i, i + 3)
            i += 3
        }
        return out.toString()
    }

    /**
     * A length of time in words, its [units] largest units that aren't zero, stopping at the
     * first zero after them (as [co.xenastudios.core.common.time.Durations.format]): `2 weeks,
     * 3 days`, `2 Wochen, 3 Tage`, `2 недели, 3 дня`, `2周3天`. Under a second is `0 seconds`.
     */
    public fun duration(duration: Duration, units: Int = 2): String = duration(duration, units, short = false)

    /** A length of time in short units: `2w 3d`, `2 Wo. 3 Tg.`, `4 ч 30 мин`. */
    public fun durationShort(duration: Duration, units: Int = 2): String = duration(duration, units, short = true)

    private fun duration(duration: Duration, units: Int, short: Boolean): String {
        val most = units.coerceAtLeast(1)
        var rest = if (duration.isNegative()) 0L else duration.inWholeSeconds
        val parts = ArrayList<String>(most)
        for (unit in UNIT_SECONDS.indices) {
            if (parts.size == most) break
            val amount = rest / UNIT_SECONDS[unit]
            if (amount > 0) {
                parts += unit(amount, unit, short)
                rest -= amount * UNIT_SECONDS[unit]
            } else if (parts.isNotEmpty()) {
                break
            }
        }
        if (parts.isEmpty()) parts += unit(0, UNIT_SECONDS.size - 1, short)
        return parts.joinToString(if (short) shortUnitSeparator else unitSeparator)
    }

    private fun unit(amount: Long, unit: Int, short: Boolean): String {
        val name = if (short) shortUnits[unit] else units[unit][plural(amount).ordinal]
        val space = if (if (short) shortUnitSpace else unitSpace) " " else ""
        return number(amount) + space + name
    }

    /** A date: `Nov 23, 2026`, `23.11.2026`, `2026年11月23日`. [utcOffsetSeconds] is the reader's (the server's) time zone. */
    public fun date(epochMillis: Long, utcOffsetSeconds: Int = 0): String {
        val local = Local.of(epochMillis, utcOffsetSeconds)
        return dateFormat.invoke(local.year, local.month, local.day)
    }

    /** A time of day: `3:04 PM` in English, `15:04` elsewhere. */
    public fun time(epochMillis: Long, utcOffsetSeconds: Int = 0): String {
        val local = Local.of(epochMillis, utcOffsetSeconds)
        val minute = local.minute.toString().padStart(2, '0')
        if (!twelveHour) return local.hour.toString().padStart(2, '0') + ":" + minute
        val hour = if (local.hour % 12 == 0) 12 else local.hour % 12
        return "$hour:$minute ${if (local.hour < 12) "AM" else "PM"}"
    }

    /** A date and time: `Nov 23, 2026, 3:04 PM`, `23.11.2026, 15:04`. */
    public fun dateTime(epochMillis: Long, utcOffsetSeconds: Int = 0): String =
        date(epochMillis, utcOffsetSeconds) + dateTimeSeparator + time(epochMillis, utcOffsetSeconds)

    override fun toString(): String = "Formats($tag)"

    /** A moment as a calendar date and time, from days since 1970 (Howard Hinnant's civil-from-days). */
    private class Local(val year: Int, val month: Int, val day: Int, val hour: Int, val minute: Int) {
        companion object {
            fun of(epochMillis: Long, offsetSeconds: Int): Local {
                val seconds = floorDiv(epochMillis, 1000) + offsetSeconds
                val days = floorDiv(seconds, 86_400)
                val secondOfDay = (seconds - days * 86_400).toInt()
                val z = days + 719_468
                val era = floorDiv(z, 146_097)
                val doe = z - era * 146_097
                val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
                val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
                val mp = (5 * doy + 2) / 153
                val day = (doy - (153 * mp + 2) / 5 + 1).toInt()
                val month = (if (mp < 10) mp + 3 else mp - 9).toInt()
                val year = (yoe + era * 400 + if (month <= 2) 1 else 0).toInt()
                return Local(year, month, day, secondOfDay / 3600, secondOfDay % 3600 / 60)
            }

            private fun floorDiv(a: Long, b: Long): Long {
                val q = a / b
                return if ((a % b != 0L) && ((a < 0) != (b < 0))) q - 1 else q
            }
        }
    }

    public companion object {
        private val POWERS = LongArray(10).also { p ->
            p[0] = 1
            for (i in 1 until p.size) p[i] = p[i - 1] * 10
        }

        // Weeks, days, hours, minutes, seconds.
        private val UNIT_SECONDS = longArrayOf(604_800, 86_400, 3_600, 60, 1)

        private const val NBSP = ' '

        // Plural forms by category (ONE, FEW, MANY, OTHER), for a unit.
        private fun forms(one: String, other: String) = arrayOf(one, other, other, other)
        private fun slavic(one: String, few: String, many: String) = arrayOf(one, few, many, many)

        private val ONE_IS_1: (Long) -> Plural = { if (it == 1L) Plural.ONE else Plural.OTHER }

        // French and Portuguese: 0 and 1 are singular.
        private val ONE_IS_0_OR_1: (Long) -> Plural = { if (it == 0L || it == 1L) Plural.ONE else Plural.OTHER }

        private val RUSSIAN: (Long) -> Plural = { n ->
            val ten = n % 10
            val hundred = n % 100
            when {
                ten == 1L && hundred != 11L -> Plural.ONE
                ten in 2..4 && hundred !in 12..14 -> Plural.FEW
                else -> Plural.MANY
            }
        }

        private val POLISH: (Long) -> Plural = { n ->
            val ten = n % 10
            val hundred = n % 100
            when {
                n == 1L -> Plural.ONE
                ten in 2..4 && hundred !in 12..14 -> Plural.FEW
                else -> Plural.MANY
            }
        }

        private fun months(vararg names: String): Array<out String> = names

        private val ENGLISH = Formats(
            LanguageTag.ENGLISH, '.', ',', 1, ONE_IS_1,
            arrayOf(forms("week", "weeks"), forms("day", "days"), forms("hour", "hours"), forms("minute", "minutes"), forms("second", "seconds")),
            arrayOf("w", "d", "h", "m", "s"), ", ", " ", true, false,
            run {
                val names = months("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
                val f: (Int, Int, Int) -> String = { y, m, d -> "${names[m - 1]} $d, $y" }
                f
            },
            twelveHour = true, dateTimeSeparator = ", ",
        )

        private val TABLE: Map<LanguageTag, Formats> = listOf(
            ENGLISH,
            Formats(
                LanguageTag.of("de"), ',', '.', 1, ONE_IS_1,
                arrayOf(forms("Woche", "Wochen"), forms("Tag", "Tage"), forms("Stunde", "Stunden"), forms("Minute", "Minuten"), forms("Sekunde", "Sekunden")),
                arrayOf("Wo.", "Tg.", "Std.", "Min.", "Sek."), ", ", " ", true, true,
                { y, m, d -> "${d.pad()}.${m.pad()}.$y" },
                twelveHour = false, dateTimeSeparator = ", ",
            ),
            Formats(
                LanguageTag.of("es"), ',', '.', 2, ONE_IS_1,
                arrayOf(forms("semana", "semanas"), forms("día", "días"), forms("hora", "horas"), forms("minuto", "minutos"), forms("segundo", "segundos")),
                arrayOf("sem.", "d", "h", "min", "s"), ", ", " ", true, true,
                run {
                    val names = months("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sept", "oct", "nov", "dic")
                    val f: (Int, Int, Int) -> String = { y, m, d -> "$d ${names[m - 1]} $y" }
                    f
                },
                twelveHour = false, dateTimeSeparator = ", ",
            ),
            Formats(
                LanguageTag.of("fr"), ',', NBSP, 1, ONE_IS_0_OR_1,
                arrayOf(forms("semaine", "semaines"), forms("jour", "jours"), forms("heure", "heures"), forms("minute", "minutes"), forms("seconde", "secondes")),
                arrayOf("sem.", "j", "h", "min", "s"), ", ", " ", true, true,
                run {
                    val names = months("janv.", "févr.", "mars", "avr.", "mai", "juin", "juil.", "août", "sept.", "oct.", "nov.", "déc.")
                    val f: (Int, Int, Int) -> String = { y, m, d -> "$d ${names[m - 1]} $y" }
                    f
                },
                twelveHour = false, dateTimeSeparator = ", ",
            ),
            Formats(
                LanguageTag.of("pt-BR"), ',', '.', 1, ONE_IS_0_OR_1,
                arrayOf(forms("semana", "semanas"), forms("dia", "dias"), forms("hora", "horas"), forms("minuto", "minutos"), forms("segundo", "segundos")),
                arrayOf("sem.", "d", "h", "min", "s"), ", ", " ", true, true,
                run {
                    val names = months("jan.", "fev.", "mar.", "abr.", "mai.", "jun.", "jul.", "ago.", "set.", "out.", "nov.", "dez.")
                    val f: (Int, Int, Int) -> String = { y, m, d -> "$d de ${names[m - 1]} de $y" }
                    f
                },
                twelveHour = false, dateTimeSeparator = " ",
            ),
            Formats(
                LanguageTag.of("ru"), ',', NBSP, 1, RUSSIAN,
                arrayOf(
                    slavic("неделя", "недели", "недель"), slavic("день", "дня", "дней"), slavic("час", "часа", "часов"),
                    slavic("минута", "минуты", "минут"), slavic("секунда", "секунды", "секунд"),
                ),
                arrayOf("нед.", "д.", "ч", "мин", "с"), ", ", " ", true, true,
                run {
                    val names = months("янв.", "февр.", "мар.", "апр.", "мая", "июн.", "июл.", "авг.", "сент.", "окт.", "нояб.", "дек.")
                    val f: (Int, Int, Int) -> String = { y, m, d -> "$d ${names[m - 1]} $y г." }
                    f
                },
                twelveHour = false, dateTimeSeparator = ", ",
            ),
            Formats(
                LanguageTag.of("pl"), ',', NBSP, 2, POLISH,
                arrayOf(
                    slavic("tydzień", "tygodnie", "tygodni"), slavic("dzień", "dni", "dni"), slavic("godzina", "godziny", "godzin"),
                    slavic("minuta", "minuty", "minut"), slavic("sekunda", "sekundy", "sekund"),
                ),
                arrayOf("tydz.", "d", "godz.", "min", "s"), ", ", " ", true, true,
                run {
                    val names = months("sty", "lut", "mar", "kwi", "maj", "cze", "lip", "sie", "wrz", "paź", "lis", "gru")
                    val f: (Int, Int, Int) -> String = { y, m, d -> "$d ${names[m - 1]} $y" }
                    f
                },
                twelveHour = false, dateTimeSeparator = ", ",
            ),
            Formats(
                LanguageTag.of("zh-CN"), '.', ',', 1, { Plural.OTHER },
                arrayOf(forms("周", "周"), forms("天", "天"), forms("小时", "小时"), forms("分钟", "分钟"), forms("秒", "秒")),
                arrayOf("周", "天", "小时", "分钟", "秒"), "", "", false, false,
                { y, m, d -> "${y}年${m}月${d}日" },
                twelveHour = false, dateTimeSeparator = " ",
            ),
        ).associateBy { it.tag }

        private fun Int.pad(): String = toString().padStart(2, '0')

        /** The languages with a table of their own. */
        public val LANGUAGES: Set<LanguageTag> get() = TABLE.keys

        /**
         * The formats for [tag]: its own, its language's (`de-AT` → `de`), the table of the
         * language's one region we have (`pt` → `pt-BR`, `zh` → `zh-CN`), or English's. Another
         * region (`zh-TW`, `pt-PT`) gets English's rather than a neighbour's, which may write
         * differently.
         */
        public fun of(tag: LanguageTag): Formats {
            for (candidate in tag.candidates()) TABLE[candidate]?.let { return it }
            // A language whose table is under a region: pt → pt-BR.
            if (tag.region == null && tag.script == null) TABLE.values.firstOrNull { it.tag.language == tag.language }?.let { return it }
            return ENGLISH
        }
    }
}
