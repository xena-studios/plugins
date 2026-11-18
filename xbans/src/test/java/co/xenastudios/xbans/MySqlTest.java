package co.xenastudios.xbans;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.xenastudios.core.db.ChangeFeed;
import co.xenastudios.core.db.NetworkSettings;
import co.xenastudios.core.db.StorageSettings;
import co.xenastudios.xbans.api.Punishment;
import co.xenastudios.xbans.api.PunishmentType;
import co.xenastudios.xbans.api.Removal;
import co.xenastudios.xbans.model.Entry;
import co.xenastudios.xbans.storage.Store;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

/**
 * The same SQL on MySQL or MariaDB, and two servers sharing bans through network mode.
 * Runs only with XR_MYSQL=host:port:database:user:password, such as against a throwaway
 * container: {@code docker run -d -p 3307:3306 -e MARIADB_ROOT_PASSWORD=pw -e MARIADB_DATABASE=xr mariadb}.
 */
@EnabledIfEnvironmentVariable(named = "XR_MYSQL", matches = ".+")
class MySqlTest {

    @TempDir
    Path folder;

    XBans plugin;
    StorageSettings storage;
    final Logger logger = Logger.getLogger("test");

    @BeforeEach
    void start() {
        MockBukkit.mock();
        plugin = MockBukkit.load(XBans.class);
        String[] parts = System.getenv("XR_MYSQL").split(":");
        String prefix = "b" + Long.toHexString(System.nanoTime()) + "_";
        storage = new StorageSettings(StorageSettings.Type.MYSQL, prefix,
            new StorageSettings.MySql(parts[0], Integer.parseInt(parts[1]), parts[2], parts[3], parts[4], ""));
    }

    @AfterEach
    void stop() {
        MockBukkit.unmock();
    }

    private Store open(List<ChangeFeed.Change> heard) throws Exception {
        Store store = new Store(plugin.tasks(), logger, folder, backend -> {
            backend.feed().subscribe(Store.TOPIC_PUNISH, heard::add);
            backend.feed().subscribe(Store.TOPIC_REVOKE, heard::add);
        });
        store.sync(new Store.Wanted(storage, new NetworkSettings(true, 1), false));
        store.ready().get(30, TimeUnit.SECONDS);
        return store;
    }

    private static Entry draft(PunishmentType type, UUID target, String reason) {
        return new Entry(new Punishment(0, type, target, "Steve", false, reason, null, "Console", Instant.now(), null, "*",
            false, null, null), null);
    }

    @Test
    void writesPagesAndTheNetwork() throws Exception {
        List<ChangeFeed.Change> heardByA = new CopyOnWriteArrayList<>();
        List<ChangeFeed.Change> heardByB = new CopyOnWriteArrayList<>();
        Store a = open(heardByA);
        Store b = open(heardByB);
        assertTrue(a.backend().feed().on());
        UUID steve = UUID.randomUUID();

        Entry ban = a.insert(draft(PunishmentType.BAN, steve, "hello ünïcode 🙂"), null, null, () -> {}).get(10, TimeUnit.SECONDS);
        long deadline = System.currentTimeMillis() + 15_000;
        while (heardByB.isEmpty() && System.currentTimeMillis() < deadline) {
            // The mock's async scheduler runs repeating tasks as it ticks.
            MockBukkit.getMock().getScheduler().performTicks(20);
            Thread.sleep(100);
        }
        assertEquals(List.of(Long.toString(ban.id())), heardByB.stream().map(ChangeFeed.Change::payload).toList(),
            "the other server hears of it");
        assertTrue(heardByA.isEmpty(), "a server doesn't hear its own changes");
        assertEquals("hello ünïcode 🙂", b.find(ban.id()).get(10, TimeUnit.SECONDS).punishment().reason());

        Removal removal = new Removal(null, "Console", Instant.now(), "sorry");
        assertTrue(b.revoke(ban.id(), removal, true).get(10, TimeUnit.SECONDS));
        assertFalse(a.revoke(ban.id(), removal, false).get(10, TimeUnit.SECONDS), "revoked once only");
        deadline = System.currentTimeMillis() + 15_000;
        while (heardByA.isEmpty() && System.currentTimeMillis() < deadline) {
            // The mock's async scheduler runs repeating tasks as it ticks.
            MockBukkit.getMock().getScheduler().performTicks(20);
            Thread.sleep(100);
        }
        assertEquals(ban.id() + Store.SILENT, heardByA.get(0).payload());

        for (int i = 0; i < 25; i++) {
            a.insert(draft(PunishmentType.KICK, steve, "kick " + i), null, null, () -> {}).get(10, TimeUnit.SECONDS);
        }
        Store.Filter filter = Store.Filter.target(steve, null);
        long total = a.read((c, backend) -> Store.count(c, backend, filter)).get(10, TimeUnit.SECONDS);
        assertEquals(26, total);
        List<Entry> third = a.read((c, backend) -> Store.page(c, backend, filter, Store.boundary(c, backend, filter, 20), 10))
            .get(10, TimeUnit.SECONDS);
        assertEquals(6, third.size());
        assertNotNull(third.get(5).punishment().removal(), "the ban, oldest, with its removal");

        a.seen(steve, "Steve", "203.0.113.9", 10, false);
        UUID alex = UUID.randomUUID();
        a.seen(alex, "Alex", "203.0.113.9", 0, false);
        a.write((c, backend) -> null).get(10, TimeUnit.SECONDS);
        assertEquals(List.of(alex), a.alts(steve, null).get(10, TimeUnit.SECONDS).stream().map(Store.IpUse::id).toList());
        assertEquals(10, a.known(steve).get(10, TimeUnit.SECONDS).weight());
        assertEquals(26, a.prune(steve, 0, false).get(10, TimeUnit.SECONDS));
        close(a);
        close(b);
    }

    // The mock can't cancel a repeating task (a real server can), which is the feed's last step in closing.
    private static void close(Store store) {
        try {
            store.close();
        } catch (RuntimeException ignored) {
            // MockBukkit's "not implemented".
        }
    }
}
