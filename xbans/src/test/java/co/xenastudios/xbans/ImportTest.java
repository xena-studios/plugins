package co.xenastudios.xbans;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.xenastudios.core.db.NetworkSettings;
import co.xenastudios.core.db.StorageSettings;
import co.xenastudios.xbans.api.Punishment;
import co.xenastudios.xbans.api.PunishmentType;
import co.xenastudios.xbans.ip.IpKeys;
import co.xenastudios.xbans.model.Entry;
import co.xenastudios.xbans.storage.Store;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
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
import org.mockbukkit.mockbukkit.entity.PlayerMock;

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

        PlayerMock online = new PlayerMock(server, "Steve", steve);
        online.setAddress(new java.net.InetSocketAddress(java.net.InetAddress.getByName("203.0.113.9"), 25565));
        server.addPlayer(online);
        server.dispatchCommand(server.getConsoleSender(), "xbans import vanilla confirm");
        await(() -> plugin.store().backend().active().ban(steve, null, "server", System.currentTimeMillis()) != null);
        await(() -> !online.isOnline());
        Entry ban = plugin.store().backend().active().ban(steve, null, "server", System.currentTimeMillis());
        assertEquals("Griefing", ban.punishment().reason());
        assertEquals("Notch", ban.punishment().staffName());
        assertEquals(1714557600000L, ban.punishment().created().toEpochMilli());
        await(() -> plugin.store().backend().active().ban(UUID.randomUUID(), "198.51.100.7", "server", System.currentTimeMillis()) != null);
        assertEquals("198.51.x.x", plugin.store().backend().active().ban(UUID.randomUUID(), "198.51.100.7", "server",
            System.currentTimeMillis()).punishment().targetName(), "named without the whole address");
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
        raw.insert(new Entry(new Punishment(0, PunishmentType.BAN, null, IpKeys.label("198.51.100.7"), true, "alts", null,
            "Console", Instant.now(), null, "*", false, null, null), "198.51.100.7"), List.of(), null).get(10, TimeUnit.SECONDS);
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
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + folder.resolve("punishments.db"));
             Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT name, ip FROM t_punishments")) {
            assertTrue(r.next());
            String banKey = hashed.backend().keys().key("198.51.100.7");
            assertEquals(banKey, r.getString(2));
            assertEquals(IpKeys.shown(banKey), r.getString(1), "the IP ban's name is the start of its hash, not 198.51.x.x");
        }
        hashed.close();

        // Turned off again: what's hashed stays hashed.
        Store again = new Store(plugin.tasks(), logger, folder, backend -> {});
        again.sync(new Store.Wanted(storage, NetworkSettings.DEFAULTS, false));
        again.ready().get(10, TimeUnit.SECONDS);
        assertTrue(again.backend().keys().hashing());
        assertNotNull(again.alts(steve, null).get(10, TimeUnit.SECONDS));
        again.close();
    }

    @Test
    void aHashedDatabaseWontOpenWithoutItsSalt() throws Exception {
        List<String> said = new java.util.concurrent.CopyOnWriteArrayList<>();
        Logger logger = Logger.getLogger("SaltTest");
        logger.setUseParentHandlers(false);
        logger.addHandler(new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord record) {
                said.add(record.getMessage());
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        });
        StorageSettings storage = StorageSettings.defaults("t_");
        Store first = new Store(plugin.tasks(), logger, folder, backend -> {});
        first.sync(new Store.Wanted(storage, NetworkSettings.DEFAULTS, true));
        first.ready().get(10, TimeUnit.SECONDS);
        first.close();
        Path salt = folder.resolve(IpKeys.SALT_FILE);
        String right = Files.readString(salt);

        // Missing: no new salt is made, and it doesn't open, so IP-banned players can't get in.
        Files.delete(salt);
        Store missing = new Store(plugin.tasks(), logger, folder, backend -> {});
        missing.sync(new Store.Wanted(storage, NetworkSettings.DEFAULTS, true));
        await(() -> said.stream().anyMatch(line -> line.contains("ip-salt.txt is missing")));
        assertNull(missing.backend());
        assertFalse(Files.exists(salt), "no new salt");
        close(missing);

        // Another salt: the same.
        Files.writeString(salt, "ab".repeat(32) + "\n");
        Store wrong = new Store(plugin.tasks(), logger, folder, backend -> {});
        wrong.sync(new Store.Wanted(storage, NetworkSettings.DEFAULTS, false));
        await(() -> said.stream().anyMatch(line -> line.contains("isn't the one this database's IPs were hashed with")));
        assertNull(wrong.backend());
        close(wrong);

        Files.writeString(salt, right);
        Store back = new Store(plugin.tasks(), logger, folder, backend -> {});
        back.sync(new Store.Wanted(storage, NetworkSettings.DEFAULTS, true));
        back.ready().get(10, TimeUnit.SECONDS);
        assertTrue(back.backend().keys().hashing());
        back.close();
    }

    // The mock can't cancel the retry, a repeating task (a real server can).
    private static void close(Store store) {
        try {
            store.close();
        } catch (RuntimeException ignored) {
            // MockBukkit's "not implemented".
        }
    }
}
