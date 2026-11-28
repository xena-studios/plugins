/*
 * Copyright (c) 2026 Xena Studios
 * SPDX-License-Identifier: GPL-3.0-only
 */
package co.xenastudios.neoskript.frontend.pattern

import co.xenastudios.neoskript.frontend.source.Text

/**
 * Compiles Skript's pattern language: `[optional]` and `(a|b)` choices, `%type%` placeholders with
 * `-`, `*`, `~` flags, `type1/type2` and `@n`; `<regex>` when a closing `>` follows; `n¦` marks and
 * `tag:` or `:` tags at the start of an alternative; `\` to escape the next character.
 */
public class PatternCompiler private constructor(private val source: String) {

    private var pos = 0
    private var placeholders = 0
    private var regexes = 0

    public companion object {
        public fun compile(pattern: String): CompiledPattern {
            val compiler = PatternCompiler(pattern)
            val root = compiler.alternative(-1)
            if (compiler.pos < pattern.length) {
                throw compiler.error("Unexpected '${pattern[compiler.pos]}'")
            }
            var element: PatternElement =
                if (root.mark == 0 && root.tag == null) root.body else PatternElement.Choice(listOf(root))
            element = anchorTail(element)
            return CompiledPattern(pattern, element, compiler.placeholders, compiler.regexes)
        }

        /** `(a|b)` or `[a|b]` after an empty tag, with each text alternative tagged with its text. */
        private fun tagEach(element: PatternElement): PatternElement? {
            val choice = when (element) {
                is PatternElement.Choice -> element
                is PatternElement.Optional -> element.choice
                else -> null
            }
            if (choice == null || choice.alternatives.size < 2) {
                return null
            }
            val alternatives = choice.alternatives.map { alternative ->
                val text = plainText(alternative.body)
                if (text == null || text.isEmpty() || alternative.mark != 0 || alternative.tag != null) {
                    alternative
                } else {
                    PatternElement.Alternative(alternative.body, 0, Text.lower(text))
                }
            }
            val tagged = PatternElement.Choice(alternatives)
            return if (element is PatternElement.Optional) PatternElement.Optional(tagged) else tagged
        }

        /** The text of an element that's only text and spaces, or `null`. */
        private fun plainText(element: PatternElement): String? = when (element) {
            is PatternElement.Literal -> element.text
            is PatternElement.Whitespace -> " "
            is PatternElement.Sequence -> {
                val text = StringBuilder()
                var plain = true
                for (each in element.elements) {
                    val part = plainText(each)
                    if (part == null) {
                        plain = false
                        break
                    }
                    text.append(part)
                }
                if (plain) Text.strip(text.toString()) else null
            }
            else -> null
        }

        private fun replaceFirst(sequence: PatternElement.Sequence, first: PatternElement): PatternElement.Sequence {
            val elements = sequence.elements.toMutableList()
            elements[0] = first
            return PatternElement.Sequence(elements)
        }

        /** The text since the last group, placeholder or regex in this sequence, spaces included. */
        private fun textBefore(elements: List<PatternElement>, literal: StringBuilder): String {
            val text = StringBuilder()
            for (i in elements.indices.reversed()) {
                when (val element = elements[i]) {
                    is PatternElement.Literal -> text.insert(0, element.text)
                    is PatternElement.Whitespace -> text.insert(0, ' ')
                    else -> break
                }
            }
            return text.append(literal).toString()
        }

        /** Makes `elements[from...]`, text and spaces, one alternative tagged `tag`, spaces after it outside. */
        private fun tagText(elements: MutableList<PatternElement>, from: Int, tag: String) {
            var end = elements.size
            while (end > from && elements[end - 1] is PatternElement.Whitespace) {
                end--
            }
            if (end <= from) {
                return
            }
            val tagged = elements.subList(from, end).toList()
            val after = elements.subList(end, elements.size).toList()
            elements.subList(from, elements.size).clear()
            val body = if (tagged.size == 1) tagged.first() else PatternElement.Sequence(tagged)
            elements.add(PatternElement.Choice(listOf(PatternElement.Alternative(body, 0, tag))))
            elements.addAll(after)
        }

        /** Records the literals that can come after each placeholder and regex in a sequence. */
        private fun linkLookahead(elements: MutableList<PatternElement>) {
            for (i in elements.indices) {
                val next = (if (i + 1 < elements.size) leadingLiterals(elements, i + 1) else null) ?: continue
                when (val element = elements[i]) {
                    is PatternElement.Placeholder -> elements[i] = element.copy(followedBy = next)
                    is PatternElement.Regex -> elements[i] = element.copy(followedBy = next)
                    else -> {}
                }
            }
        }

        /** The literals one of which must come first in `elements[from...]`, skipping whitespace, or null. */
        private fun leadingLiterals(elements: List<PatternElement>, from: Int): List<String>? {
            var i = from
            while (i < elements.size && elements[i] is PatternElement.Whitespace) {
                i++
            }
            return if (i >= elements.size) null else startLiterals(elements[i])
        }

        private fun startLiterals(element: PatternElement): List<String>? = when (element) {
            is PatternElement.Literal -> listOf(element.text)
            is PatternElement.Sequence -> leadingLiterals(element.elements, 0)
            is PatternElement.Choice -> {
                val all = ArrayList<String>()
                var open = false
                for (alternative in element.alternatives) {
                    val literals = startLiterals(alternative.body)
                    if (literals == null) {
                        open = true
                        break
                    }
                    all.addAll(literals)
                }
                if (open) null else all
            }
            else -> null
        }

        /** Marks the placeholders and regexes that end the pattern: they have to cover the rest of the input. */
        private fun anchorTail(element: PatternElement): PatternElement = when (element) {
            is PatternElement.Placeholder -> element.copy(followedBy = null, atEnd = true)
            is PatternElement.Regex -> element.copy(followedBy = null, atEnd = true)
            is PatternElement.Sequence -> {
                val elements = element.elements.toMutableList()
                for (i in elements.indices.reversed()) {
                    val last = elements[i]
                    if (last is PatternElement.Whitespace) {
                        continue
                    }
                    // Only the last element: an earlier optional part may be followed by a later one.
                    elements[i] = anchorTail(last)
                    break
                }
                PatternElement.Sequence(elements)
            }
            is PatternElement.Choice -> PatternElement.Choice(element.alternatives.map { it.copy(body = anchorTail(it.body)) })
            is PatternElement.Optional -> PatternElement.Optional(anchorTail(element.choice) as PatternElement.Choice)
            else -> element
        }

        private fun flush(literal: StringBuilder, elements: MutableList<PatternElement>) {
            if (literal.isNotEmpty()) {
                elements.add(PatternElement.Literal(literal.toString()))
                literal.setLength(0)
            }
        }

        /** The text an element starts with, up to the first part that isn't text, for `:text` tags. */
        private fun literalText(element: PatternElement): String = when (element) {
            is PatternElement.Literal -> element.text
            is PatternElement.Whitespace -> " "
            is PatternElement.Sequence -> {
                val text = StringBuilder()
                for (each in element.elements) {
                    if (each !is PatternElement.Literal && each !is PatternElement.Whitespace) {
                        break
                    }
                    text.append(literalText(each))
                }
                Text.strip(text.toString())
            }
            else -> ""
        }
    }

