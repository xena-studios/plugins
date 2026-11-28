/*
 * Copyright (c) 2026 Xena Studios
 * SPDX-License-Identifier: GPL-3.0-only
 */
package co.xenastudios.neoskript.frontend.pattern

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// neoskript-frontend's PatternTest, test for test.
class PatternTest {

    private val number = Regex("-?\\d+(\\.\\d+)?")
    private val string = Regex("\"([^\"]|\"\")*\"")
    private val plural = Regex("s$")

    /** Numbers, quoted strings, {variables}, and the words "player" and "victim". Anything is "object(s)". */
    private val stub = PlaceholderResolver { input, start, end, spec ->
        val text = input.substring(start, end)
        spec.types.firstOrNull { type ->
            when (plural.replace(type, "")) {
                "number", "integer" -> number.matches(text)
                "string", "text" -> string.matches(text) || text.startsWith("{")
                "player", "entitie", "entity", "commandsender", "command sender" ->
                    text == "player" || text == "victim" || text.startsWith("{")
                "object" -> true
                else -> false
            }
        }?.let { text }
    }

    private fun match(pattern: String, input: String): MatchResult? = PatternCompiler.compile(pattern).match(input, stub)

    private fun values(pattern: String, input: String): List<Any?> =
        assertNotNull(match(pattern, input), "'$pattern' should match '$input'").values

    private fun MatchResult?.spanAt(i: Int): List<Int>? = this!!.spans[i]?.toList()

    @Test
    fun literalsIgnoreCaseAndWhitespaceAmount() {
        assertNotNull(match("stop the server", "STOP   the Server"))
        assertNull(match("stop the server", "stop theserver"))
        assertNull(match("stop", "stopped"))
    }

    @Test
    fun optionalPartsAndTheSpacesAroundThem() {
        val pattern = "[the] (health|hp) of %players%"
        assertEquals(listOf<Any?>("player"), values(pattern, "health of player"))
        assertEquals(listOf<Any?>("player"), values(pattern, "the hp of player"))
        assertNull(match(pattern, "thehealth of player"))
        assertNotNull(match("a [b] [c] d", "a d"))
        assertNotNull(match("a [b] [c] d", "a c d"))
        assertNull(match("a [b] c", "ac"))
    }

    @Test
    fun optionalPlaceholdersAreNullWhenUnused() {
        val pattern = "send %strings% [to %commandsenders%]"
        assertEquals(listOf("\"hi\"", null), values(pattern, "send \"hi\""))
        assertEquals(listOf<Any?>("\"hi to you\"", "player"), values(pattern, "send \"hi to you\" to player"))
        val result = match(pattern, "send \"hi\" to player")
        assertEquals(listOf(5, 9), result.spanAt(0))
        assertEquals(listOf(13, 19), result.spanAt(1))
        assertNull(match(pattern, "send \"hi\"").spanAt(1))
    }

    @Test
    fun placeholdersDontSplitStringsVariablesOrBrackets() {
        assertEquals(
            listOf<Any?>("{_x::%player%}", "\"a to b\""),
            values("set %objects% to %objects%", "set {_x::%player%} to \"a to b\""),
        )
        assertEquals(listOf<Any?>("(1 to 2)", "3"), values("set %objects% to %objects%", "set (1 to 2) to 3"))
        assertEquals(listOf<Any?>("\"say \"\"to\"\" me\""), values("broadcast %strings%", "broadcast \"say \"\"to\"\" me\""))
    }

    @Test
    fun placeholdersTryTheShortestTextFirst() {
        assertEquals(listOf<Any?>("a", "b to c"), values("set %objects% to %objects%", "set a to b to c"))
    }

    @Test
    fun laterUnparsedWordsMakeAPlaceholderTryLongerText() {
        val words = object : PlaceholderResolver {
            override fun resolve(input: String, start: Int, end: Int, spec: PlaceholderSpec): Any = input.substring(start, end)
            override fun unparsed(value: Any?): Boolean = value.toString().contains("junk")
        }
        val pattern = PatternCompiler.compile("set %objects% to %objects%")
        assertEquals(listOf<Any?>("a to junk", "b"), pattern.match("set a to junk to b", words)!!.values)
        assertEquals(listOf<Any?>("a", "junk to junk"), pattern.match("set a to junk to junk", words)!!.values)
    }

    @Test
    fun choicesWithMarksCombineByXor() {
        val pattern = "(1¦add|2¦remove|3¦set) %number% [(4¦twice)]"
        assertEquals(1, match(pattern, "add 5")!!.mark)
        assertEquals(3, match(pattern, "set 5")!!.mark)
        assertEquals(1 xor 4, match(pattern, "add 5 twice")!!.mark)
    }

    @Test
    fun parseTags() {
        val pattern = "[:all] (player|first:first player|:last player)"
        assertEquals(setOf(), match(pattern, "player")!!.tags)
        assertEquals(setOf("all", "first"), match(pattern, "all first player")!!.tags)
        assertEquals(setOf("last player"), match(pattern, "LAST player")!!.tags)
        assertEquals(setOf("implicit"), match("implicit:<.+>", "anything")!!.tags)
    }

