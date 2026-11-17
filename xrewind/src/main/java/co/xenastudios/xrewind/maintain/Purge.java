package co.xenastudios.xrewind.maintain;

import co.xenastudios.core.db.Database;
import co.xenastudios.core.db.Dialect;
import co.xenastudios.xrewind.storage.Clock;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Deletes history older than a time, a few thousand rows per transaction with a pause
 * between, so the database is never locked for long and logging carries on alongside.
 * Off the server's threads only.
 */
public final class Purge {

    static final List<String> TABLES = List.of("block", "container", "entity", "text", "session");
    private static final int BATCH = 5_000;

    private Purge() {}

    /**
     * @param before delete rows from before this {@link Clock} time
     * @return how many rows were deleted
     */
    public static long run(Database database, int before) throws SQLException {
        long deleted = 0;
        try (Connection connection = database.connect()) {
            for (String table : TABLES) {
                String sql = database.dialect() == Dialect.MYSQL
                    ? database.sql("DELETE FROM {p}" + table + " WHERE time < ? LIMIT " + BATCH)
                    // SQLite has no DELETE ... LIMIT unless built with it.
                    : database.sql("DELETE FROM {p}" + table + " WHERE id IN (SELECT id FROM {p}" + table + " WHERE time < ? LIMIT " + BATCH + ")");
                try (PreparedStatement delete = connection.prepareStatement(sql)) {
                    delete.setInt(1, before);
                    int count;
                    do {
                        count = delete.executeUpdate();
                        deleted += count;
                        if (count == BATCH) {
                            // Let the writer in between batches.
                            try {
                                TimeUnit.MILLISECONDS.sleep(20);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return deleted;
                            }
                        }
                    } while (count == BATCH);
                }
            }
            if (database.dialect() == Dialect.SQLITE && deleted > 0) {
                // Hands the freed pages back to the disk, a little at a time.
                try (Statement statement = connection.createStatement()) {
                    statement.execute("PRAGMA incremental_vacuum");
                }
            }
        }
        return deleted;
    }

    /** {@link Clock} time {@code days} days ago. */
    public static int daysAgo(int days) {
        return Clock.now() - days * 86_400;
    }
}
