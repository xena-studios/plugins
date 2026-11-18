package co.xenastudios.xbans.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.xenastudios.core.db.ChangeFeed;
import co.xenastudios.xbans.XBans;
import co.xenastudios.xbans.api.Punishment;
import co.xenastudios.xbans.api.PunishmentType;
import co.xenastudios.xbans.model.Entry;
import co.xenastudios.xbans.storage.Store;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * A change from another server whose punishment can't be read: tried again, then every
 * ban and mute in force is read again, so the change isn't lost. That reload is tried again
 * until it works, and never runs more than once at a time.
 */
class NetworkTest {

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

    // A ban another server made: in the shared database, not yet in this server's memory.
    private Entry madeElsewhere(UUID target) {
        Punishment p = new Punishment(0, PunishmentType.BAN, target, "Steve", false, "elsewhere", null, "Console",
            Instant.now(), null, "*", false, null, null);
        return plugin.store().insert(new Entry(p, null), List.of(), null).join().entry();
    }

    private Entry banOf(UUID id) {
        return plugin.store().backend().active().ban(id, null, "server", System.currentTimeMillis());
    }

    private static ChangeFeed.Change punished(long id) {
        return new ChangeFeed.Change(1, Store.TOPIC_PUNISH, Long.toString(id), System.currentTimeMillis());
    }

    private static ChangeFeed.Change revoked(long id) {
        return new ChangeFeed.Change(1, Store.TOPIC_REVOKE, Long.toString(id), System.currentTimeMillis());
    }

    private Network network(Network.Reader reader) {
        return network(reader, () -> plugin.store().reloadInForce());
    }

    private Network network(Network.Reader reader, Network.Reloader reloader) {
        return new Network(plugin, reader, reloader, Duration.ofMillis(100));
    }

    private static final Network.Reader DOWN = (backend, id) -> {
        throw new SQLException("the database is gone");
    };

    @Test
    void aReadThatFailsIsTriedAgain() {
        UUID steve = UUID.randomUUID();
        Entry made = madeElsewhere(steve);
        assertNull(banOf(steve));
        AtomicInteger tries = new AtomicInteger();
        Network network = network((backend, id) -> {
            if (tries.incrementAndGet() == 1) {
                throw new SQLException("the connection dropped");
            }
            try (Connection c = backend.database().connect()) {
                return Store.findNow(c, backend, id);
            }
        });
        network.handle(plugin.store().backend(), punished(made.id()), false);
        assertEquals(2, tries.get());
        assertNotNull(banOf(steve));
    }

    @Test
    void aReadThatKeepsFailingCatchesUpOnEverything() throws Exception {
        UUID steve = UUID.randomUUID();
        PlayerMock online = new PlayerMock(server, "Steve", steve);
        online.setAddress(new java.net.InetSocketAddress(java.net.InetAddress.getByName("203.0.113.9"), 25565));
        server.addPlayer(online);
        Entry made = madeElsewhere(steve);
        // And one this server holds that was lifted elsewhere, which it never heard of either.
        UUID alex = UUID.randomUUID();
        Entry lifted = madeElsewhere(alex);
        plugin.store().backend().active().add(lifted);
        plugin.store().revoke(lifted.id(), new co.xenastudios.xbans.api.Removal(null, "Console", Instant.now(), ""), false)
            .get(10, TimeUnit.SECONDS);
        assertNotNull(banOf(alex));

        AtomicInteger tries = new AtomicInteger();
        Network network = network((backend, id) -> {
            tries.incrementAndGet();
            throw new SQLException("the database is gone");
        });
        network.handle(plugin.store().backend(), punished(made.id()), false);
        assertEquals(1 + Network.RETRY_MS.length, tries.get());
        await(() -> banOf(steve) != null);
        await(() -> banOf(alex) == null);
        await(() -> !online.isOnline());
    }

    @Test
    void aReloadThatFailsIsTriedAgainUntilItWorks() {
        UUID steve = UUID.randomUUID();
        Entry made = madeElsewhere(steve);
        AtomicInteger reloads = new AtomicInteger();
        Network network = network(DOWN, () -> reloads.incrementAndGet() <= 2
            ? CompletableFuture.failedFuture(new SQLException("still gone")) : plugin.store().reloadInForce());
        network.handle(plugin.store().backend(), punished(made.id()), false);
        await(() -> banOf(steve) != null);
        assertEquals(3, reloads.get(), "failed twice, then worked");
        settle();
        assertEquals(3, reloads.get(), "and nothing after it worked");
    }

    @Test
    void aBurstOfChangesWhileTheDatabaseIsDownStartsOneReload() {
        UUID steve = UUID.randomUUID();
        Entry made = madeElsewhere(steve);
        UUID alex = UUID.randomUUID();
        Entry lifted = madeElsewhere(alex);
        plugin.store().backend().active().add(lifted);
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger reloads = new AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean up = new java.util.concurrent.atomic.AtomicBoolean();
        Network network = network((backend, id) -> {
            reads.incrementAndGet();
            throw new SQLException("the database is gone");
        }, () -> {
            reloads.incrementAndGet();
            return up.get() ? plugin.store().reloadInForce() : CompletableFuture.failedFuture(new SQLException("gone"));
        });
        long started = System.nanoTime();
        network.handle(plugin.store().backend(), punished(made.id()), false);
        for (int i = 0; i < 500; i++) {
            network.handle(plugin.store().backend(), punished(made.id() + 1000 + i), false);
        }
        // A revocation is felt at once all the same.
        network.handle(plugin.store().backend(), revoked(lifted.id()), true);
        assertNull(banOf(alex));
        long took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertTrue(took < 3_000, "the feed's thread was held up for " + took + "ms");
        assertEquals(1 + Network.RETRY_MS.length, reads.get(), "only the first change was tried again");
        assertEquals(1, reloads.get(), "one reload, not one per change");

        up.set(true);
        await(() -> banOf(steve) != null);
        int after = reloads.get();
        settle();
        assertEquals(after, reloads.get(), "it stops trying once it worked");
    }

    @Test
    void reloadsAskedForWhileOneRunsRunOnceAfterIt() {
        List<CompletableFuture<List<Entry>>> running = new java.util.concurrent.CopyOnWriteArrayList<>();
        Network network = network(DOWN, () -> {
            CompletableFuture<List<Entry>> reload = new CompletableFuture<>();
            running.add(reload);
            return reload;
        });
        Store.Backend backend = plugin.store().backend();
        for (int i = 0; i < 100; i++) {
            network.resync(backend);
        }
        assertEquals(1, running.size());
        running.get(0).complete(List.of());
        assertEquals(2, running.size(), "the hundred asked for meanwhile are one more");
        running.get(1).complete(List.of());
        settle();
        assertEquals(2, running.size());
    }

    // Lets delayed tries come due.
    private void settle() {
        for (int i = 0; i < 20; i++) {
            server.getScheduler().performOneTick();
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        }
    }
}