    /** One alternative, up to `|`, the `close` character, or the end (when close is -1). */
    private fun alternative(close: Int): PatternElement.Alternative {
        var mark = 0
        var tag: String? = null
        var autoTag = false
        val markEnd = markEnd()
        if (markEnd > 0) {
            mark = source.substring(pos, markEnd).toInt()
            pos = markEnd + 1
        }
        val tagEnd = tagEnd()
        if (tagEnd >= 0) {
            if (tagEnd == pos) {
                autoTag = true
            } else {
                tag = Text.lower(source.substring(pos, tagEnd))
            }
            pos = tagEnd + 1
        }
        var body = sequence(close)
        if (autoTag) {
            val first = if (body is PatternElement.Sequence) body.elements.first() else body
            val tagged = tagEach(first)
            if (tagged != null) {
                // ':(a|b)' is '(a:a|b:b)', as in Skript.
                body = if (first === body) tagged else replaceFirst(body as PatternElement.Sequence, tagged)
            } else {
                val text = Text.lower(literalText(body))
                tag = text.ifEmpty { null }
            }
        }
        return PatternElement.Alternative(body, mark, tag)
    }

    /** The index of `¦` if the alternative starts with `digits¦` or `-digits¦`, otherwise -1. */
    private fun markEnd(): Int {
        val start = if (pos < source.length && source[pos] == '-') pos + 1 else pos
        var i = start
        // Java's Character.isDigit is any Unicode digit, as is Char.isDigit; toInt() then takes them too.
        while (i < source.length && source[i].isDigit()) {
            i++
        }
        return if (i > start && i < source.length && source[i] == '¦') i else -1
    }

