package co.xenastudios.xbans.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.xenastudios.xbans.api.Punishment;
import co.xenastudios.xbans.api.PunishmentType;
import co.xenastudios.xbans.model.Entry;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * LiteBans' tables, as its web interface reads them (litebans-php), mapped to xBans'
 * punishments. The same SQL runs on MySQL; SQLite stands in here.
 */
class LiteBansSourceTest {

    @TempDir
    Path folder;

    private static final UUID STEVE = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
    private static final UUID ALEX = UUID.fromString("853c80ef-3c37-49fd-aa49-938b674adae6");
    private static final UUID MOD = UUID.fromString("61699b2e-d327-4a01-9f1e-0ea8c3f06bc6");

    // LiteBans' punishment tables, as its web interface reads them. Fixed SQL; values go in as parameters.
    private static final String COLUMNS = "(id INTEGER PRIMARY KEY, uuid VARCHAR(36), ip VARCHAR(45), reason VARCHAR(2048),"
        + " banned_by_uuid VARCHAR(36), banned_by_name VARCHAR(128), removed_by_uuid VARCHAR(36),"
        + " removed_by_name VARCHAR(128), removed_by_reason VARCHAR(2048), removed_by_date TIMESTAMP,"
        + " time BIGINT, until BIGINT, template INT, server_scope VARCHAR(32), server_origin VARCHAR(32),"
        + " silent BIT, ipban BIT, ipban_wildcard BIT, active BIT";
    private static final String ROW = "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String WARNING_ROW = "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String HISTORY = "INSERT INTO litebans_history VALUES (?, ?, ?, ?, ?)";

