package co.xenastudios.xbans;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.xenastudios.core.db.NetworkSettings;
import co.xenastudios.core.db.StorageSettings;
import co.xenastudios.xbans.model.Entry;
import co.xenastudios.xbans.storage.Store;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/** Imports from the server's own lists and EssentialsX, and hashing IPs already stored. */
class ImportTest {

    @TempDir
    Path folder;

    ServerMock server;
    XBans plugin;

    @BeforeEach
    void start() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(XBans.class);
        plugin.store().ready().get(10, TimeUnit.SECONDS);
    }

    @AfterEach
    void stop() {
        MockBukkit.unmock();
    }

    private void await(BooleanSupplier condition) {
        long until = System.nanoTime() + 10_000_000_000L;
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < until, "timed out");
            server.getScheduler().performOneTick();
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        }
    }

    @Test
    void vanillaListsComeInOnceAndAreEnforced() throws Exception {
        UUID steve = UUID.randomUUID();
        Path root = folder.resolve("server");
        Files.createDirectories(root);
        plugin.imports().folders(root, root.resolve("plugins"));
        Files.writeString(root.resolve("banned-players.json"), """
            [{"uuid": "%s", "name": "Steve", "created": "2024-05-01 10:00:00 +0000", "source": "Notch",
              "expires": "forever", "reason": "Griefing"},
             {"uuid": "%s", "name": "Gone", "created": "2024-05-01 10:00:00 +0000", "source": "Server",
              "expires": "2024-05-02 10:00:00 +0000", "reason": "Over"}]
            """.formatted(steve, UUID.randomUUID()), StandardCharsets.UTF_8);
        Files.writeString(root.resolve("banned-ips.json"), """
            [{"ip": "198.51.100.7", "created": "2024-05-01 10:00:00 +0000", "source": "Server", "expires": "forever",
              "reason": "Alts"}, {"ip": "127.0.0.1", "created": "2024-05-01 10:00:00 +0000", "source": "Server",
              "expires": "forever", "reason": "Never this one"}]
            """, StandardCharsets.UTF_8);

        server.dispatchCommand(server.getConsoleSender(), "xbans import vanilla");
        Thread.sleep(200);
        server.getScheduler().performTicks(5);
        assertNull(plugin.store().backend().active().ban(steve, null, "server", System.currentTimeMillis()), "a dry run writes nothing");

        server.dispatchCommand(server.getConsoleSender(), "xbans import vanilla confirm");
        await(() -> plugin.store().backend().active().ban(steve, null, "server", System.currentTimeMillis()) != null);
        Entry ban = plugin.store().backend().active().ban(steve, null, "server", System.currentTimeMillis());
        assertEquals("Griefing", ban.punishment().reason());
        assertEquals("Notch", ban.punishment().staffName());
        assertEquals(1714557600000L, ban.punishment().created().toEpochMilli());
        await(() -> plugin.store().backend().active().ban(UUID.randomUUID(), "198.51.100.7", "server", System.currentTimeMillis()) != null);
        assertEquals(2, plugin.store().backend().active().size(), "the ended one and loopback are left out");

        // Again: nothing new.
        server.dispatchCommand(server.getConsoleSender(), "xbans import vanilla confirm");
        Thread.sleep(300);
        server.getScheduler().performTicks(5);
        long rows = plugin.store().read((c, b) -> {
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(b.database().sql("SELECT COUNT(*) FROM {p}punishments"))) {
                r.next();
                return r.getLong(1);
            }
        }).get(10, TimeUnit.SECONDS);
        assertEquals(2, rows);
    }

    @Test
    void essentialsMutesAndIps() throws Exception {
        UUID steve = UUID.randomUUID();
        Path root = folder.resolve("server");
        plugin.imports().folders(root, root.resolve("plugins"));
        Path userdata = root.resolve("plugins/Essentials/userdata");
        Files.createDirectories(userdata);
        Files.writeString(userdata.resolve(steve + ".yml"), """
            last-account-name: Steve
            ip-address: 203.0.113.9
            muted: true
            mute-reason: Shouting
            timestamps:
              mute: 0
              login: 1700000000000
            """);
        Files.writeString(userdata.resolve(UUID.randomUUID() + ".yml"), "last-account-name: Quiet\nmuted: false\n");
        server.dispatchCommand(server.getConsoleSender(), "xbans import essentials confirm");
        await(() -> plugin.store().backend().active().mute(steve, null, "server", System.currentTimeMillis()) != null);
        assertEquals("Shouting", plugin.store().backend().active().mute(steve, null, "server", System.currentTimeMillis())
            .punishment().reason());
        await(() -> !plugin.store().ipHistory(steve).join().isEmpty());
        assertEquals("203.0.113.9", plugin.store().ipHistory(steve).join().get(0).ip());
    }

    @Test
    void turningHashingOnHashesWhatsStored() throws Exception {
        Logger logger = Logger.getLogger("ImportTest");
        StorageSettings storage = StorageSettings.defaults("t_");
        Store raw = new Store(plugin.tasks(), logger, folder, backend -> {});
        raw.sync(new Store.Wanted(storage, NetworkSettings.DEFAULTS, false));
        raw.ready().get(10, TimeUnit.SECONDS);
        UUID steve = UUID.randomUUID();
        raw.seen(steve, "Steve", "203.0.113.9", 0, false);
        raw.write((c, b) -> null).get(10, TimeUnit.SECONDS);
        raw.close();

        Store hashed = new Store(plugin.tasks(), logger, folder, backend -> {});
        hashed.sync(new Store.Wanted(storage, NetworkSettings.DEFAULTS, true));
        hashed.ready().get(10, TimeUnit.SECONDS);
        String key = hashed.backend().keys().key("203.0.113.9");
        assertTrue(hashed.backend().keys().hashing());
        assertTrue(Files.exists(folder.resolve("ip-salt.txt")));
        List<String> stored = new ArrayList<>();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + folder.resolve("punishments.db"));
             Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT ip FROM t_ips")) {
            while (r.next()) {
                stored.add(r.getString(1));
            }
        }
        assertEquals(List.of(key), stored, "no address left in the database");
        hashed.close();

        // Turned off again: what's hashed stays hashed.
        Store again = new Store(plugin.tasks(), logger, folder, backend -> {});
        again.sync(new Store.Wanted(storage, NetworkSettings.DEFAULTS, false));
        again.ready().get(10, TimeUnit.SECONDS);
        assertTrue(again.backend().keys().hashing());
        assertNotNull(again.alts(steve, null).get(10, TimeUnit.SECONDS));
        again.close();
    }
}
