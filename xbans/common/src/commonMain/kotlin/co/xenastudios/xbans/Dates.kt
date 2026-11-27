package co.xenastudios.xbans

/**
 * `lists.date-format`, as 1.x read it with Java's `DateTimeFormatter`, for the patterns servers
 * use: `y`, `M` (`MMM` and `MMMM` as English month names, as 1.x wrote them on an English JVM),
 * `d`, `H`, `h`, `m`, `s`, `a`, `E`, and text in single quotes. Anything else is written as it is.
 * In the server's time zone. Parsed once per pattern.
 */
public class Dates private constructor(private val parts: List<Part>) {
    private class Part(val letter: Char, val count: Int, val text: String)

    /** [epochMillis] in this pattern, [offsetSeconds] from UTC. */
    public fun format(epochMillis: Long, offsetSeconds: Int): String {
        val local = epochMillis + offsetSeconds * 1000L
        val days = floorDiv(local, 86_400_000L)
        val ofDay = local - days * 86_400_000L
        // Days since 1970-01-01 to a civil date (Howard Hinnant's algorithm).
        val z = days + 719_468
        val era = floorDiv(z, 146_097)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val day = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val month = (if (mp < 10) mp + 3 else mp - 9).toInt()
        val year = (yoe + era * 400 + if (month <= 2) 1 else 0).toInt()
        val hour = (ofDay / 3_600_000).toInt()
        val minute = (ofDay / 60_000 % 60).toInt()
        val second = (ofDay / 1000 % 60).toInt()
        val weekday = ((days % 7 + 10) % 7).toInt() // 0 is Monday; 1970-01-01 was a Thursday.
        val out = StringBuilder()
        for (part in parts) {
            when (part.letter) {
                '\u0000' -> out.append(part.text)
                'y', 'u' -> out.append(if (part.count == 2) pad(year % 100, 2) else pad(year, part.count))
                'M', 'L' -> out.append(when {
                    part.count >= 4 -> MONTHS[month - 1]
                    part.count == 3 -> MONTHS[month - 1].take(3)
                    else -> pad(month, part.count)
                })
                'd' -> out.append(pad(day, part.count))
                'H' -> out.append(pad(hour, part.count))
                'h' -> out.append(pad(if (hour % 12 == 0) 12 else hour % 12, part.count))
                'k' -> out.append(pad(if (hour == 0) 24 else hour, part.count))
                'K' -> out.append(pad(hour % 12, part.count))
                'm' -> out.append(pad(minute, part.count))
                's' -> out.append(pad(second, part.count))
                'a' -> out.append(if (hour < 12) "AM" else "PM")
                'E' -> out.append(if (part.count >= 4) DAYS[weekday] else DAYS[weekday].take(3))
                else -> out.append(part.text)
            }
        }
        return out.toString()
    }

    public companion object {
        private val MONTHS = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
        private val DAYS = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
        private const val LETTERS = "yuMLdHhkKmsaE"

        /** [pattern] read once. */
        public fun of(pattern: String): Dates {
            val parts = ArrayList<Part>()
            var i = 0
            while (i < pattern.length) {
                val c = pattern[i]
                when {
                    c == '\'' -> {
                        val end = pattern.indexOf('\'', i + 1)
                        val text = if (end < 0) pattern.substring(i + 1) else pattern.substring(i + 1, end)
                        parts += Part('\u0000', 0, if (text.isEmpty()) "'" else text)
                        i = if (end < 0) pattern.length else end + 1
                    }
                    c in LETTERS -> {
                        var j = i
                        while (j < pattern.length && pattern[j] == c) j++
                        parts += Part(c, j - i, pattern.substring(i, j))
                        i = j
                    }
                    else -> {
                        parts += Part('\u0000', 0, c.toString())
                        i++
                    }
                }
            }
            return Dates(parts)
        }

        private fun pad(value: Int, width: Int): String = value.toString().padStart(width, '0')

        private fun floorDiv(a: Long, b: Long): Long {
            val q = a / b
            return if (a % b != 0L && (a < 0) != (b < 0)) q - 1 else q
        }
    }
}
