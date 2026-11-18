package co.xenastudios.core.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The feed's logic, on SQLite: two "servers" sharing one file. Network mode is off on
 * SQLite in a real plugin; here it's switched on by hand. MySQL is in {@link ChangeFeedMySqlTest}.
 */
class ChangeFeedTest {

    @TempDir
    Path folder;

    Database database;
    final Logger logger = Logger.getLogger("feed-test");
    final List<String> heard = new ArrayList<>();

    @BeforeEach
    void open() throws Exception {
        database = Database.open(new StorageSettings(StorageSettings.Type.SQLITE, "t_", StorageSettings.defaults("").mysql()),
            folder.resolve("test.db"));
    }

    private ChangeFeed server(String origin) throws Exception {
        ChangeFeed feed = new ChangeFeed(database, origin, Duration.ofSeconds(1), logger, true);
        feed.prepare();
        return feed;
    }

    private void insert(long id, String origin, String payload, long created) throws Exception {
        try (Connection connection = database.connect(); PreparedStatement insert = connection.prepareStatement(
            "INSERT INTO t_changes (id, origin, topic, payload, created) VALUES (?, ?, 'ban', ?, ?)")) {
            insert.setLong(1, id);
            insert.setString(2, origin);
            insert.setString(3, payload);
            insert.setLong(4, created);
            insert.executeUpdate();
        }
    }

    @Test
    void otherServersHearAChangeAndItsServerDoesNot() throws Exception {
        ChangeFeed a = server("aaaa");
        ChangeFeed b = server("bbbb");
        List<String> heardByA = new ArrayList<>();
        a.subscribe("ban", change -> heardByA.add(change.payload()));
        b.subscribe("ban", change -> heard.add(change.topic() + ":" + change.payload()));
        b.subscribe("pay", change -> heard.add(change.topic() + ":" + change.payload()));

        try (Connection connection = database.connect()) {
            connection.setAutoCommit(false);
            a.publish(connection, "ban", "steve");
            connection.rollback();
            a.publish(connection, "ban", "alex");
            a.publish(connection, "pay", "alex:5");
            a.publish(connection, "other", "nobody listens");
            connection.commit();
        }
        long now = System.currentTimeMillis();
        b.poll(now);
        a.poll(now);
        assertEquals(List.of("ban:alex", "pay:alex:5"), heard, "the rolled-back change isn't heard");
        assertEquals(List.of(), heardByA, "a server ignores its own changes");

        b.poll(now);
        assertEquals(2, heard.size(), "nothing is heard twice");
        assertThrows(IllegalArgumentException.class, () -> a.publish("ban", "x".repeat(ChangeFeed.MAX_PAYLOAD + 1)));
    }

    @Test
    void changesFromBeforeAServerStartedArentDelivered() throws Exception {
        server("aaaa");
        insert(1, "aaaa", "old", System.currentTimeMillis());
        ChangeFeed b = server("bbbb");
        b.subscribe("ban", change -> heard.add(change.payload()));
        b.poll(System.currentTimeMillis());
        assertEquals(List.of(), heard);
        assertEquals(1, b.high());
    }

    @Test
    void aRowThatCommitsLateIsStillDelivered() throws Exception {
        ChangeFeed b = server("bbbb");
        b.subscribe("ban", change -> heard.add(change.payload()));
        long now = System.currentTimeMillis();
        // Id 2 was handed out but its transaction hasn't committed yet.
        insert(1, "aaaa", "one", now);
        insert(3, "aaaa", "three", now);
        b.poll(now);
        assertEquals(List.of("one", "three"), heard);
        assertEquals(1, b.waitingFor());

        insert(2, "aaaa", "two", now);
        b.poll(now + 1_000);
        assertEquals(List.of("one", "three", "two"), heard);
        assertEquals(0, b.waitingFor());

        // One that never commits (rolled back) is waited for only so long.
        insert(5, "aaaa", "five", now);
        b.poll(now + 2_000);
        assertEquals(1, b.waitingFor());
        b.poll(now + 2_000 + ChangeFeed.GAP_WAIT_MS + 1);
        assertEquals(0, b.waitingFor());
        insert(4, "aaaa", "too late", now);
        b.poll(now + 3_000 + ChangeFeed.GAP_WAIT_MS);
        assertEquals(List.of("one", "three", "two", "five"), heard);
    }

    @Test
    void aHugeJumpIsntWaitedOn() throws Exception {
        ChangeFeed b = server("bbbb");
        insert(ChangeFeed.MAX_GAP + 10, "aaaa", "far", System.currentTimeMillis());
        b.poll(System.currentTimeMillis());
        assertEquals(0, b.waitingFor());
        assertEquals(ChangeFeed.MAX_GAP + 10, b.high());
    }

    @Test
    void oldRowsArePrunedButTheNewestStays() throws Exception {
        ChangeFeed b = server("bbbb");
        long now = System.currentTimeMillis();
        long old = now - ChangeFeed.KEEP_MS - 1;
        insert(1, "aaaa", "a", old);
        insert(2, "aaaa", "b", old);
        insert(3, "aaaa", "c", old);
        insert(4, "aaaa", "d", now);
        b.poll(now);
        assertEquals(4, count(), "not pruned before a minute has passed");
        b.poll(now + ChangeFeed.PRUNE_EVERY_MS);
        assertEquals(1, count(), "everything older than ten minutes, below the newest");

        insert(5, "aaaa", "e", old);
        b.poll(now + ChangeFeed.KEEP_MS + ChangeFeed.PRUNE_EVERY_MS * 2);
        assertEquals(1, count(), "the newest row is kept even when it's old");
    }

    @Test
    void offOnSqliteAndWhenDisabled() throws Exception {
        List<String> warnings = new ArrayList<>();
        Logger captured = Logger.getAnonymousLogger();
        captured.setUseParentHandlers(false);
        captured.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                warnings.add(record.getMessage());
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        });
        ChangeFeed disabled = ChangeFeed.open(database, NetworkSettings.DEFAULTS, captured);
        assertFalse(disabled.on());
        assertEquals(List.of(), warnings);

        ChangeFeed sqlite = ChangeFeed.open(database, new NetworkSettings(true, 3), captured);
        assertFalse(sqlite.on());
        assertEquals(1, warnings.size());

        sqlite.publish("ban", "steve");
        try (Connection connection = database.connect(); Statement statement = connection.createStatement();
             ResultSet tables = statement.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE name = 't_changes'")) {
            tables.next();
            assertEquals(0, tables.getInt(1), "an off feed makes no table and writes nothing");
        }
    }

    private int count() throws Exception {
        try (Connection connection = database.connect(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM t_changes")) {
            result.next();
            return result.getInt(1);
        }
    }
}
