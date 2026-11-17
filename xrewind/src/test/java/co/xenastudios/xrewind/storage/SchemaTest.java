package co.xenastudios.xrewind.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;

import co.xenastudios.core.db.Database;
import co.xenastudios.core.db.StorageSettings;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SchemaTest {

    @TempDir
    Path folder;

    @Test
    void theTablesAreMadeAndTakeARow() throws Exception {
        Database database = Database.open(StorageSettings.defaults("xrewind_"), folder.resolve(Storage.FILE));
        assertEquals(Schema.STEPS.size(), database.migrate(Schema.STEPS));
        try (Connection connection = database.connect(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO xrewind_block (time, user, world, x, y, z, action, old_state, new_state)"
                + " VALUES (1, 1, 1, -30000000, -64, 30000000, 1, 0, 2)");
            try (ResultSet result = statement.executeQuery("SELECT id, rolled_back FROM xrewind_block")) {
                result.next();
                assertEquals(1, result.getLong(1), "numbered by SQLite's own row id");
                assertEquals(0, result.getInt(2));
            }
        }
    }

    @Test
    void timesFitFourBytesForDecades() {
        assertEquals(0, Clock.fromMillis(Clock.EPOCH * 1000));
        assertEquals(Clock.EPOCH * 1000 + 5000, Clock.toMillis(5));
        // 2094-01-01 still fits a signed INT.
        Clock.fromMillis(3_913_056_000_000L);
    }
}
