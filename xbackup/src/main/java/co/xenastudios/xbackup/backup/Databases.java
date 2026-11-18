package co.xenastudios.xbackup.backup;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/**
 * Copies a live SQLite database consistently. Copying the file's bytes while a plugin
 * writes to it can give a corrupt copy, so xBackup never does: it asks SQLite for a copy.
 */
public interface Databases {

    /** Writes a consistent copy of {@code database} to {@code target}, which must not exist. */
    void copy(Path database, Path target) throws IOException;

    /**
     * SQLite's own {@code VACUUM INTO}, through the SQLite driver Paper and Folia ship. It
     * reads one consistent moment of the database in a single read transaction: in WAL mode
     * the database's own plugin keeps writing meanwhile; in the older journal mode its writes
     * wait for the copy (up to the busy timeout), as with any reader.
     *
     * @return the copier, or null if this JVM has no SQLite driver
     */
    static Databases sqlite() {
        Driver driver;
        try {
            driver = (Driver) Class.forName("org.sqlite.JDBC").getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
        return (database, target) -> {
            Properties properties = new Properties();
            try (Connection connection = driver.connect("jdbc:sqlite:" + database.toAbsolutePath(), properties)) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("PRAGMA busy_timeout=15000");
                }
                try (PreparedStatement vacuum = connection.prepareStatement("VACUUM INTO ?")) {
                    vacuum.setString(1, target.toAbsolutePath().toString());
                    vacuum.execute();
                }
            } catch (SQLException e) {
                throw new IOException("SQLite couldn't copy it (" + e.getMessage() + ")", e);
            }
        };
    }
}
