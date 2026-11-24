package co.xenastudios.core.testing

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.config.YamlNode
import co.xenastudios.core.common.config.YamlReader
import co.xenastudios.core.common.config.YamlSyntaxError

/**
 * A YAML reader for tests, so a plugin's config and language files are read the same on
 * wasmWasi, where no YAML library is published yet, as on the JVM. It reads the block style configs are written in: `key: value`, nested sections,
 * `- item` lists, `[a, b]` and `{}`, quoted and plain scalars, and comments. It isn't the
 * reader plugins use: on the JVM that's snakeyaml-engine-kmp (`core-jvm`'s `JvmYaml`), whose
 * tests read the same kind of files with it.
 */
public object TestYaml : YamlReader {

    private class Line(val number: Int, val indent: Int, val text: String)

    private class Failure(message: String, val line: Int) : Exception(message)

    override fun read(text: String): Outcome<YamlNode, YamlSyntaxError> = try {
        val lines = text.split('\n').mapIndexedNotNull { i, raw ->
            if ('\t' in raw.takeWhile { it == ' ' || it == '\t' }) throw Failure("tab in indentation", i + 1)
            val content = stripComment(raw).trimEnd()
            if (content.isBlank()) null else Line(i + 1, content.length - content.trimStart().length, content.trim())
        }
        if (lines.isEmpty()) {
            Outcome.Ok(YamlNode.Null(1))
        } else {
            val (node, next) = block(lines, 0, lines[0].indent)
            if (next < lines.size) throw Failure("unexpected indentation", lines[next].number)
            Outcome.Ok(node)
        }
    } catch (e: Failure) {
        Outcome.Err(YamlSyntaxError(e.message ?: "invalid", e.line))
    }

    private fun block(lines: List<Line>, start: Int, indent: Int): Pair<YamlNode, Int> =
        if (isItem(lines[start].text)) sequence(lines, start, indent) else mapping(lines, start, indent)

    private fun isItem(text: String) = text == "-" || text.startsWith("- ")

    private fun mapping(lines: List<Line>, start: Int, indent: Int): Pair<YamlNode, Int> {
        val entries = mutableListOf<YamlNode.Mapping.Entry>()
        var i = start
        while (i < lines.size && lines[i].indent == indent && !isItem(lines[i].text)) {
            val line = lines[i]
            val colon = keyEnd(line.text) ?: throw Failure("expected key: value", line.number)
            val key = line.text.substring(0, colon).trim().let { if (it.startsWith('"')) unquote(it, line.number) else it }
            if (entries.any { it.key == key }) throw Failure("duplicate key $key", line.number)
            val rest = line.text.substring(colon + 1).trim()
            i++
            val value = when {
                rest.isNotEmpty() -> value(rest, line.number)
                i < lines.size && (lines[i].indent > indent || (lines[i].indent == indent && isItem(lines[i].text))) -> {
                    val (child, next) = block(lines, i, lines[i].indent)
                    i = next
                    child
                }
                else -> YamlNode.Null(line.number)
            }
            entries += YamlNode.Mapping.Entry(key, value, line.number)
        }
        if (i < lines.size && lines[i].indent > indent) throw Failure("unexpected indentation", lines[i].number)
        return YamlNode.Mapping(entries, lines[start].number) to i
    }

    private fun sequence(lines: List<Line>, start: Int, indent: Int): Pair<YamlNode, Int> {
        val items = mutableListOf<YamlNode>()
        var i = start
        while (i < lines.size && lines[i].indent == indent && isItem(lines[i].text)) {
            val rest = lines[i].text.removePrefix("-").trim()
            items += if (rest.isEmpty()) YamlNode.Null(lines[i].number) else value(rest, lines[i].number)
            i++
        }
        return YamlNode.Sequence(items, lines[start].number) to i
    }

    private fun value(text: String, line: Int): YamlNode = when {
        text == "{}" -> YamlNode.Mapping(emptyList(), line)
        text.startsWith('[') -> {
            if (!text.endsWith(']')) throw Failure("unclosed [", line)
            val inner = text.substring(1, text.length - 1).trim()
            YamlNode.Sequence(if (inner.isEmpty()) emptyList() else splitFlow(inner, line).map { value(it.trim(), line) }, line)
        }
        text.startsWith('{') -> throw Failure("flow mappings aren't read by the test reader", line)
        text.startsWith('"') || text.startsWith('\'') -> YamlNode.Scalar(unquote(text, line), line)
        text == "null" || text == "~" -> YamlNode.Null(line)
        else -> YamlNode.Scalar(text, line)
    }

    private fun splitFlow(text: String, line: Int): List<String> {
        val parts = mutableListOf<String>()
        var quote: Char? = null
        var from = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quote == '"' && c == '\\' -> i++
                quote != null && c == quote -> quote = null
                quote == null && (c == '"' || c == '\'') -> quote = c
                quote == null && c == ',' -> {
                    parts += text.substring(from, i)
                    from = i + 1
                }
            }
            i++
        }
        if (quote != null) throw Failure("unclosed quote", line)
        parts += text.substring(from)
        return parts
    }

    // Where the key's colon is: the first ": " (or a ':' at the end) outside quotes.
    private fun keyEnd(text: String): Int? {
        var quote: Char? = null
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quote == '"' && c == '\\' -> i++
                quote != null && c == quote -> quote = null
                quote == null && (c == '"' || c == '\'') && i == 0 -> quote = c
                quote == null && c == ':' && (i == text.length - 1 || text[i + 1] == ' ') -> return i
            }
            i++
        }
        return null
    }

    private fun unquote(text: String, line: Int): String {
        val q = text[0]
        if (text.length < 2 || text.last() != q) throw Failure("unclosed quote", line)
        val body = text.substring(1, text.length - 1)
        if (q == '\'') return body.replace("''", "'")
        val out = StringBuilder()
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c != '\\') {
                out.append(c)
                i++
                continue
            }
            if (i + 1 >= body.length) throw Failure("bad escape", line)
            when (val e = body[i + 1]) {
                'n' -> out.append('\n')
                't' -> out.append('\t')
                'r' -> out.append('\r')
                '"' -> out.append('"')
                '\\' -> out.append('\\')
                'x' -> {
                    out.append(body.substring(i + 2, i + 4).toInt(16).toChar())
                    i += 2
                }
                else -> throw Failure("unknown escape \\$e", line)
            }
            i += 2
        }
        return out.toString()
    }

    private fun stripComment(raw: String): String {
        var quote: Char? = null
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            when {
                quote == '"' && c == '\\' -> i++
                quote != null && c == quote -> quote = null
                quote == null && (c == '"' || c == '\'') && (i == 0 || raw[i - 1] == ' ' || raw[i - 1] == '[' || raw[i - 1] == '-') -> quote = c
                quote == null && c == '#' && (i == 0 || raw[i - 1] == ' ') -> return raw.substring(0, i)
            }
            i++
        }
        return raw
    }
}
