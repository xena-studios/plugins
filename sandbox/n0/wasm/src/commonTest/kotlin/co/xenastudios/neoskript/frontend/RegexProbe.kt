/*
 * Copyright (c) 2026 Xena Studios
 * SPDX-License-Identifier: GPL-3.0-only
 */
package co.xenastudios.neoskript.frontend

import kotlin.test.Test

/** The target the tests run on, for the probe's and the benchmark's output. */
internal expect val target: String

/**
 * Not a test: prints what kotlin.text.Regex does with the Java regex features NeoSkript's frontend and
 * Skript's registered `<regex>` patterns use, on each target. The JVM's answers are Java's (Kotlin adds
 * UNICODE_CASE to IGNORE_CASE there); the README compares the others with them.
 */
class RegexProbe {

    private class Case(val name: String, val run: () -> Any?)

    private fun m(regex: String, input: String, ignoreCase: Boolean = false): () -> Any? = {
        (if (ignoreCase) Regex(regex, RegexOption.IGNORE_CASE) else Regex(regex)).matches(input)
    }

    private val cases = listOf(
        Case("\\s takes U+00A0", m("\\s", " ")),
        Case("\\s takes U+2003", m("\\s", " ")),
        Case("\\d takes U+0663", m("\\d", "٣")),
        Case("\\w takes é", m("\\w", "é")),
        Case("\\b between é and x", { Regex("\\bx").containsMatchIn("éx") }),
        Case("\\p{L}+ héllo", m("\\p{L}+", "héllo")),
        Case("\\p{Sc} €", m("\\p{Sc}", "€")),
        Case("\\p{N} ½", m("\\p{N}", "½")),
        Case("\\p{Nl}\\p{No} Ⅳ½", m("\\p{Nl}\\p{No}", "Ⅳ½")),
        Case("\\p{IsAlphabetic} a", m("\\p{IsAlphabetic}", "a")),
        Case("\\p{javaLowerCase} a", m("\\p{javaLowerCase}", "a")),
        Case("\\p{Alpha} a (POSIX)", m("\\p{Alpha}", "a")),
        Case("IGNORE_CASE ascii", m("abc", "ABC", true)),
        Case("IGNORE_CASE é~É", m("é", "É", true)),
        Case("(?i) prefix", m("(?i)abc", "ABC")),
        Case("(?i)é~É", m("(?i)é", "É")),
        Case("a(?i)b mid-pattern", m("a(?i)b", "aB")),
        Case("(?i:b) group flag", m("a(?i:b)", "aB")),
        Case("(?s) dot takes \\n", m("(?s)a.b", "a\nb")),
        Case(". takes \\n", m("a.b", "a\nb")),
        Case("named group", { Regex("(?<a>x)(?<b>y)").matchEntire("xy")?.groups?.get("b")?.value }),
        Case("lookbehind", { Regex("(?<=a)b").find("ab")?.range?.first }),
        Case("neg. lookbehind", { Regex("(?<!a)b").find("ab") != null }),
        Case("lookahead", m("(?=.*b)ab", "ab")),
        Case("possessive a*+a", m("a*+a", "aaa")),
        Case("atomic (?>a|ab)c", m("(?>a|ab)c", "abc")),
        Case("class intersection", m("[a-z&&[^b]]", "b")),
        Case("\\Q.\\E quote", m("\\Q.*\\E", ".*")),
        Case("\\h", m("\\h", " ")),
        Case("\\z \\A", { Regex("\\Aab\\z").matches("ab") }),
        Case("backreference", m("(a)\\1", "aa")),
        Case("matchEntire a|ab on ab", m("a|ab", "ab")),
        Case(". takes 😀", m(".", "😀")),
        Case("replace \$1", { Regex("(a)").replace("xa", "[\$1]") }),
        Case("split \\s+", { Regex("\\s+").split("a  b c").size }),
        Case("{-1} invalid", { Regex("a{2,1}") }),
        Case("bad regex [", { Regex("[") }),
        Case("bad regex throws IAE", { try { Regex("["); "no" } catch (e: IllegalArgumentException) { "IAE" } }),
        Case("Regex.escape a.b", { Regex(Regex.escape("a.b")).matches("a.b") }),
    )

    @Test
    fun probe() {
        for (case in cases) {
            val result = try {
                case.run().toString()
            } catch (e: Throwable) {
                "throws ${e::class.simpleName}"
            }
            println("PROBE $target | ${case.name} | $result")
        }
    }
}
