package co.xenastudios.xbans.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.xenastudios.xbans.model.Length;
import org.junit.jupiter.api.Test;

/** Punishment arguments, read the LiteBans way. */
class ArgsTest {

    private static Args parse(String line) {
        return Args.parse(line.split(" "), true, word -> word.equals("hacking"));
    }

    @Test
    void theWikiExample() {
        Args args = parse("Bob -s 10d server:survival Cheating");
        assertEquals("Bob", args.target());
        assertTrue(args.silent());
        assertEquals(Length.parse("10d"), args.length());
        assertEquals("survival", args.scope());
        assertEquals("Cheating", args.reason());
    }

    @Test
    void flagsCanComeAnywhere() {
        Args args = parse("Bob 1d spamming in chat -s -p");
        assertTrue(args.silent());
        assertTrue(args.publicFlag());
        assertEquals("spamming in chat", args.reason());
        assertEquals(Length.parse("1d"), args.length());
    }

    @Test
    void afterDoubleDashEverythingIsReason() {
        Args args = parse("Bob -- said -s in chat");
        assertFalse(args.silent());
        assertEquals("said -s in chat", args.reason());
    }

    @Test
    void lengthWithASpace() {
        Args args = parse("Bob 5 days griefing");
        assertEquals(Length.parse("5d"), args.length());
        assertEquals("griefing", args.reason());
    }

    @Test
    void aDecimalLengthWithASpace() {
        Args args = parse("Bob 1.5 days griefing");
        assertEquals(Length.parse("36h"), args.length());
        assertEquals("griefing", args.reason());
    }

    @Test
    void noLengthMeansNone() {
        Args args = parse("Bob griefing the spawn");
        assertNull(args.length());
        assertEquals("griefing the spawn", args.reason());
    }

    @Test
    void templatesAfterTheLength() {
        Args args = parse("Bob hacking");
        assertEquals("hacking", args.template());
        assertNull(args.reason());
        args = parse("Bob 3d hacking with a client");
        assertEquals("hacking", args.template());
        assertEquals("with a client", args.reason());
    }

    @Test
    void otherFlags() {
        Args args = parse("Bob -S -g -I");
        assertTrue(args.extraSilent());
        assertEquals("*", args.scope());
        assertTrue(args.ip());
        assertNull(args.reason());
    }

    @Test
    void aLengthTypedWrongIsCaught() {
        assertEquals("3dd", parse("Bob 3dd hi").badLength());
        assertEquals("1h30", parse("Bob 1h30 hi").badLength());
        assertEquals("101y", parse("Bob 101y hi").badLength());
        assertNull(parse("Bob 3dprinting is fun").badLength(), "3dprinting isn't a length");
        assertEquals("3dprinting is fun", parse("Bob 3dprinting is fun").reason());
    }

    @Test
    void noPlayerIsNothing() {
        assertNull(Args.parse(new String[0], true, word -> false));
    }

    @Test
    void scopeWords() {
        assertTrue(Args.isGlobal("*"));
        assertTrue(Args.isGlobal("GLOBAL"));
        assertTrue(Args.isLocal("this"));
        assertFalse(Args.isGlobal("survival"));
    }
}
