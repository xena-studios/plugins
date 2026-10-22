package co.xenastudios.zchat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.xenastudios.core.command.CommandFailure;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginIdentifiableCommand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class CommandsTest {

    ServerMock server;
    ZChat plugin;
    PlayerMock steve;
    PlayerMock alex;
    PlayerMock mod;

    @BeforeEach
    void start() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(ZChat.class);
        steve = server.addPlayer("Steve");
        alex = server.addPlayer("Alex");
        mod = server.addPlayer("Mod");
        mod.setOp(true);
    }

    @AfterEach
    void stop() {
        MockBukkit.unmock();
    }

    static String next(PlayerMock player) {
        Component message = player.nextComponentMessage();
        return message == null ? null : PlainTextComponentSerializer.plainText().serialize(message);
    }

    @Test
    void privateMessagesReachBothSidesAndSpies() {
        mod.performCommand("socialspy");
        assertEquals("» Social spy on.", next(mod));

        steve.performCommand("msg Alex meet at spawn");
        assertEquals("You → Alex: meet at spawn", next(steve));
        assertEquals("Steve → You: meet at spawn", next(alex));
        assertEquals("[Spy] Steve → Alex: meet at spawn", next(mod));

        alex.performCommand("r omw");
        assertEquals("You → Steve: omw", next(alex));
        assertEquals("Alex → You: omw", next(steve));
    }

    @Test
    void zChatsMsgReplacesMinecrafts() {
        assertTrue(Bukkit.getCommandMap().getCommand("msg") instanceof PluginIdentifiableCommand);
        steve.performCommand("tell Alex hi");
        assertEquals("Steve → You: hi", next(alex));
    }

    @Test
    void privateMessagesAreFilteredButNotMuted() {
        mod.performCommand("mutechat");
        assertEquals("» Chat has been muted by Mod.", next(alex));
        steve.performCommand("msg Alex this badword1 is fine");
        assertEquals("Steve → You: this ******** is fine", next(alex));
    }

    @Test
    void replyNeedsSomeoneToReplyTo() {
        steve.performCommand("reply hello?");
        assertEquals("» You have nobody to reply to.", next(steve));
        steve.performCommand("msg Nobody hi");
        assertEquals("» No player named Nobody is online.", next(steve));
        steve.performCommand("msg Alex");
        assertEquals("» Usage: /msg <player> <message>", next(steve));
    }

    @Test
    void clearChatSkipsBypassersAndSaysWho() {
        mod.performCommand("clearchat");
        String blank = next(steve);
        assertEquals(100, blank.split("\n", -1).length, "one message of 100 lines");
        assertEquals("» Chat was cleared by Mod.", next(steve));
        assertEquals("» Chat was cleared by Mod.", next(mod), "zchat.bypass.clearchat keeps the mod's chat");
    }

    @Test
    void consoleCanMessagePlayersAndBeRepliedTo() {
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "msg Steve server restart soon");
        assertEquals("CONSOLE → You: server restart soon", next(steve));
        steve.performCommand("r ok");
        assertEquals("You → CONSOLE: ok", next(steve));
        assertNull(next(alex));
    }

    @Test
    void failureIsNotABug() {
        // A refused command carries no stack trace, so it never floods the console.
        assertEquals(0, new CommandFailure(plugin.config().messages().error().noPermission()).getStackTrace().length);
    }
}
