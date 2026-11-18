package co.xenastudios.xbans.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import co.xenastudios.xbans.api.Punishment;
import co.xenastudios.xbans.api.PunishmentType;
import co.xenastudios.xbans.api.Removal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The bans and mutes in memory: by account and IP, scoped, ending on their own. */
class ActiveTest {

    private static final long NOW = 1_800_000_000_000L;
    private final UUID steve = UUID.randomUUID();

    static Entry entry(long id, PunishmentType type, UUID target, String ip, Long expires, String scope) {
        return new Entry(new Punishment(id, type, target, "Steve", ip != null, "r", null, "Console",
            Instant.ofEpochMilli(NOW - 1000), expires == null ? null : Instant.ofEpochMilli(expires), scope, false, null, null), ip);
    }

    @Test
    void bansByAccountAndIp() {
        Active active = new Active();
        Entry account = entry(1, PunishmentType.BAN, steve, null, null, "*");
        Entry ip = entry(2, PunishmentType.BAN, null, "203.0.113.9", null, "*");
        active.add(account);
        active.add(ip);
        assertSame(account, active.ban(steve, null, "lobby", NOW));
        assertSame(ip, active.ban(UUID.randomUUID(), "203.0.113.9", "lobby", NOW));
        assertNull(active.ban(UUID.randomUUID(), "203.0.113.10", "lobby", NOW));
        assertNull(active.mute(steve, null, "lobby", NOW), "a ban isn't a mute");
    }

    @Test
    void endsOnItsOwn() {
        Active active = new Active();
        active.add(entry(1, PunishmentType.MUTE, steve, null, NOW + 5000, "*"));
        assertNotNull(active.mute(steve, null, "lobby", NOW));
        assertNull(active.mute(steve, null, "lobby", NOW + 5000));
        assertEquals(0, active.size(), "dropped once read after it ended");
    }

    @Test
    void scopedToOneServer() {
        Active active = new Active();
        active.add(entry(1, PunishmentType.BAN, steve, null, null, "survival"));
        assertNotNull(active.ban(steve, null, "survival", NOW));
        assertNotNull(active.ban(steve, null, "SURVIVAL", NOW));
        assertNull(active.ban(steve, null, "lobby", NOW));
        assertEquals(1, active.all(PunishmentType.BAN, null, NOW).size());
        assertEquals(0, active.all(PunishmentType.BAN, "lobby", NOW).size());
    }

    @Test
    void revokedOnesAreTakenOutAndNeverAdded() {
        Active active = new Active();
        Entry ban = entry(1, PunishmentType.BAN, steve, "203.0.113.9", null, "*");
        active.add(ban);
        assertSame(ban, active.remove(1));
        assertNull(active.ban(steve, "203.0.113.9", "lobby", NOW));
        Entry revoked = new Entry(ban.punishment().withRemoval(new Removal(null, "Console", Instant.now(), "")), null);
        active.add(revoked);
        assertEquals(0, active.size());
        active.add(entry(2, PunishmentType.WARN, steve, null, null, "*"));
        active.add(entry(3, PunishmentType.KICK, steve, null, null, "*"));
        assertEquals(0, active.size(), "warnings and kicks aren't kept in memory");
    }

    @Test
    void findsTheOneOnAScope() {
        Active active = new Active();
        Entry global = entry(1, PunishmentType.BAN, steve, null, NOW + 60_000, "*");
        Entry local = entry(2, PunishmentType.BAN, steve, null, null, "survival");
        active.add(global);
        active.add(local);
        assertEquals(List.of(global), active.onScope(PunishmentType.BAN, steve, null, "*", NOW));
        assertEquals(List.of(local), active.onScope(PunishmentType.BAN, steve, null, "survival", NOW));
        assertEquals(List.of(), active.onScope(PunishmentType.MUTE, steve, null, "*", NOW));
    }

