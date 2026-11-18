package co.xenastudios.xbans.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import co.xenastudios.xbans.api.Punishment;
import co.xenastudios.xbans.api.PunishmentType;
import co.xenastudios.xbans.api.Removal;
import java.time.Instant;
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
        assertSame(global, active.onScope(PunishmentType.BAN, steve, null, "*", NOW));
        assertSame(local, active.onScope(PunishmentType.BAN, steve, null, "survival", NOW));
        assertNull(active.onScope(PunishmentType.MUTE, steve, null, "*", NOW));
    }
}
