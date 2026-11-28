/*
 * Copyright (c) 2026 Xena Studios
 * SPDX-License-Identifier: GPL-3.0-only
 */
package co.xenastudios.neoskript.frontend.parse

import co.xenastudios.neoskript.frontend.source.Text

/** Recognizes the literals every Skript script can use, whatever syntax is registered. */
public object Literals {

    private val INTEGER = Regex("[-+]?\\d+(_\\d+)*")

    /** Decimals, percentages, and scientific notation (`1.23e+4`). */
    private val DECIMAL = Regex(
        "[-+]?(\\d+(_\\d+)*)?\\.\\d+(_\\d+)*|[-+]?\\d+(_\\d+)*(\\.\\d+(_\\d+)*)?%" +
            "|[-+]?(\\d+(_\\d+)*(\\.\\d+(_\\d+)*)?|\\.\\d+(_\\d+)*)[eE][-+]?\\d+",
    )
    private val BOOLEAN = Regex("true|false|yes|no|on|off", RegexOption.IGNORE_CASE)

    private const val AMOUNT = "(?:\\d+(?:\\.\\d+)?|an?|one|two|three|four|five|six|seven|eight|nine|ten)"
    private const val UNIT =
        "(?:(?:real|rl|irl|minecraft|mc) )?(?:milliseconds?|ticks?|seconds?|minutes?|hours?|days?|weeks?|months?|years?)"

    // A unit alone is one of it, as Skript reads "wait tick".
    private const val PART = "(?:$AMOUNT ?)?$UNIT"
    private val TIMESPAN = Regex("$PART(?:(?:,? and |, | )$PART)*", RegexOption.IGNORE_CASE)
    private val TIME = Regex("\\d{1,2}:\\d{2}(?: ?[ap]\\.?m\\.?)?|\\d{1,2} ?[ap]\\.?m\\.?", RegexOption.IGNORE_CASE)

    /**
     * Text that might name something: words, digits, the punctuation item and entity names use, and
     * currency symbols, for money (`$5`, `20€`). Lookaheads and Unicode categories.
     */
    private val WORDS = Regex(
        "(?=[^\\[]*[\\p{L}\\p{Sc}])(?![^\\[]*: )[\\p{L}\\p{Nd}\\p{Nl}\\p{No}_\\p{Sc}]" +
            "[\\p{L}\\p{Nd}\\p{Nl}\\p{No}_\\p{Sc}'.:\\- ]*(\\[[\\p{L}\\p{Nd}\\p{Nl}\\p{No}_=,;:. ]*\\])?",
    )

    /** `value (type)`, a literal whose type is spelled out: `black (wolf variant)`. */
    public val SPECIFIED: Regex = Regex("(.+?)\\s+\\(\\s*([\\p{L} ]+?)\\s*\\)")

    private val BARE_UNIT = Regex(UNIT, RegexOption.IGNORE_CASE)

    /** Java's `\s`: ASCII whitespace only. Kotlin's `\s` isn't that on every target (see the probe). */
    private val SPACES = Regex("[ \\t\\n\\u000B\\f\\r]+")

    /** The type of a number literal (`integer` or `number`), or `null` if it isn't one. */
    public fun numberType(text: String): String? = when {
        INTEGER.matches(text) -> "integer"
        DECIMAL.matches(text) -> "number"
        else -> null
    }

    public fun isBoolean(text: String): Boolean = BOOLEAN.matches(text)

    public fun isTimespan(text: String): Boolean = TIMESPAN.matches(text)

    /** A time unit alone (`tick`), which is one of it only where a timespan is what's wanted. */
    public fun isBareUnit(text: String): Boolean = BARE_UNIT.matches(text)

    public fun isTime(text: String): Boolean = TIME.matches(text)

    public fun isWords(text: String): Boolean = WORDS.matches(text)

    /** Lower case, single-spaced, without a leading article: how closed-set literals are looked up. */
    public fun normalize(text: String): String {
        var lower = Text.lower(Text.strip(text))
        if (lower.contains("  ") || lower.contains('\t')) {
            lower = SPACES.replace(lower, " ")
        }
        for (article in arrayOf("the ", "an ", "a ")) {
            if (lower.startsWith(article)) return lower.substring(article.length)
        }
        return lower
    }
}