    @Test
    void aReloadBringsMemoryInLineButKeepsWhatChangedMeanwhile() {
        Active active = new Active();
        Entry stale = entry(1, PunishmentType.BAN, steve, null, null, "*");
        Entry kept = entry(2, PunishmentType.BAN, UUID.randomUUID(), null, null, "*");
        active.add(stale);
        active.add(kept);
        Active.Sync sync = active.beginSync();
        // While the database is read: a ban arrives, and one is lifted.
        Entry arrived = entry(5, PunishmentType.BAN, UUID.randomUUID(), null, null, "*");
        active.add(arrived);
        Entry liftedMeanwhile = entry(4, PunishmentType.BAN, UUID.randomUUID(), null, null, "*");
        active.remove(liftedMeanwhile.id());
        Entry missed = entry(3, PunishmentType.MUTE, steve, null, null, "*");
        // What the read found: not the stale one (lifted elsewhere), the missed mute, and the
        // one lifted meanwhile (read before it was).
        List<Entry> added = sync.finish(List.of(kept, missed, liftedMeanwhile));
        assertEquals(List.of(missed), added);
        assertNull(active.ban(steve, null, "lobby", NOW), "dropped: the database doesn't have it");
        assertSame(missed, active.mute(steve, null, "lobby", NOW));
        assertNotNull(active.byId(arrived.id()), "added while reading: kept");
        assertNull(active.byId(liftedMeanwhile.id()), "lifted while reading: stays lifted");
        assertNotNull(active.byId(kept.id()));
    }

    @Test
    void listsDropWhatEnded() {
        Active active = new Active();
        active.add(entry(1, PunishmentType.BAN, steve, null, NOW - 1, "*"));
        active.add(entry(2, PunishmentType.BAN, UUID.randomUUID(), null, null, "*"));
        assertEquals(1, active.all(PunishmentType.BAN, null, NOW).size());
        assertEquals(1, active.size(), "the ended one is gone");
        assertEquals(List.of("Steve"), active.names(PunishmentType.BAN, NOW, 50));
    }

    // Runs two pieces of work at the same moment on two threads, many times over.
    private static void race(int rounds, java.util.function.IntFunction<Runnable[]> round) throws Exception {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < rounds; i++) {
                Runnable[] work = round.apply(i);
                java.util.concurrent.CyclicBarrier start = new java.util.concurrent.CyclicBarrier(2);
                java.util.concurrent.Future<?> a = pool.submit(() -> {
                    await(start);
                    work[0].run();
                });
                java.util.concurrent.Future<?> b = pool.submit(() -> {
                    await(start);
                    work[1].run();
                });
                a.get();
                b.get();
                work[2].run();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static void await(java.util.concurrent.CyclicBarrier barrier) {
        try {
            barrier.await();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void aBanAddedWhileAReloadFinishesCanStillBeLifted() throws Exception {
        // A ban made here while a reload that didn't see it finishes: it's either kept (made
        // meanwhile) or not, but never in the player's list and missing from the ids, where
        // an unban couldn't find it.
        race(5_000, i -> {
            Active active = new Active();
            Active.Sync sync = active.beginSync();
            List<Entry> bans = new java.util.ArrayList<>();
            for (int n = 1; n <= 50; n++) {
                bans.add(entry(n, PunishmentType.BAN, new UUID(i, n), null, null, "*"));
            }
            return new Runnable[] {
                () -> bans.forEach(active::add),
                () -> sync.finish(List.of()),
                () -> {
                    for (Entry ban : bans) {
                        active.remove(ban.id());
                        assertNull(active.ban(ban.punishment().target(), null, "lobby", NOW),
                            "round " + i + ": lifted, but still in force");
                    }
                }};
        });
    }

    @Test
    void aBanLiftedWhileAReloadFinishesStaysLifted() throws Exception {
        // The reload read the ban before it was lifted; the lift lands while it finishes.
        race(20_000, i -> {
            Active active = new Active();
            Active.Sync sync = active.beginSync();
            UUID player = new UUID(0, i + 1);
            Entry ban = entry(i + 1, PunishmentType.BAN, player, null, null, "*");
            return new Runnable[] {
                () -> active.remove(ban.id()),
                () -> sync.finish(List.of(ban)),
                () -> assertNull(active.ban(player, null, "lobby", NOW), "round " + i + ": the reload brought it back")};
        });
    }
}
