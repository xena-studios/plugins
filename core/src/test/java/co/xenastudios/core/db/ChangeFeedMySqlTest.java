package co.xenastudios.core.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * The change feed on MySQL or MariaDB, where network mode really runs. Only with
 * XR_MYSQL=host:port:database:user:password, as xRewind's MySqlTest.
 */
@EnabledIfEnvironmentVariable(named = "XR_MYSQL", matches = ".+")
class ChangeFeedMySqlTest {

    Database database;
    final Logger logger = Logger.getLogger("feed-test");

    @BeforeEach
    void open() throws Exception {
        String[] parts = System.getenv("XR_MYSQL").split(":");
        database = Database.open(new StorageSettings(StorageSettings.Type.MYSQL, "t" + Long.toHexString(System.nanoTime()) + "_",
            new StorageSettings.MySql(parts[0], Integer.parseInt(parts[1]), parts[2], parts[3], parts[4], "")), Path.of("unused"));
    }

    @AfterEach
    void drop() throws Exception {
        try (Connection connection = database.connect(); Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS " + database.table("changes"));
        }
    }

    @Test
    void serversShareChangesThroughOneDatabase() throws Exception {
        NetworkSettings on = new NetworkSettings(true, 1);
        ChangeFeed a = ChangeFeed.open(database, on, logger);
        ChangeFeed b = ChangeFeed.open(database, on, logger);
        assertTrue(a.on() && b.on());
        ChangeFeed.open(database, on, logger).close(); // A third start finds the table already there.

        List<String> heardByA = new ArrayList<>();
        List<String> heardByB = new ArrayList<>();
        a.subscribe("ban", change -> heardByA.add(change.payload()));
        b.subscribe("ban", change -> heardByB.add(change.payload()));

        try (Connection connection = database.connect()) {
            connection.setAutoCommit(false);
            a.publish(connection, "ban", "rolled back");
            connection.rollback();
            for (int i = 0; i < ChangeFeed.BATCH + 20; i++) {
                a.publish(connection, "ban", "a" + i);
            }
            connection.commit();
        }
        b.publish("ban", "from b ❤");

        long now = System.currentTimeMillis();
        b.check();
        assertEquals(ChangeFeed.BATCH, heardByB.size(), "a batch per check");
        b.check();
        assertEquals(ChangeFeed.BATCH + 20, heardByB.size());
        assertEquals("a0", heardByB.getFirst());
        assertEquals("a" + (ChangeFeed.BATCH + 19), heardByB.getLast());

        a.poll(now);
        a.poll(now);
        assertEquals(List.of("from b ❤"), heardByA, "a server hears only the others, once");
        a.close();
        b.close();
    }
}
