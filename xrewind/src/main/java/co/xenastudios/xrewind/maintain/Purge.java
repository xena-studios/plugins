package co.xenastudios.xrewind.maintain;

import co.xenastudios.core.db.Database;
import co.xenastudios.core.db.Dialect;
import co.xenastudios.xrewind.log.WriteGate;
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
 * Each transaction, and the vacuum after, is a turn at the {@link WriteGate}, so a
 * backup's hold stops a purge between batches too. Off the server's threads only.
 */
public final class Purge {

    static final List<String> TABLES = List.of("block", "container", "entity", "text", "session");
    private static final int BATCH = 5_000;

    private Purge() {}

    /**
     * @param gate waited on before each transaction, so a backup's hold stops it too
     * @param before delete rows from before this {@link Clock} time
     * @return how many rows were deleted; fewer if the thread was interrupted
     */
    public static long run(Database database, WriteGate gate, int before) throws SQLException {
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
                        try {
                            gate.enter();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return deleted;
                        }
                        try {
                            count = delete.executeUpdate();
                        } finally {
                            gate.exit();
                        }
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
                try {
                    gate.enter();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return deleted;
                }
                try (Statement statement = connection.createStatement()) {
                    statement.execute("PRAGMA incremental_vacuum");
                } finally {
                    gate.exit();
                }
            }
        }
        return deleted;
    }

    /** {@link Clock} time {@code days} days ago. */
    public static int daysAgo(int days) {
        return secondsAgo(days * 86_400L);
    }

    /**
     * {@link Clock} time {@code seconds} ago, in long arithmetic and held at the earliest time
     * a row can have, so a huge age deletes nothing rather than wrapping round to now.
     */
    public static int secondsAgo(long seconds) {
        return (int) Math.max(Integer.MIN_VALUE, Clock.now() - seconds);
    }
}
