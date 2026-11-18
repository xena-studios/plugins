package co.xenastudios.xvote.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.xenastudios.core.db.StorageSettings;
import co.xenastudios.xvote.api.VoteSource;
import co.xenastudios.xvote.vote.Facts;
import co.xenastudios.xvote.vote.IncomingVote;
import co.xenastudios.xvote.vote.Periods;
import co.xenastudios.xvote.vote.Voter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.DayOfWeek;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/**
 * Totals from VotingPlugin and SuperbVote, in the formats their source writes: SuperbVote's
 * JsonVoteStorage and MysqlVoteStorage, and VotingPlugin's SQLite Users table (AdvancedCore
 * keeps numbers and lists as text).
 */
class ImportsTest {

    static final UUID STEVE = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
    static final UUID ALEX = UUID.fromString("853c80ef-3c37-49fd-aa49-938b674adae6");

    @TempDir
    Path plugins;

    @Test
    void superbVotesJson() throws Exception {
        Path folder = Files.createDirectories(plugins.resolve("SuperbVote"));
        Files.writeString(folder.resolve("votes.json"), "{\"version\":2,\"records\":{"
            + "\"" + STEVE + "\":{\"lastKnownUsername\":\"Steve\",\"votes\":12,\"lastVoted\":1763294400000},"
            + "\"" + ALEX + "\":{\"votes\":3,\"lastVoted\":1763294400001},"
            + "\"not-a-uuid\":{\"votes\":1}}}");
        Imports.Found found = Imports.superbVote(plugins);
        assertEquals(2, found.players().size());
        Imports.Imported steve = found.players().stream().filter(p -> p.id().equals(STEVE)).findFirst().orElseThrow();
        assertEquals("Steve", steve.name());
        assertEquals(12, steve.total());
        assertEquals(1763294400000L, steve.lastVote());
        // SuperbVote 0.x: a number per player.
        assertEquals(7, Imports.superbVoteJson("{\"" + STEVE + "\": 7}").get(0).total());
        assertThrows(IOException.class, () -> Imports.superbVoteJson("[]"));
    }

