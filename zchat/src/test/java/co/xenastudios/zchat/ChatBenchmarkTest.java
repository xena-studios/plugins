package co.xenastudios.zchat;

import static org.junit.jupiter.api.Assertions.assertTrue;

import co.xenastudios.zchat.api.ZChatApi;
import co.xenastudios.zchat.api.event.ZChatMessageEvent;
import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.chat.SignedMessage;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * What one chat message costs zChat, from the chat event to the drawn line, with the
 * default config (filter, mentions, formats) and nothing else installed. Every message
 * runs this, so it mustn't creep up: about 14 µs on MockBukkit (an M-series Mac) when it
 * was written. The budget is far above that, so a slow CI machine doesn't fail it, but
 * a change that makes every message do something expensive does.
 */
class ChatBenchmarkTest {

    private static final double BUDGET_MICROS = 200;
    private static final String[] MESSAGES = {
        "hello everyone, how is it going today?",
        "has anyone seen Alex around spawn",
        "selling 64 diamonds, message me if you want them",
        "lol that was close",
    };

    ServerMock server;
    ZChat plugin;
    PlayerMock steve;
    Set<Audience> viewers;

    @BeforeEach
    void start() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(ZChat.class);
        steve = server.addPlayer("Steve");
        server.addPlayer("Alex");
        // Spam checks would stop most of the messages, which would make them look cheap.
        steve.addAttachment(plugin, "zchat.bypass.cooldown", true);
        steve.addAttachment(plugin, "zchat.bypass.anti-repeat", true);
        viewers = new HashSet<>(Bukkit.getOnlinePlayers());
    }

    @AfterEach
    void stop() {
        MockBukkit.unmock();
    }

    // One message: the event, as the server fires it, then the line drawn once.
    private void chat(String text) {
        Component message = Component.text(text);
        AsyncChatEvent event = new AsyncChatEvent(true, steve, new HashSet<>(viewers), ChatRenderer.defaultRenderer(),
            message, message, SignedMessage.system(text, message));
        server.getPluginManager().callEvent(event);
        event.renderer().render(steve, steve.displayName(), event.message(), steve);
    }

    private double microsPerMessage(int rounds) {
        long start = System.nanoTime();
        for (int i = 0; i < rounds; i++) {
            chat(MESSAGES[i % MESSAGES.length]);
            // MockBukkit keeps every event it fires in a copy-on-write list, so each one
            // costs more than the last; left alone, that list is most of what's timed.
            if (i % 100 == 99) {
                server.getPluginManager().clearEvents();
            }
        }
        return (System.nanoTime() - start) / 1000.0 / rounds;
    }

    private double time() {
        return CompletableFuture.supplyAsync(() -> {
            // Warm up, so the JIT has compiled the chat path before it's timed.
            microsPerMessage(20_000);
            return microsPerMessage(50_000);
        }).join();
    }

    @Test
    void aMessageStaysCheap() {
        double micros = time();
        System.out.printf("zChat: a chat message takes %.1f µs%n", micros);
        assertTrue(micros < BUDGET_MICROS, "a chat message took " + micros + " µs");
    }

    /** The same with plugins using the API: a mute provider and a ZChatMessageEvent listener adding a placeholder. */
    @Test
    void aMessageStaysCheapWithApiUsers() {
        Plugin other = MockBukkit.createMockPlugin("Other");
        ZChatApi.get().registerMuteProvider(other, (player, kind) -> null);
        server.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            void on(ZChatMessageEvent event) {
                event.addPlaceholder("clan", Component.text("[Red]"));
            }
        }, other);
        double micros = time();
        System.out.printf("zChat: a chat message with API users takes %.1f µs%n", micros);
        assertTrue(micros < BUDGET_MICROS, "a chat message took " + micros + " µs");
    }
}
