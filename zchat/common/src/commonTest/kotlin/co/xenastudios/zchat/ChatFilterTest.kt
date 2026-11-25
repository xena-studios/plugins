package co.xenastudios.zchat

import co.xenastudios.zchat.filter.ChatFilter
import co.xenastudios.zchat.filter.Repeats
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 3.x's filter cases, on the JVM and Wasm: what the smart filter catches, and what it leaves alone. */
class ChatFilterTest {
    private fun filter(smart: Boolean, words: List<String>, patterns: List<String> = emptyList(), block: Boolean = false, censorChar: String = "*", warnings: MutableList<String> = ArrayList()) =
        ChatFilter.compile(true, !block, censorChar, smart, words, patterns, warnings)

    private fun ChatFilter.censor(message: String) = apply(message).text

    @Test
    fun smartCatchesTheUsualDodges() {
        val filter = filter(true, listOf("badword"))
        assertEquals("*******", filter.censor("badword"))
        assertEquals("*******!", filter.censor("BadWord!"))
        assertEquals("* * * * * * *", filter.censor("b a d w o r d"), "spaced out")
        assertEquals("*************", filter.censor("b.a.d.w.o.r.d"), "dotted: the dots go too")
        assertEquals("*******", filter.censor("b4dw0rd"), "numbers for letters")
        assertEquals("*********", filter.censor("baaadword"), "stretched")
        assertEquals("*******", filter.censor("bädwörd"), "accents")
        assertEquals("*******", filter.censor("BÄDWÖRD"), "accents in capitals")
        assertEquals("you ******* you", filter.censor("you b@dword you"))
    }

    @Test
    fun invisibleAndLookalikeCharactersDontHideAWord() {
        val filter = filter(true, listOf("badword"))
        assertTrue(filter.apply("bad​word").matched, "zero-width space")
        assertTrue(filter.apply("bad­word").matched, "soft hyphen")
        assertTrue(filter.apply("ｂａｄｗｏｒｄ").matched, "full-width")
        assertTrue(filter.apply("𝐛𝐚𝐝𝐰𝐨𝐫𝐝").matched, "maths bold")
        assertEquals("*******!", filter.censor("ｂａｄｗｏｒｄ!"), "one censor character per letter")
        assertEquals("*******", filter.censor("badwórd́"), "accents go with their letter")
        assertEquals("*******", filter.censor("𝐛𝐚𝐝𝐰𝐨𝐫𝐝"), "one per maths letter")
    }

    @Test
    fun aSpacedWordIsCaughtAfterAOneLetterWord() {
        val filter = filter(true, listOf("badword", "ass"))
        assertEquals("you're a * * * * * * *", filter.censor("you're a b a d w o r d"))
        assertEquals("u * * *", filter.censor("u a s s"))
        assertEquals("4 5 5", filter.censor("4 5 5"), "numbers alone aren't letters in disguise")
    }

    @Test
    fun aWordSplitIntoShortPiecesIsCaught() {
        val filter = filter(true, listOf("badword", "ass"))
        assertEquals("** ** ** *", filter.censor("ba dw or d"))
        assertEquals("so * * ** ** *!", filter.censor("so b a dw or d!"), "single letters and pairs mixed")
        assertEquals("* **", filter.censor("a ss"))
        assertEquals("** ** ** *", filter.censor("b4 dw 0r d"), "folded first, then joined")
    }

    @Test
    fun shortWordsInOrdinaryChatAreLeftAlone() {
        val filter = filter(true, listOf("badword", "ass", "tit", "anal", "hoe"))
        for (message in listOf(
            "is it to an", "go to it", "so is it", "be on at it", "as soon as", "it is as so",
            "ho ho ho", "oh hoe", "an apple", "u r so ok", "a tit is a bird", "12 34 56",
        )) {
            val expected = "hoe" in message || "tit" in message
            assertEquals(expected, filter.apply(message).matched, message)
        }
        assertFalse(filter(true, listOf("bad*")).apply("ba dl y").matched, "a word with * isn't looked for across pieces")
        assertFalse(filter(true, listOf("*ass*")).apply("so as so").matched, "\"as so\" isn't *ass*")
    }

    @Test
    fun aWordThatIsMostlySymbolsIsOnlyMatchedAsTyped() {
        val warnings = ArrayList<String>()
        val filter = filter(true, listOf("c++"), warnings = warnings)
        assertEquals(1, warnings.size)
        assertEquals("a c b", filter.censor("a c b"), "not every lone c")
        assertEquals("I like ***", filter.censor("I like c++"))
    }

    @Test
    fun wordsAreWholeWordsSoInnocentWordsAreLeftAlone() {
        val filter = filter(true, listOf("ass"))
        assertEquals("my class passed", filter.censor("my class passed"))
        assertEquals("an assassin", filter.censor("an assassin"))
        assertEquals("you *** ", filter.censor("you ass "))
        assertEquals("* * *", filter.censor("a s s"), "spaces are kept")
        assertFalse(filter.apply("as").matched, "a double letter must stay double")
    }

    @Test
    fun aStarCatchesLongerWords() {
        val filter = filter(true, listOf("bad*"))
        assertEquals("that's *****", filter.censor("that's badly"))
        assertEquals("not abad", filter.censor("not abad"))
    }

    @Test
    fun withSmartOffWordsMatchOnlyAsTyped() {
        val filter = filter(false, listOf("badword"))
        assertEquals("*******", filter.censor("BADWORD"))
        assertEquals("b4dw0rd", filter.censor("b4dw0rd"))
    }

    @Test
    fun patternsMatchAnywhereAndBadOnesAreSkippedWithAWarning() {
        val warnings = ArrayList<String>()
        val filter = filter(true, emptyList(), listOf("free\\s*money", "[unclosed"), censorChar = "#", warnings = warnings)
        assertEquals("get #### ##### now", filter.censor("get free money now"))
        assertEquals(1, warnings.size)
        assertTrue("[unclosed" in warnings[0], warnings[0])
    }

    @Test
    fun blockModeReportsAMatchWithoutChangingTheText() {
        val filter = filter(true, listOf("badword"), block = true)
        val result = filter.apply("so b a d w o r d")
        assertTrue(result.matched)
        assertEquals("so b a d w o r d", result.text)
        assertEquals(listOf("b a d w o r d"), result.matches("so b a d w o r d"))
        assertFalse(filter.apply("all good").matched)
    }

    @Test
    fun anOffFilterMatchesNothing() {
        assertFalse(ChatFilter.compile(false, true, "*", true, listOf("badword"), emptyList(), ArrayList()).apply("badword").matched)
    }

    @Test
    fun repeatsAreFoldedAndCompared() {
        assertEquals(Repeats.key("Hello there!"), Repeats.key("hello  THERE"))
        assertEquals(100, Repeats.similarity("hellothere", "hellothere"))
        assertTrue(Repeats.similarity("hellothere", "hellotherr") >= 90)
        assertTrue(Repeats.similarity("hello", "goodbye") < 50)
    }
}
