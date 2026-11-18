package co.xenastudios.xlimbo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.xenastudios.xlimbo.api.XLimboApi;
import co.xenastudios.xlimbo.api.event.PlayerEnterLimboEvent;
import co.xenastudios.xlimbo.api.event.PlayerReturnEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * The API on MockBukkit. MockBukkit can't make xLimbo's world, so players here aren't
 * "in limbo" and nobody is queued on their own; the world and the status ping are
 * covered on real servers by boot-checks.txt.
 */
class ApiTest {

    ServerMock server;
    XLimbo plugin;
    XLimboApi api;
    List<PlayerReturnEvent> returns = new ArrayList<>();
    List<PlayerEnterLimboEvent> arrivals = new ArrayList<>();
    boolean cancelReturns;

    @BeforeEach
    @SuppressWarnings("unchecked") // ServerMock's own getBanList, inherited by the subclass
    void start() {
        // MockBukkit doesn't implement isStopping, which disabling xLimbo asks.
        server = MockBukkit.mock(new ServerMock() {
            @Override
            public boolean isStopping() {
                return false;
            }
        });
        server.addSimpleWorld("world");
        plugin = MockBukkit.load(XLimbo.class);
        api = XLimboApi.get();
        server.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            void on(PlayerReturnEvent event) {
                returns.add(event);
                event.setCancelled(cancelReturns);
            }

            @EventHandler
            void on(PlayerEnterLimboEvent event) {
                arrivals.add(event);
            }
        }, MockBukkit.createMockPlugin("Other"));
    }

    @AfterEach
    void stop() {
        MockBukkit.unmock();
    }

    /** A player who joined, with their join messages read. */
    PlayerMock join(String name) {
        PlayerMock player = server.addPlayer(name);
        while (player.nextMessage() != null) {
            // The arrival messages.
        }
        return player;
    }

    void setConfig(String from, String to) throws Exception {
        Path config = plugin.getDataFolder().toPath().resolve("config.yml");
        String text = Files.readString(config);
        String changed = text.replace(from, to);
        assertFalse(text.equals(changed), "the test's config edit matched nothing");
        Files.writeString(config, changed);
        plugin.reload();
    }

    /** The queue on, through auto-join, with a delay long enough that nobody joins it on their own. */
    void queueOn() throws Exception {
        setConfig("auto-join:\n  # Only without server-check, which sends players back as soon as the main server is up.\n  enabled: false\n  # How long after arriving.\n  delay-seconds: 30",
            "auto-join:\n  enabled: true\n  delay-seconds: 3600");
        assertTrue(plugin.returns() != null, "the queue is running");
    }

    @Test
    void registeredWhileEnabled() {
        assertSame(api, server.getServicesManager().load(XLimboApi.class));
        server.getPluginManager().disablePlugin(plugin);
        assertNull(server.getServicesManager().load(XLimboApi.class));
        assertThrows(IllegalStateException.class, XLimboApi::get);
    }

    @Test
    void withoutServerCheckTheMainServersStatusIsUnknown() {
        assertFalse(api.isServerCheckEnabled());
        assertFalse(api.isMainServerUp());
        assertEquals(0, api.getQueueSize());
    }

    @Test
    void withTheQueueOffPlayersAreSentAtOnce() {
        PlayerMock steve = join("Steve");
        api.sendBack(steve);
        assertEquals(1, returns.size());
        assertEquals(PlayerReturnEvent.Cause.PLUGIN, returns.get(0).getCause());
        assertEquals("main", returns.get(0).getServer());
        assertEquals("» Sending you back to the server...",
            PlainTextComponentSerializer.plainText().serialize(steve.nextComponentMessage()));

        cancelReturns = true;
        api.sendBack(steve);
        assertEquals(2, returns.size());
        assertNull(steve.nextMessage(), "a cancelled return isn't sent");
    }

    @Test
    void withTheQueueOnPlayersWaitTheirTurn() throws Exception {
        queueOn();
        PlayerMock steve = join("Steve");
        PlayerMock alex = join("Alex");
        api.sendBack(steve);
        api.sendBack(alex);
        api.sendBack(steve);
        assertEquals(OptionalInt.of(1), api.getQueuePosition(steve));
        assertEquals(OptionalInt.of(2), api.getQueuePosition(alex));
        assertEquals(2, api.getQueueSize());
        assertTrue(returns.isEmpty(), "not sent yet");

        server.getScheduler().performTicks(21);
        assertEquals(2, returns.size());
        assertEquals(PlayerReturnEvent.Cause.QUEUE, returns.get(0).getCause());
        assertSame(steve, returns.get(0).getPlayer());
        assertEquals(0, api.getQueueSize());
        assertEquals(OptionalInt.empty(), api.getQueuePosition(steve));
    }

    @Test
    void sendBackFromAnotherThreadRunsOnTheMainThread() {
        PlayerMock steve = join("Steve");
        CompletableFuture.runAsync(() -> api.sendBack(steve)).join();
        assertTrue(returns.isEmpty());
        server.getScheduler().performTicks(1);
        assertEquals(1, returns.size());
    }

    @Test
    void arrivalsSayWhetherTheMainServerRefusedThem() throws Exception {
        queueOn();
        PlayerMock steve = join("Steve");
        assertEquals(PlayerEnterLimboEvent.Reason.UNKNOWN, arrivals.get(0).getReason());
        api.sendBack(steve);
        server.getScheduler().performTicks(21);
        assertEquals(1, returns.size());
        // Sent back, then here again moments later.
        steve.disconnect();
        steve.reconnect();
        assertEquals(PlayerEnterLimboEvent.Reason.REFUSED, arrivals.get(arrivals.size() - 1).getReason());
    }

    @Test
    void joinCommandAsksFirst() {
        PlayerMock steve = join("Steve");
        cancelReturns = true;
        steve.performCommand("join");
        assertEquals(1, returns.size());
        assertEquals(PlayerReturnEvent.Cause.JOIN_COMMAND, returns.get(0).getCause());
        assertNull(steve.nextMessage(), "nothing sent");
    }
}
