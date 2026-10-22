package co.xenastudios.zchat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.xenastudios.core.text.Message;
import co.xenastudios.zchat.chat.ChatFilter;
import co.xenastudios.zchat.config.Settings;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChatFilterTest {

    static ChatFilter filter(boolean smart, List<String> words, List<String> patterns) {
        return ChatFilter.compile(new Settings.Filter(true, Settings.FilterMode.CENSOR, "*", smart, words, patterns, Message.empty()), new ArrayList<>());
    }

    static String censor(ChatFilter filter, String message) {
        return filter.apply(message).text();
    }

    @Test
    void smartCatchesTheUsualDodges() {
        ChatFilter filter = filter(true, List.of("badword"), List.of());
        assertEquals("*******", censor(filter, "badword"));
        assertEquals("*******!", censor(filter, "BadWord!"));
        assertEquals("* * * * * * *", censor(filter, "b a d w o r d"), "spaced out");
        assertEquals("*************", censor(filter, "b.a.d.w.o.r.d"), "dotted: the dots go too");
        assertEquals("*******", censor(filter, "b4dw0rd"), "numbers for letters");
        assertEquals("*********", censor(filter, "baaadword"), "stretched");
        assertEquals("*******", censor(filter, "bädwörd"), "accents");
        assertEquals("you ******* you", censor(filter, "you b@dword you"));
    }

    @Test
    void invisibleAndLookalikeCharactersDontHideAWord() {
        ChatFilter filter = filter(true, List.of("badword"), List.of());
        assertTrue(filter.apply("bad\u200Bword").matched(), "zero-width space");
        assertTrue(filter.apply("bad\u00ADword").matched(), "soft hyphen");
        assertTrue(filter.apply("ｂａｄｗｏｒｄ").matched(), "full-width");
        assertTrue(filter.apply("\uD835\uDC1B\uD835\uDC1A\uD835\uDC1D\uD835\uDC30\uD835\uDC28\uD835\uDC2B\uD835\uDC1D").matched(), "maths bold");
        assertEquals("*******!", censor(filter, "ｂａｄｗｏｒｄ!"), "one censor character per letter");
        assertEquals("*******", censor(filter, "badwo\u0301rd\u0301"), "accents go with their letter");
    }

    @Test
    void aSpacedWordIsCaughtAfterAOneLetterWord() {
        ChatFilter filter = filter(true, List.of("badword", "ass"), List.of());
        assertEquals("you're a * * * * * * *", censor(filter, "you're a b a d w o r d"));
        assertEquals("u * * *", censor(filter, "u a s s"));
        assertEquals("4 5 5", censor(filter, "4 5 5"), "numbers alone aren't letters in disguise");
    }

    @Test
    void aWordThatIsMostlySymbolsIsOnlyMatchedAsTyped() {
        List<String> warnings = new ArrayList<>();
        ChatFilter filter = ChatFilter.compile(new Settings.Filter(true, Settings.FilterMode.CENSOR, "*", true,
            List.of("c++"), List.of(), Message.empty()), warnings);
        assertEquals(1, warnings.size());
        assertEquals("a c b", censor(filter, "a c b"), "not every lone c");
    }

    @Test
    void wordsAreWholeWordsSoInnocentWordsAreLeftAlone() {
        ChatFilter filter = filter(true, List.of("ass"), List.of());
        assertEquals("my class passed", censor(filter, "my class passed"));
        assertEquals("an assassin", censor(filter, "an assassin"));
        assertEquals("you *** ", censor(filter, "you ass "));
        assertEquals("* * *", censor(filter, "a s s"), "spaces are kept");
        assertFalse(filter.apply("as").matched(), "a double letter must stay double");
    }

    @Test
    void aStarCatchesLongerWords() {
        ChatFilter filter = filter(true, List.of("bad*"), List.of());
        assertEquals("that's *****", censor(filter, "that's badly"));
        assertEquals("not abad", censor(filter, "not abad"));
    }

    @Test
    void withSmartOffWordsMatchOnlyAsTyped() {
        ChatFilter filter = filter(false, List.of("badword"), List.of());
        assertEquals("*******", censor(filter, "BADWORD"));
        assertEquals("b4dw0rd", censor(filter, "b4dw0rd"));
    }

    @Test
    void patternsMatchAnywhereAndBadOnesAreSkippedWithAWarning() {
        List<String> warnings = new ArrayList<>();
        ChatFilter filter = ChatFilter.compile(new Settings.Filter(true, Settings.FilterMode.CENSOR, "#", true,
            List.of(), List.of("free\\s*money", "[unclosed"), Message.empty()), warnings);
        assertEquals("get #### ##### now", censor(filter, "get free money now"));
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains("[unclosed"));
    }

    @Test
    void blockModeReportsAMatchWithoutChangingTheText() {
        ChatFilter filter = ChatFilter.compile(new Settings.Filter(true, Settings.FilterMode.BLOCK, "*", true,
            List.of("badword"), List.of(), Message.empty()), new ArrayList<>());
        ChatFilter.Result result = filter.apply("so b a d w o r d");
        assertTrue(result.matched());
        assertEquals("so b a d w o r d", result.text());
        assertFalse(filter.apply("all good").matched());
    }

    @Test
    void anOffFilterMatchesNothing() {
        ChatFilter filter = ChatFilter.compile(new Settings.Filter(false, Settings.FilterMode.CENSOR, "*", true,
            List.of("badword"), List.of(), Message.empty()), new ArrayList<>());
        assertFalse(filter.apply("badword").matched());
    }
}
