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
import java.sql.Statement;
import java.time.DayOfWeek;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Totals from VotingPlugin and SuperbVote, in the formats their source writes: SuperbVote's
 * JsonVoteStorage and VotingPlugin's SQLite Users table (AdvancedCore keeps numbers as text).
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
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + folder.resolve("Users.db"));
             Statement statement = c.createStatement()) {
            statement.execute("CREATE TABLE Users (uuid VARCHAR(37) PRIMARY KEY, PlayerName TEXT, AllTimeTotal TEXT, MonthTotal TEXT, Points TEXT)");
            statement.execute("INSERT INTO Users VALUES ('" + STEVE + "', 'Steve', '40', '3', '100')");
            statement.execute("INSERT INTO Users VALUES ('" + ALEX + "', 'Alex', '', '0', '0')");
        }
        Imports.Found found = Imports.votingPlugin(plugins);
        assertEquals(List.of(new Imports.Imported(STEVE, "Steve", 40, 0)), found.players());
        assertTrue(found.from().endsWith("Users.db"));
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