    /** The index of `:` if the alternative starts with `word:` or `:`, otherwise -1. */
    private fun tagEnd(): Int {
        var i = pos
        while (i < source.length && (source[i].isLetterOrDigit() || source[i] == '_' || source[i] == '-')) {
            i++
        }
        return if (i < source.length && source[i] == ':') i else -1
    }

    private fun sequence(close: Int): PatternElement {
        var tagNext = false
        val elements = ArrayList<PatternElement>()
        val literal = StringBuilder()
        // ':text' with nothing before the colon: the text up to the next group or placeholder is tagged with itself.
        var textTag: String? = null
        var textTagStart = 0
        var textTagEnd = -1
        while (pos < source.length) {
            val c = source[pos]
            if (textTag != null && pos >= textTagEnd) {
                flush(literal, elements)
                tagText(elements, textTagStart, textTag)
                textTag = null
            }
            if (c == '|' || c.code == close) {
                break
            }
            if (c == ')' || c == ']') {
                throw error("Unmatched '$c'")
            }
            if (Text.isWhitespace(c)) {
                flush(literal, elements)
                while (pos < source.length && Text.isWhitespace(source[pos])) {
                    pos++
                }
                if (elements.isEmpty() || elements.last() !is PatternElement.Whitespace) {
                    elements.add(PatternElement.Whitespace)
                }
                continue
            }
            when (c) {
                '\\' -> {
                    if (pos + 1 >= source.length) {
                        throw error("Nothing to escape after '\\'")
                    }
                    literal.append(source[pos + 1])
                    pos += 2
                }
                '[', '(' -> {
                    flush(literal, elements)
                    pos++
                    val choice = choice(if (c == '[') ']' else ')')
                    val group = if (c == '[') PatternElement.Optional(choice) else choice
                    val tagged = if (tagNext) tagEach(group) else null
                    elements.add(tagged ?: group)
                    tagNext = false
                }
                ':' -> {
                    // A tag, as Skript's PatternCompiler reads one: the text since the last group, placeholder or
                    // regex ('x ' in 'x :all') is the tag, and isn't matched. With no text before it, it tags the
                    // literal after it ('[:all of]'), or each text choice of the group after it (':(a|b)').
                    val next = if (pos + 1 < source.length) source[pos + 1] else '\u0000'
                    val end = textEnd(pos + 1)
                    val before = textBefore(elements, literal)
                    if (before.isNotEmpty()) {
                        literal.setLength(0)
                        while (elements.isNotEmpty() &&
                            (elements.last() is PatternElement.Literal || elements.last() is PatternElement.Whitespace)
                        ) {
                            elements.removeAt(elements.size - 1)
                        }
                        elements.add(
                            PatternElement.Choice(
                                listOf(PatternElement.Alternative(PatternElement.Sequence(emptyList()), 0, Text.lower(before))),
                            ),
                        )
                    } else if (next == '(' || next == '[') {
                        tagNext = true
                    } else if (textTag == null && end > pos + 1 && !Text.isBlank(source.substring(pos + 1, end))) {
                        textTag = Text.lower(Text.strip(source.substring(pos + 1, end)))
                        textTagStart = elements.size
                        textTagEnd = end
                    }
                    pos++
                }
                '%' -> {
                    flush(literal, elements)
                    elements.add(placeholder())
                }
                '<' -> {
                    val end = regexEnd()
                    if (end < 0) {
                        literal.append(c)
                        pos++
                    } else {
                        flush(literal, elements)
                        elements.add(regex(end))
                    }
                }
                else -> {
                    literal.append(c)
                    pos++
                }
            }
        }
        flush(literal, elements)
        if (textTag != null) {
            tagText(elements, textTagStart, textTag)
        }
        linkLookahead(elements)
        return if (elements.size == 1) elements.first() else PatternElement.Sequence(elements)
    }