    private static void insert(Connection c, String sql, Object... values) throws java.sql.SQLException {
        try (java.sql.PreparedStatement insert = c.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) {
                insert.setObject(i + 1, values[i]);
            }
            insert.executeUpdate();
        }
    }

    @Test
    void readsBansMutesWarningsKicksAndLogins() throws Exception {
        long now = System.currentTimeMillis();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + folder.resolve("litebans.db"))) {
            try (Statement s = c.createStatement()) {
                s.execute("CREATE TABLE litebans_bans " + COLUMNS + ")");
                s.execute("CREATE TABLE litebans_mutes " + COLUMNS + ")");
                s.execute("CREATE TABLE litebans_warnings " + COLUMNS + ", warned BIT)");
                s.execute("CREATE TABLE litebans_kicks " + COLUMNS + ")");
                s.execute("CREATE TABLE litebans_history (id INTEGER PRIMARY KEY, date TIMESTAMP, name VARCHAR(16),"
                    + " uuid VARCHAR(36), ip VARCHAR(45))");
            }
            // A permanent ban in force, by a moderator.
            insert(c, "INSERT INTO litebans_bans VALUES " + ROW, 1, STEVE.toString(), "203.0.113.9", "Hacking",
                MOD.toString(), "Mod", null, null, null, null, now - 1000, -1, 0, "*", "lobby", 0, 0, 0, 1);
            // A temporary IP ban that was lifted early.
            insert(c, "INSERT INTO litebans_bans VALUES " + ROW, 2, ALEX.toString(), "198.51.100.7", "Alts", "CONSOLE",
                "Console", MOD.toString(), "Mod", "Appealed", 1700000000000L, now - 5000, now + 86_400_000L,
                0, "survival", "survival", 1, 1, 0, 0);
            // A mute that ran out on its own.
            insert(c, "INSERT INTO litebans_mutes VALUES " + ROW, 1, STEVE.toString(), null, "Spam", "CONSOLE", "Console", null,
                "#expired", null, null, now - 100_000, now - 50_000, 0, "global", "lobby", 0, 0, 0, 0);
            insert(c, "INSERT INTO litebans_warnings VALUES " + WARNING_ROW, 1, STEVE.toString(), null, "Language", "CONSOLE",
                "Console", null, null, null, null, now - 1000, 0, 0, "*", "lobby", 0, 0, 0, 1, 1);
            insert(c, "INSERT INTO litebans_kicks VALUES " + ROW, 1, ALEX.toString(), null, "Calm down", "CONSOLE", "Console",
                null, null, null, null, now - 1000, 0, 0, "*", "lobby", 0, 0, 0, 0);
            insert(c, HISTORY, 1, 1600000000000L, "OldSteve", STEVE.toString(), "203.0.113.9");
            insert(c, HISTORY, 2, 1700000000000L, "Steve", STEVE.toString(), "203.0.113.9");
            insert(c, HISTORY, 3, 1700000000000L, "Alex", ALEX.toString(), "203.0.113.9");
            // A wildcard IP ban, which xBans can't match.
            insert(c, "INSERT INTO litebans_bans VALUES " + ROW, 3, null, "192.168.1.*", "Range", "CONSOLE", "Console", null,
                null, null, null, now - 1000, -1, 0, "*", "lobby", 0, 1, 1, 1);
            // This server's default-scope is local: LiteBans' global bans stay global all the same.
            Haul haul = LiteBansSource.read(c, "litebans_", "lobby");
            Map<String, Entry> rows = haul.rows.stream()
                .collect(Collectors.toMap(Haul.Row::key, Haul.Row::entry, (a, b) -> a, java.util.LinkedHashMap::new));
            assertEquals(5, rows.size());
            assertEquals(1, haul.wildcards(), "the wildcard ban is counted, not imported");
            assertEquals(Punishment.GLOBAL, rows.get("litebans:bans:1").punishment().scope(), "'*' is global");
            assertEquals(Punishment.GLOBAL, rows.get("litebans:warnings:1").punishment().scope());
            assertEquals(2, haul.count(PunishmentType.BAN));
            assertEquals(2, haul.players());

            Punishment ban = rows.get("litebans:bans:1").punishment();
            assertEquals(STEVE, ban.target());
            assertEquals("Steve", ban.targetName(), "the newest name from the logins");
            assertEquals(MOD, ban.staff());
            assertEquals("Mod", ban.staffName());
            assertTrue(ban.isPermanent());
            assertNull(ban.removal());
            assertTrue(rows.get("litebans:bans:1").active(now));
            assertFalse(ban.ipBased());
            assertNull(rows.get("litebans:bans:1").ip(), "the IP isn't kept with an account ban");

            Entry lifted = rows.get("litebans:bans:2");
            assertTrue(lifted.punishment().ipBased());
            assertEquals("198.51.100.7", lifted.ip());
            assertEquals("survival", lifted.punishment().scope());
            assertNull(lifted.punishment().staff(), "CONSOLE isn't a uuid");
            assertTrue(lifted.punishment().silent());
            assertNotNull(lifted.punishment().removal());
            assertEquals("Appealed", lifted.punishment().removal().reason());
            assertEquals(MOD, lifted.punishment().removal().staff());
            assertFalse(lifted.active(now), "lifted early, so not in force");

            Entry expired = rows.get("litebans:mutes:1");
            assertNull(expired.punishment().removal(), "it ran out; nobody revoked it");
            assertFalse(expired.active(now));
            assertEquals(Punishment.GLOBAL, expired.punishment().scope(), "'global' is too");

            assertTrue(rows.get("litebans:warnings:1").active(now));
            assertEquals(PunishmentType.KICK, rows.get("litebans:kicks:1").type());

            Map<UUID, Haul.Seen> seen = haul.seen.stream().collect(Collectors.toMap(Haul.Seen::id, Function.identity()));
            assertEquals("203.0.113.9", seen.get(STEVE).ip());
            assertEquals(1600000000000L, seen.get(STEVE).first());
            assertEquals(1700000000000L, seen.get(STEVE).last());
        }
    }

    @Test
    void aBigLoginHistoryReadsQuickly() throws Exception {
        int accounts = 40_000;
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + folder.resolve("big.db"))) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute("CREATE TABLE litebans_history (id INTEGER PRIMARY KEY, date BIGINT, name VARCHAR(16),"
                    + " uuid VARCHAR(36), ip VARCHAR(45))");
            }
            try (var insert = c.prepareStatement("INSERT INTO litebans_history (date, name, uuid, ip) VALUES (?, ?, ?, ?)")) {
                for (int i = 0; i < accounts; i++) {
                    insert.setLong(1, 1_700_000_000_000L + i);
                    insert.setString(2, "p" + i);
                    insert.setString(3, new UUID(7, i).toString());
                    // Half joined from an address, half from loopback (not kept).
                    insert.setString(4, i % 2 == 0 ? "203.0." + (i / 256 % 256) + "." + (i % 256) : "127.0.0.1");
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            c.commit();
            long started = System.nanoTime();
            Haul haul = LiteBansSource.read(c, "litebans_", "*");
            long millis = (System.nanoTime() - started) / 1_000_000;
            assertEquals(accounts, haul.players());
            assertEquals(accounts, haul.seen.size(), "one row each: an IP, or just the name");
            // Once it compared every name with every IP use: minutes at this size.
            assertTrue(millis < 5_000, "read in " + millis + " ms");
        }
    }

    @Test
    void missingTablesAreSkipped() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + folder.resolve("empty.db"))) {
            Haul haul = LiteBansSource.read(c, "litebans_", "*");
            assertTrue(haul.rows.isEmpty());
        }
    }
}
