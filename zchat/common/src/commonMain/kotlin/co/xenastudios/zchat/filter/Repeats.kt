package co.xenastudios.zchat.filter

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** How alike two messages are, for anti-repeat. */
public object Repeats {
    /** Messages with fewer letters than this, like "gg", are never repeats. */
    public const val SHORTEST: Int = 3

    /** The message as anti-repeat compares it: folded, with the spaces taken out. */
    public fun key(message: String): String = Folded.of(message).text.replace(" ", "")

    /** Similarity from 0 to 100: 100 is identical. */
    public fun similarity(a: String, b: String): Int {
        if (a == b) return 100
        val longest = max(a.length, b.length)
        if (longest == 0) return 100
        return (100.0 * (longest - distance(a, b)) / longest).roundToInt()
    }

    // Levenshtein distance in two rows: chat messages are at most 256 characters.
    private fun distance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            val c = a[i - 1]
            for (j in 1..b.length) {
                val replace = previous[j - 1] + if (c == b[j - 1]) 0 else 1
                current[j] = min(replace, min(previous[j], current[j - 1]) + 1)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }
}
