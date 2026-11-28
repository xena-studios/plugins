/*
 * Copyright (c) 2026 Xena Studios
 * SPDX-License-Identifier: GPL-3.0-only
 */
package co.xenastudios.neoskript.frontend.pattern

import co.xenastudios.neoskript.frontend.source.Text

/**
 * Finds the patterns that could match a line without trying all of them. Patterns are filed under the
 * literals they must start with; a line is only tried against patterns filed under a prefix of its first
 * word, plus the patterns that can start with a placeholder. Results keep registration order.
 */
public class PatternIndex<T> {

    public data class Entry<T>(val order: Int, val pattern: CompiledPattern, val owner: T)

    private val byLiteral = HashMap<String, MutableList<Entry<T>>>()
    private val all = ArrayList<Entry<T>>()
    private val unindexed = ArrayList<Entry<T>>()
    private var longestLiteral = 0

    public fun add(pattern: CompiledPattern, owner: T) {
        val entry = Entry(all.size, pattern, owner)
        all.add(entry)
        val leading = pattern.leadingLiterals
        if (leading == null) {
            unindexed.add(entry)
            return
        }
        for (literal in leading) {
            byLiteral.getOrPut(literal) { ArrayList() }.add(entry)
            longestLiteral = maxOf(longestLiteral, literal.length)
        }
    }

    public val size: Int get() = all.size

    /** Every entry, in registration order. */
    public fun all(): List<Entry<T>> = all

    /** The entries that can start with a placeholder, in registration order. */
    public fun unindexed(): List<Entry<T>> = unindexed

    /** The patterns that could match `line`, in registration order. */
    public fun candidates(line: String): List<Entry<T>> = merge(indexed(line), unindexed)

    /** The entries filed under a prefix of the first word of `line`, in registration order. */
    public fun indexed(line: String): List<Entry<T>> {
        val text = Text.lower(Text.strip(line))
        var wordEnd = 0
        while (wordEnd < text.length && !Text.isWhitespace(text[wordEnd])) {
            wordEnd++
        }
        var found: List<Entry<T>> = emptyList()
        for (end in 1..minOf(wordEnd, longestLiteral)) {
            val entries = byLiteral[text.substring(0, end)]
            if (entries != null) {
                found = if (found.isEmpty()) entries else merge(found, entries)
            }
        }
        return found
    }

    public companion object {
        /** Merges two lists sorted by order, dropping duplicates. */
        public fun <T> merge(a: List<Entry<T>>, b: List<Entry<T>>): List<Entry<T>> {
            if (a.isEmpty()) return b
            if (b.isEmpty()) return a
            val merged = ArrayList<Entry<T>>(a.size + b.size)
            var i = 0
            var j = 0
            while (i < a.size || j < b.size) {
                val next = if (j >= b.size || (i < a.size && a[i].order <= b[j].order)) a[i++] else b[j++]
                if (merged.isEmpty() || merged.last().order != next.order) {
                    merged.add(next)
                }
            }
            return merged
        }
    }
}
