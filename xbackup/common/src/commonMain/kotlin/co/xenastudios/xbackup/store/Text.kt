package co.xenastudios.xbackup.store

/**
 * Just enough JSON for the cloud drives' replies and requests: objects, arrays, strings, numbers,
 * booleans and null. Written here because the command-line restore runs without a server's JSON
 * library, and kept small.
 */
public class Json private constructor(private val text: String) {
    private var pos = 0
    private var depth = 0

    private fun error(what: String) = IOException("bad JSON at $pos: $what", null)

    private fun space() {
        while (pos < text.length && text[pos].isWhitespace()) pos++
    }

    private fun value(): Any? {
        if (pos >= text.length) throw error("unexpected end")
        val c = text[pos]
        if ((c == '{' || c == '[') && ++depth > MAX_DEPTH) throw error("nested more than $MAX_DEPTH deep")
        return when (c) {
            '{' -> obj().also { depth-- }
            '[' -> array().also { depth-- }
            '"' -> string()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            else -> number()
        }
    }

    private fun literal(word: String, value: Any?): Any? {
        if (!text.startsWith(word, pos)) throw error("expected $word")
        pos += word.length
        return value
    }

    private fun obj(): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        pos++
        space()
        if (pos < text.length && text[pos] == '}') {
            pos++
            return map
        }
        while (true) {
            space()
            if (pos >= text.length || text[pos] != '"') throw error("expected a key")
            val key = string()
            space()
            if (pos >= text.length || text[pos] != ':') throw error("expected :")
            pos++
            space()
            map[key] = value()
            space()
            if (pos >= text.length) throw error("unexpected end")
            when (text[pos++]) {
                '}' -> return map
                ',' -> continue
                else -> throw error("expected , or }")
            }
        }
    }

    private fun array(): List<Any?> {
        val list = ArrayList<Any?>()
        pos++
        space()
        if (pos < text.length && text[pos] == ']') {
            pos++
            return list
        }
        while (true) {
            space()
            list += value()
            space()
            if (pos >= text.length) throw error("unexpected end")
            when (text[pos++]) {
                ']' -> return list
                ',' -> continue
                else -> throw error("expected , or ]")
            }
        }
    }

    private fun string(): String {
        val out = StringBuilder()
        pos++
        while (pos < text.length) {
            val c = text[pos++]
            if (c == '"') return out.toString()
            if (c != '\\') {
                out.append(c)
                continue
            }
            if (pos >= text.length) break
            when (val e = text[pos++]) {
                'n' -> out.append('\n')
                't' -> out.append('\t')
                'r' -> out.append('\r')
                'b' -> out.append('\b')
                'f' -> out.append('\u000C')
                'u' -> {
                    if (pos + 4 > text.length) throw error("short \\u escape")
                    val hex = text.substring(pos, pos + 4)
                    val code = hex.toIntOrNull(16) ?: throw error("bad \\u escape $hex")
                    out.append(code.toChar())
                    pos += 4
                }
                else -> out.append(e)
            }
        }
        throw error("unterminated string")
    }

    private fun number(): Any {
        val start = pos
        while (pos < text.length && "+-0123456789.eE".indexOf(text[pos]) >= 0) pos++
        val number = text.substring(start, pos)
        if (number.isEmpty()) throw error("unexpected character")
        return if ('.' in number || 'e' in number || 'E' in number) {
            number.toDoubleOrNull() ?: throw error("bad number $number")
        } else {
            number.toLongOrNull() ?: throw error("bad number $number")
        }
    }

    public companion object {
        /** Deeper than any reply xBackup reads; a hostile one can't overflow the stack. */
        private const val MAX_DEPTH = 64

        /** Parses a document: maps, lists, strings, doubles or longs, booleans and null. */
        public fun parse(text: String): Any? {
            val json = Json(text)
            json.space()
            val value = json.value()
            json.space()
            if (json.pos != text.length) throw json.error("text after the end")
            return value
        }

        /** Parses an object. */
        @Suppress("UNCHECKED_CAST")
        public fun obj(text: String): Map<String, Any?> = parse(text) as? Map<String, Any?> ?: throw IOException("expected a JSON object", null)

        /** [value] as a JSON string, escaping everything outside printable ASCII. */
        public fun quote(value: String): String {
            val out = StringBuilder("\"")
            for (c in value) {
                when {
                    c == '"' || c == '\\' -> out.append('\\').append(c)
                    c.code < 0x20 || c.code > 0x7E -> out.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                    else -> out.append(c)
                }
            }
            return out.append('"').toString()
        }

        /** Writes a flat object of strings, numbers, booleans and lists of them. */
        public fun write(values: Map<String, Any?>): String {
            val out = StringBuilder("{")
            for ((key, value) in values) {
                if (out.length > 1) out.append(',')
                out.append(quote(key)).append(':')
                when (value) {
                    is String -> out.append(quote(value))
                    is List<*> -> out.append(value.joinToString(",", "[", "]") { if (it is String) quote(it) else it.toString() })
                    else -> out.append(value)
                }
            }
            return out.append('}').toString()
        }

        public fun string(map: Map<String, Any?>, key: String): String? = map[key]?.toString()

        public fun number(map: Map<String, Any?>, key: String, fallback: Long): Long = (map[key] as? Number)?.toLong() ?: fallback
    }
}