    @Test
    void votingPluginsSqlite() throws Exception {
        Path folder = Files.createDirectories(plugins.resolve("VotingPlugin"));
        // As AdvancedCore's SqliteUserBackend makes it: a column per key, numbers and lists as text,
        // LastVotes as site//millis entries joined with %line% (VotingPluginUser.setLastVotes).
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + folder.resolve("Users.db"));
             Statement statement = c.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS \"Users\" (\"uuid\" VARCHAR(37), \"PlayerName\" TEXT, \"AllTimeTotal\" TEXT,"
                + " \"LastVotes\" TEXT, \"MonthTotal\" TEXT, \"Points\" TEXT, PRIMARY KEY (\"uuid\"))");
        }
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + folder.resolve("Users.db"));
             PreparedStatement insert = c.prepareStatement("INSERT INTO \"Users\" VALUES (?, ?, ?, ?, ?, ?)")) {
            for (String[] row : new String[][] {
                {STEVE.toString(), "Steve", "40", "PlanetMinecraft//1763294400000%line%MinecraftMP//1763300000000%line%Broken//x", "3", "100"},
                {ALEX.toString(), "Alex", "", "", "0", "0"},
                {UUID.randomUUID().toString(), null, "2", null, "0", "0"}}) {
                for (int i = 0; i < row.length; i++) {
                    insert.setString(i + 1, row[i]);
                }
                insert.executeUpdate();
            }
        }
        Imports.Found found = Imports.votingPlugin(plugins);
        assertEquals(2, found.players().size());
        assertEquals(new Imports.Imported(STEVE, "Steve", 40, 1763300000000L), found.players().get(0));
        assertEquals(0, found.players().get(1).lastVote(), "no LastVotes yet");
        assertTrue(found.from().endsWith("Users.db"));
    }

    @Test
    void votingPluginsLastVotes() {
        assertEquals(0, Imports.lastVote(null));
        assertEquals(0, Imports.lastVote(""));
        assertEquals(5, Imports.lastVote("A//5"));
        assertEquals(9, Imports.lastVote("A//5%line%B//9%line%C//7"));
        assertEquals(5, Imports.lastVote("A//5%line%nothing%line%B//later"));
    }

    @Test
    void superbVotesMysqlSettingsAreReadAsItReadsThem() throws Exception {
        // SuperbVoteConfiguration's defaults: localhost:3306, database and table both superbvote.
        Imports.MysqlSource defaults = Imports.superbVoteMysqlSource(new YamlConfiguration());
        assertEquals("jdbc:mysql://localhost:3306/superbvote", defaults.url());
        assertEquals("superbvote", defaults.table());
        assertEquals("root", defaults.login().getProperty("user"));
        YamlConfiguration set = new YamlConfiguration();
        set.loadFromString("storage:\n  mysql:\n    host: db.example\n    port: 3307\n    database: votes\n    table: sv\n");
        Imports.MysqlSource source = Imports.superbVoteMysqlSource(set);
        assertEquals("jdbc:mysql://db.example:3307/votes", source.url());
        assertEquals("sv", source.table());
        set.set("storage.mysql.table", "sv; DROP TABLE x");
        assertThrows(IOException.class, () -> Imports.superbVoteMysqlSource(set));
    }

    /** SuperbVote's MySQL table as MysqlVoteStorage makes it, read back. Needs XR_MYSQL, as MySqlTest. */
    @Test
    @EnabledIfEnvironmentVariable(named = "XR_MYSQL", matches = ".+")
    void superbVotesMysqlTable() throws Exception {
        String[] parts = System.getenv("XR_MYSQL").split(":");
        String table = "sv" + Long.toHexString(System.nanoTime());
        Path folder = Files.createDirectories(plugins.resolve("SuperbVote"));
        Files.writeString(folder.resolve("config.yml"), "storage:\n  database: mysql\n  mysql:\n    host: " + parts[0]
            + "\n    port: " + parts[1] + "\n    database: " + parts[2] + "\n    username: " + parts[3]
            + "\n    password: '" + parts[4] + "'\n    table: " + table + "\n");
        String url = "jdbc:mysql://" + parts[0] + ":" + parts[1] + "/" + parts[2];
        try (Connection c = DriverManager.getConnection(url, parts[3], parts[4]); Statement statement = c.createStatement()) {
            statement.executeUpdate("CREATE TABLE " + table + " (uuid VARCHAR(36) PRIMARY KEY NOT NULL, last_name VARCHAR(16),"
                + " votes INT NOT NULL, last_vote TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)");
            try {
                statement.executeUpdate("INSERT INTO " + table + " VALUES ('" + STEVE + "', 'Steve', 12, '2026-11-16 12:00:00'),"
                    + " ('" + ALEX + "', NULL, 0, CURRENT_TIMESTAMP)");
                Imports.Found found = Imports.superbVote(plugins);
                assertEquals(1, found.players().size());
                assertEquals(STEVE, found.players().get(0).id());
                assertEquals(12, found.players().get(0).total());
                assertTrue(found.players().get(0).lastVote() > 0);
                assertTrue(found.from().contains(parts[1]), found.from());
            } finally {
                statement.executeUpdate("DROP TABLE " + table);
            }
        }
    }

    @Test
    void importedTotalsCarryOn() throws Exception {
        Store store = Store.open(StorageSettings.defaults("xvote_"), plugins, Logger.getLogger("test"));
        try {
            Ledger ledger = new Ledger(store.database());
            Periods periods = new Periods(ZoneOffset.UTC, DayOfWeek.MONDAY);
            long now = 1_763_294_400_000L;
            assertEquals(0L, (long) store.<Long>now(c -> ledger.imported(c, "superbvote")));
            store.now(c -> ledger.addImported(c, "superbvote", List.of(new Imports.Imported(STEVE, "Steve", 12, now - 86_400_000L)), periods, now));
            assertEquals(now, (long) store.<Long>now(c -> ledger.imported(c, "superbvote")));
            Voter steve = store.now(c -> ledger.voter(c, STEVE, periods, now, 0));
            assertEquals(12, steve.total());
            assertEquals(0, steve.today(periods, now));
            // The next vote is number 13, and starts a streak.
            IncomingVote vote = new IncomingVote("List", "Steve", "", "1", VoteSource.API, now);
            Facts facts = store.now(c -> ledger.count(c, ledger.insert(c, vote, STEVE, "Steve", null), STEVE, "Steve", "List", now, periods));
            assertEquals(13, facts.number());
            assertEquals(1, facts.streak());
            // Imported again on top: added, as the command warns.
            store.now(c -> ledger.addImported(c, "superbvote", List.of(new Imports.Imported(STEVE, "Steve", 12, 0)), periods, now));
            assertEquals(25, store.now(c -> ledger.voter(c, STEVE, periods, now, 0)).total());
            Map<UUID, Voter> all = store.now(c -> ledger.loadAll(c, periods, now, 0));
            assertEquals(1, all.size());
        } finally {
            store.stop(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void nothingToImport() {
        assertThrows(IOException.class, () -> Imports.superbVote(plugins));
        assertThrows(IOException.class, () -> Imports.votingPlugin(plugins));
    }
}