    @Test
    fun emptyTagBeforeAChoiceTagsEachAlternative() {
        val pattern = "[the] [:(min|max)] value of %number%"
        assertEquals(setOf("min"), match(pattern, "the min value of 5")!!.tags)
        assertEquals(setOf("max"), match(pattern, "max value of 5")!!.tags)
        assertEquals(setOf(), match(pattern, "value of 5")!!.tags)
        assertEquals(setOf("down"), match("look [:(up|down)] now", "look down now")!!.tags)
        assertEquals(setOf("foo bar"), match("[:foo bar [baz]]", "foo bar baz")!!.tags)
        assertEquals(setOf("all of"), match("[:all of] %object%", "all of b")!!.tags)
        assertEquals(setOf(), match("[:all of] %object%", "b")!!.tags)
    }

    @Test
    fun textBeforeAColonIsItsTagAndIsntMatched() {
        assertEquals(setOf("x "), match("x :all of %object%", "all of b")!!.tags)
        assertNull(match("x :all of %object%", "x all of b"))
        assertEquals(setOf("look "), match("look :[up|down] now", "down now")!!.tags)
        assertNull(match("look :[up|down] now", "look down now"))
        assertEquals(setOf("x "), match("x :(a b|c [d])", "a b")!!.tags)
        assertEquals(setOf("ab"), match("ab:cd %object%", "cd b")!!.tags)
        assertEquals(setOf(" "), match("[json] :values [%string%] of %object%", "values \"a\" of b")!!.tags)
        assertNull(match("[json] :values [%string%] of %object%", "json values \"a\" of b"))
        assertEquals(setOf(), match(":%object%", "b")!!.tags)
    }

    @Test
    fun regexes() {
        val result = match("(exit|stop) <-?\\d+(_\\d+)*> (section|loop|conditional)s", "exit 2 loops")
        assertEquals("2", (result!!.regexValues.first() as kotlin.text.MatchResult).value)
        assertNull(match("(exit|stop) <-?\\d+(_\\d+)*> (section|loop|conditional)s", "exit x loops"))
        assertEquals(
            listOf<Any?>("{_x}", "{_y}"),
            values("%objects% if <.+>[,] (otherwise|else) %objects%", "{_x} if 1 is 1, otherwise {_y}"),
        )
    }

    @Test
    fun lessThanIsTextWhenNoRegexFollows() {
        val pattern = "%objects% ((is|are) less than|<) %objects%"
        assertEquals(listOf<Any?>("1", "2"), values(pattern, "1 < 2"))
        assertEquals(listOf<Any?>("1", "2"), values(pattern, "1 is less than 2"))
    }

    @Test
    fun escapes() {
        assertNotNull(match("chance of %number%[(\\%)]", "chance of 50%"))
        assertNotNull(match("%objects% where \\[<.+>\\]", "{_x::*} where [input > 1]"))
    }

    @Test
    fun placeholderSpecs() {
        val element = PatternCompiler.compile("%-*~item type/entity types@-1%").root
        val spec = (element as PatternElement.Placeholder).spec
        assertEquals(PlaceholderSpec(listOf("item type", "entity types"), true, true, true, -1), spec)
        assertEquals(3, PatternCompiler.compile("%a% [%b%] (%c%|x)").placeholderCount)
    }

    @Test
    fun reportsInvalidPatterns() {
        for (pattern in listOf("(a|b", "[a", "a)", "%number", "%number@x%", "%/x%", "<[>", "a\\")) {
            assertFailsWith<InvalidPatternException>(pattern) { PatternCompiler.compile(pattern) }
        }
    }

    @Test
    fun leadingLiteralsForTheIndex() {
        assertEquals(setOf("health", "hp", "the"), PatternCompiler.compile("[the] (health|hp) of %players%").leadingLiterals)
        assertNull(PatternCompiler.compile("%players%'s health").leadingLiterals)
        assertNull(PatternCompiler.compile("[the] %players%").leadingLiterals)
        assertNull(PatternCompiler.compile("[a]").leadingLiterals)
    }

    @Test
    fun indexReturnsCandidatesInRegistrationOrder() {
        val index = PatternIndex<String>()
        index.add(PatternCompiler.compile("send %strings%"), "send")
        index.add(PatternCompiler.compile("%objects% is set"), "is set")
        index.add(PatternCompiler.compile("[the] health of %players%"), "health")
        index.add(PatternCompiler.compile("-%number%"), "negate")
        index.add(PatternCompiler.compile("set %objects% to %objects%"), "set")
        assertEquals(listOf("send", "is set"), index.candidates("Send \"hi\"").map { it.owner })
        assertEquals(listOf("is set", "health"), index.candidates("the health of player").map { it.owner })
        assertEquals(listOf("is set", "negate"), index.candidates("-5").map { it.owner })
        assertEquals(listOf("is set"), index.candidates("{_x} is set").map { it.owner })
    }

    @Test
    fun spanCandidates() {
        assertEquals(listOf(1, 3, 7), ends("a b {c}"))
        assertEquals(listOf(2, 6), ends("\"\" \"x\" }"), "stops at a closing brace it didn't open")
        assertEquals(listOf(1, 9, 11), ends("a (b c d) e"))
        assertTrue(ends(" a").isEmpty())
    }

    @Test
    fun javaWhitespaceNotKotlins() {
        // A no-break space isn't whitespace to Java (or Skript): it's part of a word, not a gap.
        assertNull(match("stop the server", "stop the server"))
        assertNotNull(match("stop the server", "stop the server"))
    }

    @Test
    fun everySkript217PatternCompiles() {
        for (pattern in co.xenastudios.neoskript.frontend.SKRIPT_PATTERNS) {
            PatternCompiler.compile(pattern)
        }
    }

    private fun ends(input: String): List<Int> = Spans.candidateEnds(input, 0).toList()
}
