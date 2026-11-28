/*
 * Copyright (c) 2026 Xena Studios
 * SPDX-License-Identifier: GPL-3.0-only
 */
package co.xenastudios.neoskript.frontend.pattern

/** A compiled piece of a syntax pattern. */
public sealed interface PatternElement {

    /** Text matched without regard to case. Never contains whitespace. */
    public data class Literal(val text: String) : PatternElement

    /**
     * A space in the pattern. It matches one or more whitespace characters, or nothing at the start or end
     * of the input or right after other whitespace, so `[the] player` matches `player`.
     */
    public data object Whitespace : PatternElement

    public data class Sequence(val elements: List<PatternElement>) : PatternElement

    /** `(a|b)`: the first alternative that leads to a full match wins. */
    public data class Choice(val alternatives: List<Alternative>) : PatternElement

    /**
     * One alternative of a choice. `mark` comes from `2¦text` and `tag` from `name:text` or `:text`; both
     * are recorded in the match when the alternative is used.
     */
    public data class Alternative(val body: PatternElement, val mark: Int, val tag: String?)

    /** `[...]`: tries to match the choice, then tries skipping it. */
    public data class Optional(val choice: Choice) : PatternElement

    /**
     * `%type%`, numbered from 0 in pattern order. `followedBy` is the literals one of which comes next in
     * the same sequence (after optional whitespace), or null if anything can; `atEnd`, whether nothing can
     * follow the placeholder, so it has to cover the rest of the input.
     */
    public data class Placeholder(
        val index: Int,
        val spec: PlaceholderSpec,
        val followedBy: List<String>?,
        val atEnd: Boolean,
    ) : PatternElement

    /**
     * `<regex>`, numbered from 0 in pattern order. `followedBy` works as for placeholders. `matchesCode`
     * is true for `<.+>`-style regexes that match a piece of code: those can only end where the code is
     * balanced. The regex is kotlin.text.Regex: Java's engine on the JVM, Kotlin's own on Wasm, the
     * browser's RegExp on JS.
     */
    public data class Regex(
        val index: Int,
        val regex: kotlin.text.Regex,
        val followedBy: List<String>?,
        val matchesCode: Boolean,
        val atEnd: Boolean,
    ) : PatternElement
}

/**
 * What a `%...%` placeholder accepts, e.g. `%-*number/timespan@-1%`: the type names as written, `-`
 * (may be left out), `*` (literal only), `~` (changeable only) and `@n` (time state).
 */
public data class PlaceholderSpec(
    val types: List<String>,
    val nullable: Boolean,
    val literalOnly: Boolean,
    val changeableOnly: Boolean,
    val time: Int,
)

/** A syntax pattern that can't be compiled. This is a bug in whoever registered the pattern. */
public class InvalidPatternException(
    public val pattern: String,
    public val index: Int,
    message: String,
) : IllegalArgumentException("$message at index $index in pattern: $pattern")

/**
 * A successful match of a pattern against a line: the XOR of the marks used, the tags used, what each
 * placeholder and regex resolved to and the `[start, end)` of their text, `null` where unused.
 */
public class MatchResult(
    public val mark: Int,
    public val tags: Set<String>,
    public val values: List<Any?>,
    public val spans: List<IntArray?>,
    public val regexValues: List<Any?>,
    public val regexSpans: List<IntArray?>,
) {
    public fun hasTag(tag: String): Boolean = tag in tags

    override fun toString(): String =
        "MatchResult[mark=$mark, tags=$tags, values=$values, spans=${spans.map { it?.contentToString() }}]"
}

/**
 * Parses the text a placeholder might cover. The matcher calls this for each candidate span, shortest
 * first, so implementations should memoize by span and spec.
 */
public fun interface PlaceholderResolver {

    /** What `input[start, end)` parses to as `spec`, or `null` if it doesn't. */
    public fun resolve(input: String, start: Int, end: Int, spec: PlaceholderSpec): Any?

    /** Whether `value` is Skript's unparsed literal that no type's parser takes. */
    public fun unparsed(value: Any?): Boolean = false

    /** Whether the `<regex>` at `index` stands for one of Skript's placeholders. */
    public fun regexAfterRest(index: Int): Boolean = false

    /**
     * Checks the text a `<regex>` matched. `match` is of `input.substring(start, end)`, so its groups'
     * ranges are relative to `start` (Java's `Matcher.region` gave absolute ones).
     */
    public fun resolveRegex(input: String, start: Int, end: Int, index: Int, match: kotlin.text.MatchResult): Any? =
        match

    /** Called as the matcher works, so a parser can stop work on a line that takes too long. */
    public fun step() {}
}
