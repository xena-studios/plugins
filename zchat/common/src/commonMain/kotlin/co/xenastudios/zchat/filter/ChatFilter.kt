package co.xenastudios.zchat.filter

/**
 * The chat filter, compiled once per config load. Words are matched as whole words, and with
 * `smart` on, against the [Folded] message too, so spacing, number swaps, accents and stretched
 * letters don't get past it. Patterns are regular expressions matched anywhere in the message as
 * typed. Matching whole tokens is what keeps innocent words (*class*, *assassin*) clean.
 */
public class ChatFilter private constructor(
    private val censor: Boolean,
    private val censorChar: String,
    /** Matches a whole folded token that's one of the words, or `null` if there are none. */
    private val smartWords: Regex?,
    /** As [smartWords], but only the words without a `*`: for words split into short pieces. */
    private val smartWhole: Regex?,
    /** Matches the words in the message as typed, or `null` if there are none. */
    private val plainWords: Regex?,
    private val patterns: List<Regex>,
) {
    /** What the filter made of a message: [text] is censored when the filter censors. */
    public class Result internal constructor(
        public val matched: Boolean,
        public val text: String,
        private val hits: BooleanArray?,
    ) {
        /** The parts of [message] that matched, as typed: for the API's filter event. */
        public fun matches(message: String): List<String> {
            val hits = hits ?: return emptyList()
            val parts = ArrayList<String>()
            var i = 0
            while (i < hits.size) {
                if (!hits[i]) {
                    i++
                    continue
                }
                var end = i
                while (end < hits.size && hits[end]) end++
                parts += message.substring(i, minOf(end, message.length))
                i = end
            }
            return parts
        }
    }

    /** Whether there's nothing to look for. */
    public val isEmpty: Boolean get() = smartWords == null && plainWords == null && patterns.isEmpty()

    /** Checks [message], censoring what matched when the filter censors. */
    public fun apply(message: String): Result {
        if (isEmpty) return Result(false, message, null)
        val hit = BooleanArray(message.length)
        var any = false
        fun mark(from: Int, to: Int) {
            if (to <= from) return
            for (i in from until minOf(to, hit.size)) hit[i] = true
            any = true
        }
        plainWords?.findAll(message)?.forEach { mark(it.range.first, it.range.last + 1) }
        for (pattern in patterns) pattern.findAll(message).forEach { mark(it.range.first, it.range.last + 1) }
        if (smartWords != null) {
            val folded = Folded.of(message)
            val words = folded.words()
            for (token in Folded.tokens(words)) {
                val letters = folded.letters(token)
                if (!token.joined) {
                    if (smartWords.matches(letters)) mark(folded.origin[token.start], end(message, folded.origin[token.end - 1]))
                    continue
                }
                // In "u r a b a d w o r d" every letter is a word, so any part can be one. A run of
                // digits alone ("4 5 5") is numbers, not letters in disguise.
                val positions = folded.letterPositions(token)
                if (onlyDigits(message, folded, positions)) continue
                for (match in smartWords.findAll(letters)) {
                    if (match.range.isEmpty()) continue
                    mark(folded.origin[positions[match.range.first]], end(message, folded.origin[positions[match.range.last]]))
                }
            }
            if (smartWhole != null) any = markSplitWords(message, folded, words, smartWhole, ::mark) || any
        }
        if (!any) return Result(false, message, null)
        return Result(true, if (censor) censored(message, hit) else message, hit)
    }

    /**
     * A word split into short pieces, "ba dw or d": runs of pieces of one or two letters, at least
     * one of them two, are joined and must make a whole filter word, never just contain one, so
     * ordinary short words ("is it to an") are left alone. Words with a `*` are left out: "as so"
     * would make "*ass*".
     */
    private fun markSplitWords(message: String, folded: Folded, words: List<Folded.Token>, whole: Regex, mark: (Int, Int) -> Unit): Boolean {
        var found = false
        val joined = StringBuilder()
        for (i in words.indices) {
            if (words[i].length > SHORT_PIECE) continue
            joined.setLength(0)
            joined.append(folded.text, words[i].start, words[i].end)
            var twoLetters = words[i].length == SHORT_PIECE
            var j = i + 1
            while (j < words.size && j - i < MAX_PIECES && words[j].length <= SHORT_PIECE) {
                val piece = words[j]
                joined.append(folded.text, piece.start, piece.end)
                twoLetters = twoLetters || piece.length == SHORT_PIECE
                if (twoLetters && whole.matches(joined)) {
                    val start = words[i].start
                    val positions = folded.letterPositions(Folded.Token(start, piece.end, true))
                    if (!onlyDigits(message, folded, positions)) {
                        mark(folded.origin[start], end(message, folded.origin[piece.end - 1]))
                        found = true
                    }
                }
                j++
            }
        }
        return found
    }

    // Every matched character but spaces becomes the censor character, one per code point;
    // accents and invisible characters straight after a censored one go with it.
    private fun censored(message: String, hit: BooleanArray): String {
        val out = StringBuilder(message.length)
        var censoring = false
        var i = 0
        while (i < message.length) {
            val codePoint = Folded.codePointAt(message, i)
            val size = Folded.charCount(codePoint)
            when {
                censoring && Folded.invisible(codePoint) -> {}
                hit[i] && !Folded.isWhitespace(codePoint) -> {
                    out.append(censorChar)
                    censoring = true
                }
                else -> {
                    out.append(message, i, i + size)
                    censoring = false
                }
            }
            i += size
        }
        return out.toString()
    }

    public companion object {
        private const val WORD_CHAR = "[\\p{L}\\p{N}]"

        /** A word split into short pieces ("ba dw or d") is looked for across at most this many. */
        private const val MAX_PIECES = 16

        /** The longest a piece can be and still be joined to the pieces beside it. */
        private const val SHORT_PIECE = 2

        /** A filter that never matches. */
        public val NONE: ChatFilter = ChatFilter(true, "*", null, null, null, emptyList())

        /**
         * Compiles the filter's settings, once per config load. A pattern that isn't a valid
         * regular expression is left out, with a warning added to [warnings].
         */
        public fun compile(
            enabled: Boolean,
            censor: Boolean,
            censorChar: String,
            smart: Boolean,
            words: List<String>,
            patterns: List<String>,
            warnings: MutableList<String>,
        ): ChatFilter {
            if (!enabled) return NONE
            val smartWords = ArrayList<String>()
            val whole = ArrayList<String>()
            val plain = ArrayList<String>()
            for (word in words) {
                val trimmed = word.trim()
                val before = trimmed.startsWith("*")
                val after = trimmed.endsWith("*") && trimmed.length > 1
                val core = trimmed.substring(if (before) 1 else 0, trimmed.length - if (after) 1 else 0)
                if (core.isBlank()) continue
                plain += (if (before) "$WORD_CHAR*" else "") + Regex.escape(core) + (if (after) "$WORD_CHAR*" else "")
                val folded = Folded.word(core)
                if (smart && folded.length < 2) {
                    // "c++" would fold to "c" and catch every lone c.
                    warnings += "filter.words: \"$word\" is mostly symbols, so it's matched only as typed, not smartly."
                } else if (smart) {
                    smartWords += (if (before) "$WORD_CHAR*" else "") + stretchable(folded) + (if (after) "$WORD_CHAR*" else "")
                    if (!before && !after) whole += stretchable(folded)
                }
            }
            val compiled = ArrayList<Regex>()
            for (pattern in patterns) {
                try {
                    compiled += Regex(pattern, RegexOption.IGNORE_CASE)
                } catch (e: IllegalArgumentException) {
                    warnings += "filter.patterns: \"$pattern\" isn't a valid regular expression (${e.message?.lineSequence()?.firstOrNull() ?: "it can't be read"}), so it's skipped."
                }
            }
            return ChatFilter(
                censor,
                censorChar,
                if (smartWords.isEmpty()) null else Regex("(?:" + smartWords.joinToString("|") + ")", RegexOption.IGNORE_CASE),
                if (whole.isEmpty()) null else Regex("(?:" + whole.joinToString("|") + ")", RegexOption.IGNORE_CASE),
                if (plain.isEmpty()) null else Regex("(?<!$WORD_CHAR)(?:" + plain.joinToString("|") + ")(?!$WORD_CHAR)", RegexOption.IGNORE_CASE),
                compiled,
            )
        }

        // "baad" -> "b+a{2,}d+": each run of a letter matches that many or more.
        private fun stretchable(folded: String): String {
            val regex = StringBuilder()
            var i = 0
            while (i < folded.length) {
                val c = folded[i]
                var run = 1
                while (i + run < folded.length && folded[i + run] == c) run++
                regex.append(Regex.escape(c.toString())).append(if (run == 1) "+" else "{$run,}")
                i += run
            }
            return regex.toString()
        }

        // The end of the code point at [index]: one past it, two for a surrogate pair.
        private fun end(message: String, index: Int): Int = index + Folded.charCount(Folded.codePointAt(message, index))

        private fun onlyDigits(message: String, folded: Folded, positions: IntArray): Boolean =
            positions.all { Folded.isDigit(Folded.codePointAt(message, folded.origin[it])) }
    }
}
