package co.xenastudios.xvote

/**
 * The little JSON xVote reads and writes: Votifier v2's messages and replies, NuVotifier's forwarded
 * votes and SuperbVote's `votes.json`. No library: Gson isn't on every server xVote runs on in one
 * version, and a vote is a handful of strings. Values come back as `Map<String, Any?>` (in order),
 * `List<Any?>`, `String`, `Double` (or the number's [Number.text]), `Boolean` and `null`.
 */
public object Json {
    /** A number as it was written, so a timestamp keeps every digit. */
    public class Number(public val text: String) {
        public fun toLongOrNull(): Long? = text.toLongOrNull() ?: text.toDoubleOrNull()?.takeIf { it == kotlin.math.floor(it) && kotlin.math.abs(it) < 9.2e18 }?.toLong()

        override fun equals(other: Any?): Boolean = other is Number && other.text == text
        override fun hashCode(): Int = text.hashCode()
        override fun toString(): String = text
    }

    /** What isn't JSON, and where. */
    public class Malformed(message: String) : RuntimeException(message)

    /** The one value [text] holds. Throws [Malformed] on anything else, or on more than [maxDepth] levels. */
    public fun parse(text: String, maxDepth: Int = 32): Any? {
        val reader = Reader(text, maxDepth)
        reader.space()
        val value = reader.value(0)
        reader.space()
        if (!reader.atEnd) throw Malformed("more after the value at ${reader.at}")
        return value
    }

    /** Every value [text] holds one after another (NuVotifier's forwarded votes are objects back to back). */
    public fun parseAll(text: String, maxDepth: Int = 32): List<Any?> {
        val reader = Reader(text, maxDepth)
        val out = ArrayList<Any?>()
        reader.space()
        while (!reader.atEnd) {
            out += reader.value(0)
            reader.space()
        }
        return out
    }

    /** [value] (a map, list, string, number, boolean or null) as compact JSON. */
    public fun write(value: Any?): String = StringBuilder().also { write(value, it) }.toString()

    private fun write(value: Any?, out: StringBuilder) {
        when (value) {
            null -> out.append("null")
            is String -> quote(value, out)
            is Boolean -> out.append(value)
            is Number -> out.append(value.text)
            is Int, is Long -> out.append(value.toString())
            is Double -> out.append(if (value == kotlin.math.floor(value) && kotlin.math.abs(value) < 1e15) value.toLong().toString() else value.toString())
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) out.append(',')
                    first = false
                    quote(k.toString(), out)
                    out.append(':')
                    write(v, out)
                }
                out.append('}')
            }
            is List<*> -> {
                out.append('[')
                for ((i, v) in value.withIndex()) {
                    if (i > 0) out.append(',')
                    write(v, out)
                }
                out.append(']')
            }
            else -> quote(value.toString(), out)
        }
    }

    private fun quote(text: String, out: StringBuilder) {
        out.append('"')
        for (c in text) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c.code < 0x20 -> out.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> out.append(c)
            }
        }
        out.append('"')
    }

    private class Reader(private val text: String, private val maxDepth: Int) {
        var at = 0
        val atEnd: Boolean get() = at >= text.length

        fun space() {
            while (at < text.length && text[at].let { it == ' ' || it == '\n' || it == '\r' || it == '\t' }) at++
        }

        fun value(depth: Int): Any? {
            if (depth > maxDepth) throw Malformed("nested more than $maxDepth deep")
            if (atEnd) throw Malformed("ended where a value was expected")
            return when (val c = text[at]) {
                '{' -> obj(depth)
                '[' -> array(depth)
                '"' -> string()
                't' -> word("true", true)
                'f' -> word("false", false)
                'n' -> word("null", null)
                else -> if (c == '-' || c in '0'..'9') number() else throw Malformed("unexpected '$c' at $at")
            }
        }

        private fun obj(depth: Int): Map<String, Any?> {
            at++
            val out = LinkedHashMap<String, Any?>()
            space()
            if (peek() == '}') {
                at++
                return out
            }
            while (true) {
                space()
                if (peek() != '"') throw Malformed("a key isn't a string at $at")
                val key = string()
                space()
                expect(':')
                space()
                out[key] = value(depth + 1)
                space()
                when (next()) {
                    ',' -> continue
                    '}' -> return out
                    else -> throw Malformed("expected , or } at ${at - 1}")
                }
            }
        }

        private fun array(depth: Int): List<Any?> {
            at++
            val out = ArrayList<Any?>()
            space()
            if (peek() == ']') {
                at++
                return out
            }
            while (true) {
                space()
                out += value(depth + 1)
                space()
                when (next()) {
                    ',' -> continue
                    ']' -> return out
                    else -> throw Malformed("expected , or ] at ${at - 1}")
                }
            }
        }

        private fun string(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                if (atEnd) throw Malformed("a string doesn't end")
                val c = text[at++]
                when {
                    c == '"' -> return out.toString()
                    c == '\\' -> {
                        if (atEnd) throw Malformed("a string doesn't end")
                        when (val e = text[at++]) {
                            '"', '\\', '/' -> out.append(e)
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (at + 4 > text.length) throw Malformed("a \\u escape is cut short")
                                val code = text.substring(at, at + 4).toIntOrNull(16) ?: throw Malformed("a \\u escape isn't hex at $at")
                                out.append(code.toChar())
                                at += 4
                            }
                            else -> throw Malformed("unknown escape \\$e at ${at - 1}")
                        }
                    }
                    else -> out.append(c)
                }
            }
        }

        private fun number(): Number {
            val start = at
            if (peek() == '-') at++
            while (at < text.length && text[at].let { it in '0'..'9' || it == '.' || it == 'e' || it == 'E' || it == '+' || it == '-' }) at++
            val written = text.substring(start, at)
            if (written.toDoubleOrNull() == null) throw Malformed("\"$written\" isn't a number")
            return Number(written)
        }

        private fun word(word: String, value: Any?): Any? {
            if (!text.startsWith(word, at)) throw Malformed("unexpected text at $at")
            at += word.length
            return value
        }

        private fun peek(): Char = if (atEnd) throw Malformed("ended too soon") else text[at]

        private fun next(): Char = if (atEnd) throw Malformed("ended too soon") else text[at++]

        private fun expect(c: Char) {
            if (next() != c) throw Malformed("expected '$c' at ${at - 1}")
        }
    }
}
