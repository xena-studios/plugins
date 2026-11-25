package co.xenastudios.zchat.filter

/**
 * A message folded the way someone dodging a filter would hope it isn't: lowercase, accents
 * dropped, full-width and maths letters made plain, common number-for-letter swaps undone, and
 * everything that isn't a letter or digit turned into one space. Keeps where each character came
 * from, so a match can be censored in what the player actually typed.
 *
 * Common code has no Unicode normalizer (`java.text.Normalizer` is the JDK's), so the letters
 * people dress words up with are folded from tables: Latin's accented letters (Latin-1 and
 * Extended-A and -B), full-width forms and the maths alphanumerics, which is what 3.x's NFKD
 * fold caught in practice.
 */
internal class Folded private constructor(
    /** The folded text: letters and digits, with single spaces between words. */
    val text: String,
    /** For each character of [text], its index in the original; -1 for spaces. */
    val origin: IntArray,
) {
    /** One word of [text], from [start] up to [end]; [joined] if it's a run of single letters ("b a d"). */
    class Token(val start: Int, val end: Int, val joined: Boolean) {
        val length: Int get() = end - start
    }

    /** The words of the text as typed, nothing joined. */
    fun words(): List<Token> {
        val words = ArrayList<Token>()
        var start = 0
        for (i in 0..text.length) {
            if (i == text.length || text[i] == ' ') {
                if (i > start) words += Token(start, i, false)
                start = i + 1
            }
        }
        return words
    }

    /** The token's letters, with the spaces of a joined run taken out. */
    fun letters(token: Token): String = text.substring(token.start, token.end).replace(" ", "")

    /** Where each of [letters]' characters is in [text]. */
    fun letterPositions(token: Token): IntArray {
        val positions = IntArray(token.length)
        var count = 0
        for (i in token.start until token.end) if (text[i] != ' ') positions[count++] = i
        return positions.copyOf(count)
    }

    companion object {
        /** What a character folds to when it's dropped, not even leaving a gap. */
        private const val SKIP = '\u0000'

        fun of(original: String): Folded {
            val text = StringBuilder(original.length)
            val origin = IntArray(original.length)
            var length = 0
            var i = 0
            while (i < original.length) {
                val codePoint = codePointAt(original, i)
                val folded = fold(codePoint)
                if (folded != SKIP) {
                    if (folded != ' ') {
                        text.append(folded)
                        origin[length++] = i
                    } else if (length > 0 && text[length - 1] != ' ') {
                        text.append(' ')
                        origin[length++] = -1
                    }
                }
                i += charCount(codePoint)
            }
            if (length > 0 && text[length - 1] == ' ') {
                length--
                text.setLength(length)
            }
            return Folded(text.toString(), origin.copyOf(length))
        }

        /** A filter word folded the same way, with nothing but letters and digits left. */
        fun word(word: String): String {
            val out = StringBuilder(word.length)
            var i = 0
            while (i < word.length) {
                val codePoint = codePointAt(word, i)
                val f = fold(codePoint)
                if (f != ' ' && f != SKIP) out.append(f)
                i += charCount(codePoint)
            }
            return out.toString()
        }

        /** The words of a folded text, with runs of single letters ("b a d") joined into one. */
        fun tokens(words: List<Token>): List<Token> {
            val joined = ArrayList<Token>(words.size)
            var i = 0
            while (i < words.size) {
                var j = i
                while (j + 1 < words.size && words[j].length == 1 && words[j + 1].length == 1) j++
                joined += if (j > i) Token(words[i].start, words[j].end, true) else words[i]
                i = j + 1
            }
            return joined
        }

        internal fun codePointAt(text: String, index: Int): Int {
            val high = text[index]
            if (high.isHighSurrogate() && index + 1 < text.length) {
                val low = text[index + 1]
                if (low.isLowSurrogate()) return ((high.code - 0xD800) shl 10) + (low.code - 0xDC00) + 0x10000
            }
            return high.code
        }

        internal fun charCount(codePoint: Int): Int = if (codePoint >= 0x10000) 2 else 1

        /** Whether [codePoint] is a mark or an invisible character: dropped with the letter before it when censoring. */
        internal fun invisible(codePoint: Int): Boolean {
            if (codePoint >= 0x10000) return codePoint in 0xE0000..0xE007F || codePoint in 0x1D173..0x1D17A
            val category = codePoint.toChar().category
            return category == CharCategory.NON_SPACING_MARK || category == CharCategory.FORMAT || category == CharCategory.ENCLOSING_MARK
        }

        internal fun isDigit(codePoint: Int): Boolean =
            if (codePoint >= 0x10000) codePoint in 0x1D7CE..0x1D7FF else codePoint.toChar().isDigit()

        internal fun isWhitespace(codePoint: Int): Boolean = codePoint < 0x10000 && codePoint.toChar().isWhitespace()

        private fun fold(codePoint: Int): Char {
            if (codePoint >= 0x10000) return foldSupplementary(codePoint)
            val c = codePoint.toChar()
            when (c) {
                '0' -> return 'o'
                '1' -> return 'i'
                '3' -> return 'e'
                '4' -> return 'a'
                '5' -> return 's'
                '7' -> return 't'
                '@' -> return 'a'
                '$' -> return 's'
                else -> {}
            }
            // Full-width forms (ａ, ０) are their ASCII letters and digits.
            if (c in '０'..'９' || c in 'Ａ'..'Ｚ' || c in 'ａ'..'ｚ') return fold(c.code - 0xFEE0)
            if (c.isLetterOrDigit()) {
                val lower = c.lowercaseChar()
                return ACCENTS[lower] ?: lower
            }
            // An accent typed on its own belongs to the letter before it, and invisible characters
            // (zero-width spaces, soft hyphens) are put in to split a word: skip both.
            return if (invisible(codePoint)) SKIP else ' '
        }

        // Beyond the first plane: maths letters (𝐚, 𝓪, 𝟎) fold to plain ones; tag characters are invisible.
        private fun foldSupplementary(codePoint: Int): Char {
            if (codePoint in 0x1D400..0x1D6A3) {
                val offset = (codePoint - 0x1D400) % 52
                return if (offset < 26) 'a' + offset else 'a' + (offset - 26)
            }
            if (codePoint in 0x1D7CE..0x1D7FF) return fold('0'.code + (codePoint - 0x1D7CE) % 10)
            if (invisible(codePoint)) return SKIP
            // Other letters out here (scripts of their own, emoji's letters) are gaps.
            return ' '
        }

        /** Each accented Latin letter (lower case) and the letter it's dressed up from. */
        private val ACCENTS: Map<Char, Char> = buildMap {
            val table = mapOf(
                'a' to "àáâãäåāăąǎǟǡǻȁȃȧª",
                'b' to "ƀɓ",
                'c' to "çćĉċčƈ",
                'd' to "ďđɗ",
                'e' to "èéêëēĕėęěȅȇȩɇ",
                'f' to "ƒ",
                'g' to "ĝğġģǥǧǵ",
                'h' to "ĥħȟ",
                'i' to "ìíîïĩīĭįıǐȉȋ",
                'j' to "ĵǰ",
                'k' to "ķĸǩƙ",
                'l' to "ĺļľŀłƚ",
                'n' to "ñńņňŉŋǹ",
                'o' to "òóôõöøōŏőơǒǫǭǿȍȏȫȭȯȱº",
                'r' to "ŕŗřȑȓ",
                's' to "śŝşšș",
                't' to "ţťŧț",
                'u' to "ùúûüũūŭůűųưǔǖǘǚǜȕȗ",
                'w' to "ŵ",
                'y' to "ýÿŷƴȳ",
                'z' to "źżžƶȥ",
            )
            for ((base, dressed) in table) for (c in dressed) put(c, base)
        }
    }
}
