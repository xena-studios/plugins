package co.xenastudios.zchat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.xenastudios.zchat.api.MessageKind;
import co.xenastudios.zchat.api.MuteProvider;
import co.xenastudios.zchat.api.ZChatApi;
import co.xenastudios.zchat.api.event.ChatMessageEvent;
import co.xenastudios.zchat.api.event.ChatMessageSentEvent;
import co.xenastudios.zchat.api.event.FilterMatchEvent;
import co.xenastudios.zchat.api.event.MentionEvent;
import co.xenastudios.zchat.api.event.PrivateMessageEvent;
import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.chat.SignedMessage;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class ApiTest {

    ServerMock server;
    ZChat plugin;
    PlayerMock steve;
    PlayerMock alex;
    Plugin other;
    ZChatApi api;

    @BeforeEach
    void start() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(ZChat.class);
        steve = server.addPlayer("Steve");
        alex = server.addPlayer("Alex");
        for (PlayerMock player : List.of(steve, alex)) {
            player.addAttachment(plugin, "zchat.bypass.cooldown", true);
            player.addAttachment(plugin, "zchat.bypass.anti-repeat", true);
        }
        other = MockBukkit.createMockPlugin("Other");
        api = ZChatApi.get();
    }

    @AfterEach
    void stop() {
        MockBukkit.unmock();
    }

    static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    static String next(PlayerMock player) {
        Component message = player.nextComponentMessage();
        return message == null ? null : plain(message);
    }

    AsyncChatEvent chat(PlayerMock player, String text) {
        Set<Audience> viewers = new HashSet<>(Bukkit.getOnlinePlayers());
        AsyncChatEvent event = new AsyncChatEvent(true, player, viewers, ChatRenderer.defaultRenderer(),
            Component.text(text), Component.text(text), SignedMessage.system(text, Component.text(text)));
        CompletableFuture.runAsync(() -> server.getPluginManager().callEvent(event)).join();
        return event;
    }

    String line(AsyncChatEvent event) {
        return plain(event.renderer().render(event.getPlayer(), event.getPlayer().displayName(), event.message(), alex));
    }

    void setConfig(String from, String to) throws Exception {
        Path config = plugin.getDataFolder().toPath().resolve("config.yml");
        String text = Files.readString(config);
        String changed = text.replace(from, to);
        assertFalse(text.equals(changed), "the test's config edit matched nothing");
        Files.writeString(config, changed);
        plugin.reload();
    }

    void listen(Listener listener) {
        server.getPluginManager().registerEvents(listener, other);
    }

    @Test
    void registeredWhileEnabled() throws Exception {
        assertSame(api, server.getServicesManager().load(ZChatApi.class));
        plugin.reload();
        assertSame(api, ZChatApi.get(), "the same API after a reload");
        server.getPluginManager().disablePlugin(plugin);
        assertNull(server.getServicesManager().load(ZChatApi.class));
        assertThrows(IllegalStateException.class, ZChatApi::get);
    }

    @Test
    void aMuteProviderStopsChatAndPrivateMessages() {
        List<MessageKind> asked = new CopyOnWriteArrayList<>();
        MuteProvider mutes = (player, kind) -> {
            asked.add(kind);
            return player == steve ? Component.text("You're muted for 5 minutes.") : null;
        };
        api.registerMuteProvider(other, mutes);
        api.registerMuteProvider(other, mutes);

        assertTrue(chat(steve, "hello").isCancelled());
        assertEquals("You're muted for 5 minutes.", next(steve));
        assertEquals(List.of(MessageKind.CHAT), asked, "asked once, though registered twice");
        assertFalse(chat(alex, "hi").isCancelled());

        steve.performCommand("msg Alex psst");
        assertEquals("You're muted for 5 minutes.", next(steve));
        assertNull(next(alex));
        assertEquals(MessageKind.PRIVATE_MESSAGE, asked.get(asked.size() - 1));
        assertNull(next(steve), "told once");

        api.unregisterMuteProvider(mutes);
        assertFalse(chat(steve, "hello again").isCancelled());
    }

    @Test
    void aProviderOfADisabledPluginOrOneThatThrowsIsDropped() {
        api.registerMuteProvider(other, (player, kind) -> Component.text("muted"));
        server.getPluginManager().disablePlugin(other);
        assertFalse(chat(steve, "hello").isCancelled(), "a disabled plugin's provider isn't asked");

        Plugin broken = MockBukkit.createMockPlugin("Broken");
        api.registerMuteProvider(broken, (player, kind) -> {
            throw new IllegalStateException("oops");
        });
        assertFalse(chat(steve, "hello").isCancelled(), "a provider that throws doesn't break chat");
        assertFalse(chat(steve, "hello").isCancelled());
        assertEquals(0, plugin.mutes().size(), "and is dropped");
    }

    @Test
    void broadcastsAreInZChatsStyleAndSkipHiddenChat() {
        alex.performCommand("togglechat");
        next(alex);
        assertTrue(api.hasChatHidden(alex));
        assertFalse(api.hasChatHidden(steve));

        api.broadcast(Component.text("Vote for us!"));
        assertEquals("» Vote for us!", next(steve));
        assertNull(next(alex));

        assertFalse(api.send(alex, Component.text("hi")));
        assertNull(next(alex));
        assertTrue(api.send(steve, Component.text("hi")));
        assertEquals("» hi", next(steve));
    }

    @Test
    void stateIsReadFromMemory() {
        assertFalse(api.isChatMuted());
        steve.setOp(true);
        steve.performCommand("mutechat");
        assertTrue(api.isChatMuted());

        assertEquals(ZChatApi.DEFAULT_FORMAT, api.getFormatName(alex));
        alex.addAttachment(plugin, "zchat.group.vip", true);
        assertEquals("vip", api.getFormatName(alex));
        alex.addAttachment(plugin, "zchat.group.admin", true);
        assertEquals("admin", api.getFormatName(alex), "the highest weight wins");
    }

    @Test
    void messageEventCanCancelAndAddPlaceholders() throws Exception {
        setConfig("default-format: \"<gray><player>:</gray>", "default-format: \"<clan><gray><player>:</gray>");
        List<ChatMessageEvent> seen = new CopyOnWriteArrayList<>();
        listen(new Listener() {
            @EventHandler
            void on(ChatMessageEvent event) {
                seen.add(event);
                if (plain(event.getMessage()).contains("secret")) {
                    event.setCancelled(true);
                }
                event.addPlaceholder("clan", Component.text("[Red] "));
                assertThrows(IllegalArgumentException.class, () -> event.addPlaceholder("player", Component.text("x")));
                assertThrows(IllegalArgumentException.class, () -> event.addPlaceholder("Bad Name", Component.text("x")));
            }
        });

        assertEquals("[Red] Steve: hello", line(chat(steve, "hello")));
        assertTrue(seen.get(0).isAsynchronous());
        assertEquals(ZChatApi.DEFAULT_FORMAT, seen.get(0).getFormatName());
        assertTrue(chat(steve, "a secret").isCancelled());
    }

    @Test
    void sentEventFiresOnlyForMessagesThatGoOut() {
        List<ChatMessageSentEvent> sent = new CopyOnWriteArrayList<>();
        listen(new Listener() {
            @EventHandler
            void on(ChatMessageSentEvent event) {
                sent.add(event);
            }

            // Another plugin, after zChat's ChatMessageEvent.
            @EventHandler(priority = EventPriority.HIGH)
            void on(AsyncChatEvent event) {
                if (plain(event.message()).contains("secret")) {
                    event.setCancelled(true);
                }
            }
        });
        chat(steve, "hello");
        assertEquals(1, sent.size());
        assertSame(steve, sent.get(0).getPlayer());
        assertEquals("hello", plain(sent.get(0).getMessage()));
        assertTrue(sent.get(0).getViewers().contains(alex));
        assertTrue(sent.get(0).isAsynchronous());

        assertTrue(chat(steve, "a secret").isCancelled());
        steve.setOp(true);
        steve.performCommand("mutechat");
        assertTrue(chat(alex, "hi").isCancelled(), "zChat stops it");
        assertEquals(1, sent.size(), "nothing for a cancelled message");
    }

    @Test
    void filterEventSaysWhatMatched() {
        List<FilterMatchEvent> seen = new CopyOnWriteArrayList<>();
        listen(new Listener() {
            @EventHandler
            void on(FilterMatchEvent event) {
                seen.add(event);
            }
        });
        assertEquals("Steve: you ******** and ********", line(chat(steve, "you badword1 and b4dw0rd2")));
        assertEquals(1, seen.size());
        assertEquals(List.of("badword1", "b4dw0rd2"), seen.get(0).getMatches());
        assertEquals(MessageKind.CHAT, seen.get(0).getKind());
        assertFalse(seen.get(0).isBlocked());

        steve.performCommand("msg Alex badword2");
        assertEquals(MessageKind.PRIVATE_MESSAGE, seen.get(1).getKind());
        assertEquals(List.of("badword2"), seen.get(1).getMatches());
        assertFalse(seen.get(1).isAsynchronous(), "commands run on the main thread here");
    }

    @Test
    void privateMessageEventCanCancelAndChangeIt() {
        List<PrivateMessageEvent> seen = new ArrayList<>();
        listen(new Listener() {
            @EventHandler
            void on(PrivateMessageEvent event) {
                seen.add(event);
                if (plain(event.getMessage()).equals("stop")) {
                    event.setCancelled(true);
                } else {
                    event.setMessage(Component.text(plain(event.getMessage()).toUpperCase()));
                }
            }
        });
        steve.performCommand("msg Alex hello");
        assertEquals("You → Alex: HELLO", next(steve));
        assertEquals("Steve → You: HELLO", next(alex));
        assertFalse(seen.get(0).isReply());
        assertSame(alex, seen.get(0).getRecipient());

        alex.performCommand("r stop");
        assertTrue(seen.get(1).isReply());
        assertNull(next(alex));
        assertNull(next(steve));
    }

    @Test
    void mentionEventCanKeepAPlayerFromBeingPinged() {
        List<MentionEvent> seen = new ArrayList<>();
        listen(new Listener() {
            @EventHandler
            void on(MentionEvent event) {
                seen.add(event);
                event.setCancelled(true);
            }
        });
        chat(steve, "hey alex");
        server.getScheduler().performTicks(2);
        assertEquals(1, seen.size());
        assertSame(alex, seen.get(0).getPlayer());
        assertSame(steve, seen.get(0).getSender());
        assertFalse(seen.get(0).isEveryone());
        assertTrue(alex.getHeardSounds().isEmpty(), "no ping");
    }

    @Test
    void withoutAListenerAPingIsHeard() {
        chat(steve, "hey alex");
        server.getScheduler().performTicks(2);
        assertFalse(alex.getHeardSounds().isEmpty());
    }
}