    /** Where the plain text starting at `from` ends: at a group, a placeholder, a regex or a choice's end. */
    private fun textEnd(from: Int): Int {
        var i = from
        while (i < source.length && "[(%<|)]:\\¦".indexOf(source[i]) < 0) {
            i++
        }
        return i
    }

    private fun choice(close: Char): PatternElement.Choice {
        val open = pos - 1
        val alternatives = ArrayList<PatternElement.Alternative>()
        while (true) {
            alternatives.add(alternative(close.code))
            if (pos >= source.length) {
                pos = open
                throw error("Unclosed '${source[open]}'")
            }
            val c = source[pos++]
            if (c == close) {
                return PatternElement.Choice(alternatives)
            }
        }
    }

    private fun placeholder(): PatternElement {
        val start = pos
        val end = source.indexOf('%', start + 1)
        if (end < 0) {
            throw error("Unclosed '%'")
        }
        pos = end + 1
        val body = source.substring(start + 1, end)
        var nullable = false
        var literalOnly = false
        var changeableOnly = false
        var i = 0
        while (i < body.length) {
            when (body[i]) {
                '-' -> nullable = true
                '*' -> literalOnly = true
                '~' -> changeableOnly = true
                else -> break
            }
            i++
        }
        var types = body.substring(i)
        var time = 0
        val at = types.indexOf('@')
        if (at >= 0) {
            // Java's Integer.parseInt: an optional sign and digits. toIntOrNull is the same on every target.
            time = types.substring(at + 1).toIntOrNull() ?: run {
                pos = start
                throw error("Invalid time state in '%$body%'")
            }
            types = types.substring(0, at)
        }
        val typeNames = types.split('/').map { Text.strip(it) }
        if (typeNames.any { it.isEmpty() }) {
            pos = start
            throw error("Missing type in '%$body%'")
        }
        return PatternElement.Placeholder(
            placeholders++,
            PlaceholderSpec(typeNames, nullable, literalOnly, changeableOnly, time),
            null,
            false,
        )
    }

    /** The index of the `>` closing a regex that starts at `pos`, or -1 if there is none. */
    private fun regexEnd(): Int {
        var i = pos + 1
        while (i < source.length) {
            val c = source[i]
            if (c == '\\') {
                i++
            } else if (c == '>') {
                return if (i > pos + 1) i else -1
            }
            i++
        }
        return -1
    }

    private fun regex(end: Int): PatternElement {
        val regex = source.substring(pos + 1, end)
        // Java's PatternSyntaxException is IllegalArgumentException's subclass; Kotlin's Regex throws an
        // IllegalArgumentException (or a subclass) on every target, with a message of the engine's own.
        val compiled = try {
            Regex(regex)
        } catch (e: IllegalArgumentException) {
            throw error("Invalid regex <$regex>: ${e.message}")
        }
        val element = PatternElement.Regex(regexes++, compiled, null, regex == ".+" || regex == "(.+)", false)
        pos = end + 1
        return element
    }

    private fun error(message: String): InvalidPatternException = InvalidPatternException(source, pos, message)
}
