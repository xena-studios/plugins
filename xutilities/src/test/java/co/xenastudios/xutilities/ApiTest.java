package co.xenastudios.xutilities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.xenastudios.xutilities.api.XUtilitiesApi;
import co.xenastudios.xutilities.api.event.BackRecordEvent;
import co.xenastudios.xutilities.api.event.BackTeleportEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class ApiTest {

    ServerMock server;
    XUtilities plugin;
    PlayerMock player;
    XUtilitiesApi api;

    @BeforeEach
    void start() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(XUtilities.class);
        player = server.addPlayer("Steve");
        player.setOp(true);
        api = XUtilitiesApi.get();
    }

    @AfterEach
    void stop() {
        MockBukkit.unmock();
    }

    String next() {
        var message = player.nextComponentMessage();
        return message == null ? null : PlainTextComponentSerializer.plainText().serialize(message);
    }

    void setConfig(String from, String to) throws Exception {
        Path config = plugin.getDataFolder().toPath().resolve("config.yml");
        String text = Files.readString(config);
        String changed = text.replaceFirst(from, to);
        assertFalse(text.equals(changed), "the test's config edit matched nothing");
        Files.writeString(config, changed);
        plugin.reload();
    }

    @Test
    void registeredWhileEnabledAndThroughReloads() throws Exception {
        assertSame(api, server.getServicesManager().load(XUtilitiesApi.class));
        setConfig("(?s)(  fly:\\n    enabled: )true", "$1false");
        assertSame(api, XUtilitiesApi.get(), "the same API after a reload");

        server.getPluginManager().disablePlugin(plugin);
        assertNull(server.getServicesManager().load(XUtilitiesApi.class));
        assertThrows(IllegalStateException.class, XUtilitiesApi::get);
    }

    @Test
    void backLocationsCanBeReadSetAndCleared() {
        assertTrue(api.isBackEnabled());
        assertTrue(api.getBackLocation(player).isEmpty());

        Location start = player.getLocation().clone();
        player.teleport(start.clone().add(100, 0, 0));
        Location back = api.getBackLocation(player).orElseThrow();
        assertEquals(start.getX(), back.getX(), 0.001);
        back.add(50, 0, 0);
        assertEquals(start.getX(), api.getBackLocation(player).orElseThrow().getX(), 0.001, "a copy is handed out");

        Location spot = start.clone().add(0, 0, 30);
        assertTrue(api.setBackLocation(player, spot));
        spot.add(1, 0, 0);
        assertEquals(start.getX(), api.getBackLocation(player).orElseThrow().getX(), 0.001, "a copy is kept");
        assertEquals(start.getZ() + 30, api.getBackLocation(player).orElseThrow().getZ(), 0.001);
        player.performCommand("back");
        assertFalse("» You have nowhere to go back to.".equals(next()), "/back uses a spot set by the API");

        api.clearBackLocation(player);
        assertTrue(api.getBackLocation(player).isEmpty());
        player.performCommand("back");
        assertEquals("» You have nowhere to go back to.", next());
    }

    @Test
    void aSpotForSomeoneOfflineIsntKept() {
        Location spot = player.getLocation().clone();
        server.getPluginManager().callEvent(new PlayerQuitEvent(player, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));
        player.disconnect();
        assertFalse(api.setBackLocation(player, spot));
    }

    @Test
    void withBackOffNothingIsRemembered() throws Exception {
        setConfig("(?s)(  back:\\n    enabled: )true", "$1false");
        assertFalse(api.isBackEnabled());
        assertFalse(api.setBackLocation(player, player.getLocation()));
        player.teleport(player.getLocation().clone().add(100, 0, 0));
        assertTrue(api.getBackLocation(player).isEmpty());
        assertFalse(api.isCommandEnabled("back"));
    }

    @Test
    void cancellingBackRecordEventKeepsTheOldSpot() {
        Location start = player.getLocation().clone();
        player.teleport(start.clone().add(100, 0, 0));
        List<BackRecordEvent> seen = new ArrayList<>();
        listen(new Listener() {
            @EventHandler
            void on(BackRecordEvent event) {
                seen.add(event);
                event.setCancelled(true);
            }
        });
        player.teleport(start.clone().add(200, 0, 0));
        assertEquals(1, seen.size());
        assertEquals(BackRecordEvent.Reason.TELEPORT, seen.get(0).getReason());
        assertEquals(start.getX() + 100, seen.get(0).getLocation().getX(), 0.001);
        assertNotSame(seen.get(0).getLocation(), seen.get(0).getLocation());
        assertEquals(start.getX(), api.getBackLocation(player).orElseThrow().getX(), 0.001, "kept the first spot");
    }

    @Test
    void cancellingBackTeleportEventKeepsThePlayerWhereTheyAre() {
        Location start = player.getLocation().clone();
        player.teleport(start.clone().add(100, 0, 0));
        List<BackTeleportEvent> seen = new ArrayList<>();
        listen(new Listener() {
            @EventHandler
            void on(BackTeleportEvent event) {
                seen.add(event);
                event.setCancelled(true);
            }
        });
        player.performCommand("back");
        assertEquals(1, seen.size());
        assertEquals(start.getX(), seen.get(0).getDestination().getX(), 0.001);
        assertNull(next(), "nothing said: the listener tells the player why");
        assertEquals(start.getX() + 100, player.getLocation().getX(), 0.001);
        assertTrue(api.getBackLocation(player).isPresent(), "the spot is kept");
    }

    @Test
    void commandsAreKnownByNameAndAlias() throws Exception {
        assertTrue(api.isCommandEnabled("fly"));
        assertTrue(api.isCommandEnabled("/Flight"));
        assertTrue(api.isCommandEnabled("gmc"));
        assertTrue(api.isCommandEnabled("discord"), "link commands count");
        assertFalse(api.isCommandEnabled("tpa"));

        setConfig("(?s)(  fly:\\n    enabled: )true", "$1false");
        assertFalse(api.isCommandEnabled("fly"));
        assertFalse(api.isCommandEnabled("flight"));
    }

    void listen(Listener listener) {
        Plugin other = MockBukkit.createMockPlugin("Other");
        server.getPluginManager().registerEvents(listener, other);
    }
}
