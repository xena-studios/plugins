package co.xenastudios.zchat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.chat.SignedMessage;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** Chat events as a server fires them: off the main thread, then drawn for each viewer. */
class ChatTest {

    ServerMock server;
    ZChat plugin;
    PlayerMock steve;
    PlayerMock alex;

    @BeforeEach
    void start() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(ZChat.class);
        steve = server.addPlayer("Steve");
        alex = server.addPlayer("Alex");
    }

    @AfterEach
    void stop() {
        MockBukkit.unmock();
    }

    /** Lets a player chat back to back, for tests about something other than spam. */
    void chatty(PlayerMock player) {
        player.addAttachment(plugin, "zchat.bypass.cooldown", true);
        player.addAttachment(plugin, "zchat.bypass.anti-repeat", true);
    }

    static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    AsyncChatEvent chat(PlayerMock player, String text) {
        Set<Audience> viewers = new HashSet<>(Bukkit.getOnlinePlayers());
        viewers.add(Bukkit.getConsoleSender());
        AsyncChatEvent event = new AsyncChatEvent(true, player, viewers, ChatRenderer.defaultRenderer(),
            Component.text(text), Component.text(text), SignedMessage.system(text, Component.text(text)));
        CompletableFuture.runAsync(() -> server.getPluginManager().callEvent(event)).join();
        return event;
    }

    String line(AsyncChatEvent event, Audience viewer) {
        return plain(event.renderer().render(event.getPlayer(), event.getPlayer().displayName(), event.message(), viewer));
    }

    String next(PlayerMock player) {
        Component message = player.nextComponentMessage();
        return message == null ? null : plain(message);
    }

    @Test
    void eachPlayerGetsTheirGroupsFormat() {
        chatty(steve);
        AsyncChatEvent plainChat = chat(steve, "hello");
        assertEquals("Steve: hello", line(plainChat, alex));

        steve.addAttachment(plugin, "zchat.group.vip", true);
        steve.addAttachment(plugin, "zchat.group.admin", true);
        assertEquals("[Admin] Steve » hi", line(chat(steve, "hi"), alex), "the highest weight wins");
    }

    @Test
    void operatorsAreNotPutInGroupsByDefault() {
        steve.setOp(true);
        assertEquals("Steve: hi", line(chat(steve, "hi"), alex));
    }

    @Test
    void theLineIsDrawnOnceForEveryone() {
        AsyncChatEvent event = chat(steve, "hello");
        Component forAlex = event.renderer().render(steve, steve.displayName(), event.message(), alex);
        Component forConsole = event.renderer().render(steve, steve.displayName(), event.message(), Bukkit.getConsoleSender());
        assertTrue(event.renderer() instanceof ChatRenderer.ViewerUnaware, "a viewer-unaware renderer is drawn once");
        assertEquals(forAlex, forConsole);
    }

    @Test
    void playersCantInjectFormattingWithoutThePermission() {
        chatty(steve);
        AsyncChatEvent event = chat(steve, "<click:run_command:/op Steve><red>free</red>");
        assertEquals("Steve: <click:run_command:/op Steve><red>free</red>", line(event, alex));

        steve.addAttachment(plugin, "zchat.chat.color", true);
        Component colored = chat(steve, "<red>hi <click:run_command:/op Steve>x</click>").message();
        assertEquals("hi <click:run_command:/op Steve>x</click>", plain(colored), "colours work, clicks stay text");
        assertTrue(MiniMessage.miniMessage().serialize(colored).startsWith("<red>hi"), MiniMessage.miniMessage().serialize(colored));
    }

    @Test
    void theFilterCensors() {
        assertEquals("Steve: you ******** you", line(chat(steve, "you b4dw0rd1 you"), alex));
        assertEquals("Alex: ********", line(chat(alex, "badword2"), steve));
    }

    @Test
    void stylesCantHideAWordFromTheFilter() {
        chatty(steve);
        steve.addAttachment(plugin, "zchat.chat.color", true);
        assertEquals("Steve: ********", line(chat(steve, "bad<reset>word1"), alex));
        assertEquals("Steve: ********", line(chat(steve, "b<b></b>adword1"), alex));
    }

    @Test
    void aMessageAnotherPluginCancelsDoesntCountOrPing() {
        server.getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.NORMAL)
            void cancel(AsyncChatEvent event) {
                event.setCancelled(true);
            }
        }, plugin);
        assertTrue(chat(steve, "hey alex").isCancelled());
        org.bukkit.event.HandlerList.unregisterAll((org.bukkit.plugin.Plugin) plugin);
        plugin.reload();
        assertFalse(chat(steve, "hey alex").isCancelled(), "the cancelled message didn't start a cooldown");
    }

    @Test
    void hiddenChatIsOnlyKeptWhileTogglechatIsOn() throws Exception {
        alex.performCommand("togglechat");
        Path config = plugin.getDataFolder().toPath().resolve("config.yml");
        Files.writeString(config, Files.readString(config).replaceFirst("(?s)(togglechat:\n  enabled: )true", "$1false"));
        plugin.reload();
        assertTrue(chat(steve, "anyone?").viewers().contains(alex));
    }

    @Test
    void theCooldownHoldsPlayersBack() {
        assertFalse(chat(steve, "first").isCancelled());
        AsyncChatEvent second = chat(steve, "second");
        assertTrue(second.isCancelled());
        assertEquals("» Wait 3s before chatting again.", next(steve));
    }

    @Test
    void antiRepeatStopsTheSameMessageTwice() throws Exception {
        Path config = plugin.getDataFolder().toPath().resolve("config.yml");
        Files.writeString(config, Files.readString(config).replace("seconds: 3\n", "seconds: 0\n"));
        plugin.reload();
        assertFalse(chat(steve, "buy my stuff").isCancelled());
        assertTrue(chat(steve, "Buy  my stuff!!").isCancelled(), "a near copy is a repeat");
        assertEquals("» You just said that.", next(steve));
        assertFalse(chat(steve, "gg").isCancelled());
        assertFalse(chat(steve, "gg").isCancelled(), "short messages are let through");
    }

    @Test
    void mutedChatStopsAllButBypassers() {
        steve.setOp(true);
        steve.performCommand("mutechat");
        assertTrue(chat(alex, "hello?").isCancelled());
        assertFalse(chat(steve, "announcement").isCancelled(), "zchat.bypass.mute");
        steve.performCommand("mc");
        assertFalse(chat(alex, "hello again").isCancelled());
    }

    @Test
    void mentionsHighlightAndPing() {
        chatty(steve);
        AsyncChatEvent event = chat(steve, "hey alex, look");
        assertEquals("Steve: hey @Alex, look", line(event, alex));
        AsyncChatEvent self = chat(alex, "I am Alex");
        assertEquals("Alex: I am Alex", line(self, steve), "you can't mention yourself");
    }

    @Test
    void playersWhoHidChatDontSeeOthers() {
        alex.performCommand("togglechat");
        assertEquals("» Chat is now hidden. Run it again to show it.", next(alex));
        AsyncChatEvent fromSteve = chat(steve, "anyone?");
        assertFalse(fromSteve.viewers().contains(alex));
        assertTrue(fromSteve.viewers().contains(steve));
        assertTrue(chat(alex, "my own").viewers().contains(alex), "your own messages still show");
    }
}
