/*
 * Copyright (c) 2026 Xena Studios
 * SPDX-License-Identifier: GPL-3.0-only
 */
package co.xenastudios.neoskript.frontend.source

/** Text helpers for the parser's hot paths, with Java's meaning on every target. */
public object Text {

    /**
     * `text.toLowerCase(Locale.ROOT)`, with a fast path for ASCII. Kotlin's `lowercase()` is
     * already locale-independent on every target.
     */
    public fun lower(text: String): String {
        for (i in text.indices) {
            val c = text[i]
            if (c.code >= 0x80) {
                return text.lowercase()
            }
            if (c in 'A'..'Z') {
                val chars = text.toCharArray()
                for (j in i until chars.size) {
                    val d = chars[j]
                    if (d.code >= 0x80) {
                        return text.lowercase()
                    }
                    if (d in 'A'..'Z') {
                        chars[j] = d + ('a' - 'A')
                    }
                }
                return chars.concatToString()
            }
        }
        return text
    }

    /**
     * Java's `Character.isWhitespace`. Kotlin's `Char.isWhitespace()` is not the same: it also
     * takes the no-break spaces (U+00A0, U+2007, U+202F), which Java (and so Skript) doesn't, so
     * `trim()`, `isBlank()` and `isWhitespace()` can't be used where Java's meaning matters.
     */
    public fun isWhitespace(c: Char): Boolean = when (c) {
        ' ', '\t', '\n', '\u000B', '\u000C', '\r', '\u001C', '\u001D', '\u001E', '\u001F' -> true
        ' ', ' ', ' ' -> false
        else -> c.code > 0x7F && c.isWhitespace()
    }

    /** Java's `String.strip()`. */
    public fun strip(text: String): String {
        var start = 0
        var end = text.length
        while (start < end && isWhitespace(text[start])) start++
        while (end > start && isWhitespace(text[end - 1])) end--
        return if (start == 0 && end == text.length) text else text.substring(start, end)
    }

    /** Java's `String.isBlank()`. */
    public fun isBlank(text: String): Boolean = text.all { isWhitespace(it) }

    /** Java's `String.regionMatches(true, ...)`. */
    public fun regionMatchesIgnoreCase(text: String, offset: Int, other: String): Boolean =
        offset + other.length <= text.length && text.regionMatches(offset, other, 0, other.length, ignoreCase = true)
}
