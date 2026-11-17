package co.xenastudios.zchat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class FilterCommandTest {

    ServerMock server;
    ZChat plugin;
    PlayerMock mod;
    PlayerMock steve;

    @BeforeEach
    void start() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(ZChat.class);
        mod = server.addPlayer("Mod");
        mod.setOp(true);
        steve = server.addPlayer("Steve");
    }

    @AfterEach
    void stop() {
        MockBukkit.unmock();
    }

    static String next(PlayerMock player) {
        Component message = player.nextComponentMessage();
        return message == null ? null : PlainTextComponentSerializer.plainText().serialize(message);
    }

    // The edit runs on the next tick, outside the command.
    void run(String command) {
        mod.performCommand(command);
        server.getScheduler().performOneTick();
    }

    Path config() {
        return plugin.getDataFolder().toPath().resolve("config.yml");
    }

    @Test
    void addingAWordSavesItAndAppliesItAtOnce() throws Exception {
        String before = Files.readString(config());
        run("filter add Grief*");
        assertEquals("» Added grief* to the filter.", next(mod));
        assertNull(next(mod), "no plural tip for a word with *");
        assertEquals(List.of("badword1", "badword2", "grief*"), plugin.config().filter().words());

        String after = Files.readString(config());
        assertTrue(after.contains("grief*"), after);
        // The owner's comments are kept.
        assertTrue(after.contains("# Words to catch, matched as whole words."), after);
        assertTrue(before.lines().count() - 3 <= after.lines().count(), "nothing else was dropped");

        mod.performCommand("filter test stop griefers");
        assertEquals("» That would show as: stop ********", next(mod));
    }

    @Test
    void aWholeWordGetsThePluralTip() {
        run("filter add grief");
        assertEquals("» Added grief to the filter.", next(mod));
        assertEquals("» It catches grief as a whole word. Add grief* to catch plurals and longer words too.", next(mod));
    }

    @Test
    void duplicatesAndMissingWordsChangeNothing() throws Exception {
        String before = Files.readString(config());
        run("filter add BADWORD1");
        assertEquals("» badword1 is already in the filter.", next(mod));
        run("filter remove nothere");
        assertEquals("» nothere isn't in the filter.", next(mod));
        assertEquals(before, Files.readString(config()), "an edit that changes nothing doesn't rewrite the file");
    }

    @Test
    void removingAWord() {
        run("filter remove badword2");
        assertEquals("» Removed badword2 from the filter.", next(mod));
        assertEquals(List.of("badword1"), plugin.config().filter().words());
        mod.performCommand("filter list");
        assertEquals("» Filtered words (1): badword1", next(mod));
    }

    @Test
    void onlyOneWordWithStarsAtItsEnds() {
        run("filter add two words");
        assertEquals("» A filter word is one word, with * only at its start or end. For anything else, use filter.patterns in config.yml.", next(mod));
        run("filter add gr*ef");
        assertEquals("» A filter word is one word, with * only at its start or end. For anything else, use filter.patterns in config.yml.", next(mod));
        assertEquals(List.of("badword1", "badword2"), plugin.config().filter().words());
    }

    @Test
    void testingAMessage() {
        mod.performCommand("filter test hello there");
        assertEquals("» That passes the filter.", next(mod));
        mod.performCommand("filter test this b a d w o r d 1 here");
        String censored = next(mod);
        assertTrue(censored.startsWith("» That would show as: this "), censored);
        assertFalse(censored.contains("b a d"), censored);
    }

    @Test
    void staffOnlyByDefault() {
        steve.performCommand("filter list");
        assertEquals("» You don't have permission to do that.", next(steve));
    }

    @Test
    void aBrokenConfigIsNeverOverwritten() throws Exception {
        Files.writeString(config(), "filter: [unclosed\n");
        run("filter add grief");
        assertEquals("» Something went wrong. The console has the details.", next(mod));
        assertEquals("filter: [unclosed\n", Files.readString(config()));
    }
}
