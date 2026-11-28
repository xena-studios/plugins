/*
 * Copyright (c) 2026 Xena Studios
 * SPDX-License-Identifier: GPL-3.0-only
 */
package co.xenastudios.neoskript.frontend.pattern

import co.xenastudios.neoskript.frontend.source.Text

/**
 * A compiled syntax pattern. Matching backtracks through the pattern: choices are tried in order,
 * optional parts are tried before being skipped, and placeholders try their shortest text first. The first
 * way that covers the whole input wins. [couldMatch] rules out most patterns cheaply first.
 */
public class CompiledPattern internal constructor(
    public val source: String,
    public val root: PatternElement,
    public val placeholderCount: Int,
    public val regexCount: Int,
) {
    /** The lower-case literals any match must start with, or `null` if it can start with a placeholder or regex. */
    public val leadingLiterals: Set<String>?

    /** Like [leadingLiterals], for the end of a match. */
    public val trailingLiterals: Set<String>?

    private val requiredLiterals: Array<String>

    init {
        val first = edge(root, true)
        leadingLiterals = if (first.open || first.canBeEmpty) null else first.literals
        val last = edge(root, false)
        trailingLiterals = if (last.open || last.canBeEmpty) null else last.literals
        requiredLiterals = required(root).toTypedArray()
    }

    /** Whether `lowerText` contains every literal the pattern needs. */
    public fun couldMatchWithin(lowerText: String): Boolean = requiredLiterals.all { lowerText.contains(it) }

    /** A quick check that rules out most text this pattern can't match. `lowerText` must be stripped. */
    public fun couldMatch(lowerText: String): Boolean {
        if (!couldMatchWithin(lowerText)) return false
        val trailing = trailingLiterals ?: return true
        return trailing.any { lowerText.endsWith(it) }
    }

    /** Matches `input[start, end)`, ignoring surrounding whitespace. Spans in the result are positions in `input`. */
    public fun match(input: String, resolver: PlaceholderResolver, start: Int = 0, end: Int = input.length): MatchResult? {
        var from = start
        var to = end
        while (from < to && Text.isWhitespace(input[from])) from++
        while (to > from && Text.isWhitespace(input[to - 1])) to--
        val stop = to
        val state = Matcher(input, from, to, resolver).match(root, from, State.EMPTY) { pos, s -> if (pos == stop) s else null }
        return state?.toResult(placeholderCount, regexCount)
    }

    override fun toString(): String = source

    /** Parse state collected so far. Immutable, so backtracking needs no undo. */
    private class State(val mark: Int, val tags: Tags?, val captures: Capture?) {

        fun withAlternative(alternative: PatternElement.Alternative): State {
            if (alternative.mark == 0 && alternative.tag == null) return this
            return State(mark xor alternative.mark, if (alternative.tag == null) tags else Tags(alternative.tag, tags), captures)
        }

        fun withCapture(regex: Boolean, index: Int, start: Int, end: Int, value: Any?): State =
            State(mark, tags, Capture(regex, index, start, end, value, captures))

        fun toResult(placeholders: Int, regexCount: Int): MatchResult {
            val values = arrayOfNulls<Any?>(placeholders)
            val spans = arrayOfNulls<IntArray>(placeholders)
            val regexValues = arrayOfNulls<Any?>(regexCount)
            val regexSpans = arrayOfNulls<IntArray>(regexCount)
            var c = captures
            while (c != null) {
                if (c.regex) {
                    regexValues[c.index] = c.value
                    regexSpans[c.index] = intArrayOf(c.start, c.end)
                } else {
                    values[c.index] = c.value
                    spans[c.index] = intArrayOf(c.start, c.end)
                }
                c = c.previous
            }
            val tagSet = LinkedHashSet<String>()
            var t = tags
            while (t != null) {
                tagSet.add(t.tag)
                t = t.previous
            }
            return MatchResult(mark, tagSet, values.asList(), spans.asList(), regexValues.asList(), regexSpans.asList())
        }

        companion object {
            val EMPTY = State(0, null, null)
        }
    }

    private class Tags(val tag: String, val previous: Tags?)

    private class Capture(
        val regex: Boolean,
        val index: Int,
        val start: Int,
        val end: Int,
        val value: Any?,
        val previous: Capture?,
    )

    /** What to do after an element matched: returns the final state, or `null` to backtrack. */
    private fun interface Continuation {
        fun next(pos: Int, state: State): State?
    }

    private class Matcher(val input: String, val start: Int, val end: Int, val resolver: PlaceholderResolver) {

        fun match(element: PatternElement, pos: Int, state: State, next: Continuation): State? {
            resolver.step()
            return when (element) {
                is PatternElement.Literal -> {
                    val text = element.text
                    if (pos + text.length <= end && Text.regionMatchesIgnoreCase(input, pos, text)) next.next(pos + text.length, state) else null
                }
                is PatternElement.Whitespace -> {
                    var after = pos
                    while (after < end && Text.isWhitespace(input[after])) after++
                    if (after > pos || pos == start || pos == end || Text.isWhitespace(input[pos - 1])) next.next(after, state) else null
                }
                is PatternElement.Sequence -> sequence(element.elements, 0, pos, state, next)
                is PatternElement.Choice -> {
                    for (alternative in element.alternatives) {
                        val result = match(alternative.body, pos, state.withAlternative(alternative), next)
                        if (result != null) return result
                    }
                    null
                }
                is PatternElement.Optional -> match(element.choice, pos, state, next) ?: next.next(pos, state)
                is PatternElement.Placeholder -> placeholder(element, pos, state, next)
                is PatternElement.Regex -> regex(element, pos, state, next)
            }
        }

        private fun regex(regex: PatternElement.Regex, pos: Int, state: State, next: Continuation): State? {
            val followedBy = regex.followedBy
            // Code-matching regexes (<.+>) are greedy, like the regex they are: longest text first.
            val ends = when {
                regex.atEnd -> intArrayOf(end)
                regex.matchesCode -> Spans.candidateEnds(input, pos, end).reversedArray()
                else -> IntArray(end - pos) { pos + 1 + it }
            }
            for (to in ends) {
                resolver.step()
                if (followedBy != null && !followedAt(to, followedBy)) continue
                // Java matched with Matcher.region(pos, to).matches(): with its default (opaque, anchoring)
                // bounds that's the same as matching the substring.
                val matched = regex.regex.matchEntire(input.substring(pos, to)) ?: continue
                if (resolver.regexAfterRest(regex.index)) {
                    // Like a placeholder: the rest first, so its errors come before this part's.
                    val rest = next.next(to, state)
                    val value = if (rest == null) null else resolver.resolveRegex(input, pos, to, regex.index, matched)
                    if (value != null) {
                        next.next(to, state.withCapture(true, regex.index, pos, to, value))?.let { return it }
                    }
                } else {
                    val value = resolver.resolveRegex(input, pos, to, regex.index, matched)
                    if (value != null) {
                        next.next(to, state.withCapture(true, regex.index, pos, to, value))?.let { return it }
                    }
                }
            }
            return null
        }

        private fun placeholder(placeholder: PatternElement.Placeholder, pos: Int, state: State, next: Continuation): State? {
            val followedBy = placeholder.followedBy
            val ends = if (placeholder.atEnd) endIfBalanced(pos) else Spans.candidateEnds(input, pos, end)
            var backup: State? = null
            for (to in ends) {
                if (followedBy != null && !followedAt(to, followedBy)) continue
                // As Skript's parser, the rest of the pattern is matched before what this placeholder covers is
                // parsed, so an error in a later placeholder comes first.
                var result = next.next(to, state) ?: continue
                val value = resolver.resolve(input, pos, to, placeholder.spec) ?: continue
                result = result.withCapture(false, placeholder.index, pos, to, value)
                // A later placeholder that's words no type takes: longer text is tried for this one, and the
                // first such match is kept for if none comes without, as in Skript.
                if (!unparsedAfter(result, placeholder.index)) return result
                if (backup == null) backup = result
            }
            return backup
        }

        private fun unparsedAfter(state: State, index: Int): Boolean {
            var c = state.captures
            while (c != null) {
                if (!c.regex && c.index > index && resolver.unparsed(c.value)) return true
                c = c.previous
            }
            return false
        }

        /** `[end]` if `input[pos, end)` is balanced text a placeholder can cover, else nothing. */
        private fun endIfBalanced(pos: Int): IntArray {
            val ends = Spans.candidateEnds(input, pos, end)
            return if (ends.isNotEmpty() && ends.last() == end) intArrayOf(end) else IntArray(0)
        }

        /** Whether one of `literals` comes at `pos`, after any whitespace. */
        private fun followedAt(at: Int, literals: List<String>): Boolean {
            var pos = at
            while (pos < end && Text.isWhitespace(input[pos])) pos++
            return literals.any { pos + it.length <= end && Text.regionMatchesIgnoreCase(input, pos, it) }
        }

        private fun sequence(elements: List<PatternElement>, index: Int, pos: Int, state: State, next: Continuation): State? {
            if (index == elements.size) return next.next(pos, state)
            return match(elements[index], pos, state) { p, s -> sequence(elements, index + 1, p, s, next) }
        }
    }

    /** The literals a match can start (or end) with. `open`: it can start with a placeholder or regex. */
    private class Edge(val literals: MutableSet<String>, val canBeEmpty: Boolean, val open: Boolean)

    private companion object {
        fun edge(element: PatternElement, leading: Boolean): Edge = when (element) {
            is PatternElement.Literal -> Edge(hashSetOf(Text.lower(element.text)), canBeEmpty = false, open = false)
            is PatternElement.Whitespace -> Edge(HashSet(), canBeEmpty = true, open = false)
            is PatternElement.Placeholder, is PatternElement.Regex -> Edge(HashSet(), canBeEmpty = false, open = true)
            is PatternElement.Optional -> edge(element.choice, leading).let { Edge(it.literals, true, it.open) }
            is PatternElement.Choice -> {
                val literals = HashSet<String>()
                var canBeEmpty = false
                var open = false
                for (alternative in element.alternatives) {
                    val e = edge(alternative.body, leading)
                    literals.addAll(e.literals)
                    canBeEmpty = canBeEmpty or e.canBeEmpty
                    open = open or e.open
                }
                Edge(literals, canBeEmpty, open)
            }
            is PatternElement.Sequence -> sequenceEdge(element.elements, leading)
        }

        private fun sequenceEdge(elements: List<PatternElement>, leading: Boolean): Edge {
            val literals = HashSet<String>()
            var open = false
            for (i in elements.indices) {
                val e = edge(elements[if (leading) i else elements.size - 1 - i], leading)
                literals.addAll(e.literals)
                open = open or e.open
                if (!e.canBeEmpty) return Edge(literals, false, open)
            }
            return Edge(literals, canBeEmpty = true, open = true)
        }

        /** Lower-case literals that every match contains. */
        fun required(element: PatternElement): MutableSet<String> = when (element) {
            is PatternElement.Literal -> hashSetOf(Text.lower(element.text))
            is PatternElement.Sequence -> element.elements.flatMapTo(HashSet()) { required(it) }
            is PatternElement.Choice -> {
                var common: MutableSet<String>? = null
                for (alternative in element.alternatives) {
                    val r = required(alternative.body)
                    if (common == null) common = r else common.retainAll(r)
                }
                common ?: HashSet()
            }
            else -> HashSet()
        }
    }
}
