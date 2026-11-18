package co.xenastudios.core.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatabaseTest {

    @Test
    void mysqlTimeoutsDefaultUnlessTheOwnerSetsThem() {
        Properties none = new Properties();
        Database.defaultTo(none, "", "connectTimeout", "10000");
        assertEquals("10000", none.getProperty("connectTimeout"));

        Properties owners = new Properties();
        Database.defaultTo(owners, "useSSL=false&ConnectTimeout=3000", "connectTimeout", "10000");
        assertEquals(null, owners.getProperty("connectTimeout"));

        Properties similar = new Properties();
        Database.defaultTo(similar, "connectTimeoutX=1", "connectTimeout", "10000");
        assertEquals("10000", similar.getProperty("connectTimeout"));
    }

    @TempDir
    Path folder;

    private Database sqlite(String prefix) throws DatabaseException {
        return Database.open(new StorageSettings(StorageSettings.Type.SQLITE, prefix, StorageSettings.defaults("").mysql()),
            folder.resolve("data/test.db"));
    }

    private static final SchemaStep ONE = SchemaStep.of(1, "things", "CREATE TABLE {p}thing (id {rowid}, name TEXT, data {blob})");
    private static final SchemaStep TWO = SchemaStep.of(2, "a column", "ALTER TABLE {p}thing ADD COLUMN size INTEGER");

    @Test
    void stepsRunOnceInOrderWithThePrefix() throws Exception {
        Database database = sqlite("t_");
        assertEquals(1, database.migrate(List.of(ONE)));
        assertEquals(2, database.migrate(List.of(ONE, TWO)), "only the new step runs");
        assertEquals(2, database.migrate(List.of(ONE, TWO)), "nothing runs twice");
        try (Connection connection = database.connect(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO t_thing (name, size) VALUES ('a', 1)");
            assertTrue(statement.executeQuery("SELECT 1 FROM t_meta WHERE name = 'schema' AND value = '2'").next());
        }
    }

    @Test
    void theFileIsSetUpForAWriterAndReaders() throws Exception {
        Database database = sqlite("");
        database.migrate(List.of(ONE));
        try (Connection connection = database.connect(); Statement statement = connection.createStatement()) {
            assertEquals("wal", single(statement, "PRAGMA journal_mode"));
            assertEquals("2", single(statement, "PRAGMA auto_vacuum"), "incremental");
            assertEquals("1", single(statement, "PRAGMA synchronous"), "normal");
        }
        assertEquals("the SQLite database test.db", database.description());
    }

    @Test
    void aStepThatFailsLeavesTheStepBefore() throws Exception {
        Database database = sqlite("");
        SchemaStep broken = SchemaStep.of(2, "broken", "CREATE TABLE half (id INTEGER)", "NOT SQL");
        DatabaseException failure = assertThrows(DatabaseException.class, () -> database.migrate(List.of(ONE, broken)));
        assertTrue(failure.getMessage().contains("schema 2 (broken)"), failure.getMessage());
        try (Connection connection = database.connect(); Statement statement = connection.createStatement()) {
            assertEquals("1", single(statement, "SELECT value FROM meta WHERE name = 'schema'"));
            assertFalse(statement.executeQuery("SELECT name FROM sqlite_master WHERE name = 'half'").next(), "rolled back");
        }
    }

    @Test
    void aNewerDatabaseIsRefusedNotChanged() throws Exception {
        sqlite("").migrate(List.of(ONE, TWO));
        DatabaseException failure = assertThrows(DatabaseException.class, () -> sqlite("").migrate(List.of(ONE)));
        assertTrue(failure.getMessage().contains("newer version"), failure.getMessage());
    }

    @Test
    void stepsMustBeNumberedInOrder() {
        assertThrows(IllegalArgumentException.class, () -> sqlite("").migrate(List.of(TWO)));
    }

    @Test
    void aPrefixIsPlainLettersOnly() {
        assertThrows(DatabaseException.class, () -> sqlite("x; DROP TABLE y"));
    }

    private static String single(Statement statement, String sql) throws SQLException {
        try (ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }
}
