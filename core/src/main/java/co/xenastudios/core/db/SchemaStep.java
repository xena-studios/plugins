package co.xenastudios.core.db;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * One change to a plugin's tables, numbered from 1. {@link Database#migrate} runs the
 * ones a database doesn't have yet, in order, each in its own transaction, so a step
 * that fails leaves the database as the step before left it (on SQLite; MySQL commits
 * each table change as it goes, so a step there should be one table change, or safe
 * to run again).
 */
public record SchemaStep(int version, String description, Work work) {

    @FunctionalInterface
    public interface Work {
        void run(Connection connection, Database database) throws SQLException;
    }

    // MySQL's "table already exists" (1050) and "duplicate key name" (1061).
    private static boolean alreadyThere(Database database, SQLException e) {
        return database.dialect() == Dialect.MYSQL && (e.getErrorCode() == 1050 || e.getErrorCode() == 1061);
    }

    /** A step that runs statements in order, with {@link Database#sql}'s placeholders filled in. */
    public static SchemaStep of(int version, String description, String... statements) {
        return new SchemaStep(version, description, (connection, database) -> {
            try (var statement = connection.createStatement()) {
                for (String sql : statements) {
                    try {
                        statement.execute(database.sql(sql));
                    } catch (SQLException e) {
                        // MySQL commits each table change as it goes, so a step that failed
                        // partway leaves some of its tables: running it again skips those.
                        if (!alreadyThere(database, e)) {
                            throw e;
                        }
                    }
                }
            }
        });
    }
}
