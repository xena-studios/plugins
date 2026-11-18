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
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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
 * ban and mute in force is read again, so the change isn't lost.
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

    @Test
    void aReadThatFailsIsTriedAgain() {
        UUID steve = UUID.randomUUID();
        Entry made = madeElsewhere(steve);
        assertNull(banOf(steve));
        AtomicInteger tries = new AtomicInteger();
        Network network = new Network(plugin, (backend, id) -> {
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
        Network network = new Network(plugin, (backend, id) -> {
            tries.incrementAndGet();
            throw new SQLException("the database is gone");
        });
        network.handle(plugin.store().backend(), punished(made.id()), false);
        assertEquals(1 + Network.RETRY_MS.length, tries.get());
        await(() -> banOf(steve) != null);
        await(() -> banOf(alex) == null);
        await(() -> !online.isOnline());
    }
}