/**
 * The owner-only YAML files that hold secrets (`secrets.yml`, `tokens.yml`), read and written
 * without a server, so the command-line restore reads them where they are instead of keeping
 * copies of its own. Two levels, by remote name:
 *
 * ```
 * offsite:
 *   password: "..."
 * ```
 *
 * Values are plain, 'single-quoted' or "double-quoted" on one line, as people type them and as
 * this writes them. Anything fancier (a block scalar, a value over several lines) is refused with
 * the line it's on, rather than read wrong. The same rules as 1.x, which reads these files too.
 */
public object SecretText {
    /** The remotes' passwords and secret keys, beside config.yml. */
    public const val SECRETS: String = "secrets.yml"

    /** The values of [text] (from a file called [file]) by remote, then by key. */
    public fun read(text: String, file: String): MutableMap<String, MutableMap<String, String>> {
        val values = LinkedHashMap<String, MutableMap<String, String>>()
        var current: MutableMap<String, String>? = null
        for ((n, line) in text.lines().withIndex()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed == "---" || trimmed == "{}") continue
            val indent = line.length - line.trimStart().length
            val colon = keyEnd(trimmed)
            if (colon < 0) throw problem(file, n)
            val key = unquote(trimmed.substring(0, colon).trim(), file, n)
            val rest = trimmed.substring(colon + 1).trim()
            if (indent == 0) {
                if (rest.isNotEmpty() && !rest.startsWith("#") && rest != "{}") throw problem(file, n)
                current = values.getOrPut(key) { LinkedHashMap() }
            } else {
                (current ?: throw problem(file, n))[key] = scalar(rest, file, n)
            }
        }
        return values
    }

    /** The file's text: [header] as comments, then every value double-quoted on one line. */
    public fun write(header: String, values: Map<String, Map<String, String>>): String {
        val text = StringBuilder()
        for (line in header.lines()) text.append(if (line.isEmpty()) "#" else "# $line").append('\n')
        for ((remote, entries) in values) {
            if (entries.isEmpty()) continue
            text.append(quote(remote)).append(":\n")
            for ((key, value) in entries) text.append("  ").append(quote(key)).append(": ").append(quote(value)).append('\n')
        }
        return text.toString()
    }

    /** [value] as a double-quoted YAML scalar on one line. */
    public fun quote(value: String): String {
        val out = StringBuilder("\"")
        for (c in value) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c.code < 0x20 || c.code == 0x7F -> out.append("\\x").append(c.code.toString(16).padStart(2, '0'))
                else -> out.append(c)
            }
        }
        return out.append('"').toString()
    }

    // Where the key's colon is: the first ": " (or a colon at the end) outside quotes.
    private fun keyEnd(line: String): Int {
        var quote = 0.toChar()
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (quote != 0.toChar()) {
                if (c == quote) quote = 0.toChar() else if (c == '\\' && quote == '"') i++
            } else if (c == '"' || c == '\'') {
                quote = if (i == 0) c else 0.toChar()
            } else if (c == ':' && (i + 1 == line.length || line[i + 1] == ' ')) {
                return i
            }
            i++
        }
        return -1
    }

    private fun scalar(text: String, file: String, n: Int): String {
        if (text.isEmpty() || text.startsWith("#")) return ""
        val first = text[0]
        if (first == '"' || first == '\'') {
            val end = closing(text, first)
            if (end < 0) throw problem(file, n)
            val after = text.substring(end + 1).trim()
            if (after.isNotEmpty() && !after.startsWith("#")) throw problem(file, n)
            return unquote(text.substring(0, end + 1), file, n)
        }
        if (first in "|>{[&*!") throw problem(file, n)
        val comment = text.indexOf(" #")
        val value = (if (comment < 0) text else text.substring(0, comment)).trim()
        return if (value == "~" || value == "null") "" else value
    }

    private fun closing(text: String, quote: Char): Int {
        var i = 1
        while (i < text.length) {
            val c = text[i]
            if (quote == '"' && c == '\\') {
                i++
            } else if (c == quote) {
                if (quote == '\'' && i + 1 < text.length && text[i + 1] == '\'') i++ else return i
            }
            i++
        }
        return -1
    }

    private fun unquote(text: String, file: String, n: Int): String {
        if (text.length < 2 || text[0] != text[text.length - 1] || text[0] != '"' && text[0] != '\'') return text
        val inner = text.substring(1, text.length - 1)
        if (text[0] == '\'') return inner.replace("''", "'")
        val out = StringBuilder(inner.length)
        var i = 0
        while (i < inner.length) {
            val c = inner[i]
            if (c != '\\') {
                out.append(c)
                i++
                continue
            }
            if (++i >= inner.length) throw problem(file, n)
            when (val e = inner[i]) {
                'n' -> out.append('\n')
                't' -> out.append('\t')
                'r' -> out.append('\r')
                '0' -> out.append('\u0000')
                '"', '\\', '/', ' ' -> out.append(e)
                'x', 'u', 'U' -> {
                    val digits = if (e == 'x') 2 else if (e == 'u') 4 else 8
                    if (i + 1 + digits > inner.length) throw problem(file, n)
                    val code = inner.substring(i + 1, i + 1 + digits).toIntOrNull(16) ?: throw problem(file, n)
                    if (code > 0xFFFF) {
                        val v = code - 0x10000
                        out.append((0xD800 + (v shr 10)).toChar()).append((0xDC00 + (v and 0x3FF)).toChar())
                    } else {
                        out.append(code.toChar())
                    }
                    i += digits
                }
                else -> throw problem(file, n)
            }
            i++
        }
        return out.toString()
    }

    private fun problem(file: String, n: Int): IOException =
        IOException("line ${n + 1} of $file isn't a simple \"key: value\" the command-line restore can read. Write each value on one line, in double quotes.", null)
}
