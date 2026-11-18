package co.xenastudios.core.db;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Properties;

/**
 * A plugin's database: SQLite in its folder, or MySQL/MariaDB. Hands out connections,
 * each set up the same way, and brings the tables up to date with {@link #migrate}.
 *
 * <p>No driver is shaded in: Paper and Folia ship both SQLite's and MySQL's (MySQL's
 * also talks to MariaDB). Connections are opened on demand; a plugin that writes a lot
 * keeps one for its writer and opens others for reads.
 */
public final class Database {

    private static final String SQLITE_DRIVER = "org.sqlite.JDBC";
    private static final String MYSQL_DRIVER = "com.mysql.cj.jdbc.Driver";

    private final Dialect dialect;
    private final String url;
    private final Properties properties;
    private final String prefix;
    private final String description;

    private Database(Dialect dialect, String url, Properties properties, String prefix, String description) {
        this.dialect = dialect;
        this.url = url;
        this.properties = properties;
        this.prefix = prefix;
        this.description = description;
    }

    /**
     * Opens the database the settings describe, checking that it can be reached.
     *
     * @param sqliteFile where the SQLite file goes, for {@code type: sqlite}
     */
    public static Database open(StorageSettings settings, Path sqliteFile) throws DatabaseException {
        String prefix = settings.tablePrefix();
        if (!prefix.matches("[A-Za-z0-9_]*")) {
            throw new DatabaseException("storage.table-prefix can only use letters, digits and _.");
        }
        Database database = switch (settings.type()) {
            case SQLITE -> sqlite(sqliteFile, prefix);
            case MYSQL -> mysql(settings.mysql(), prefix);
        };
        try (Connection connection = database.connect()) {
            if (database.dialect == Dialect.SQLITE) {
                try (Statement statement = connection.createStatement()) {
                    // Lets purges give space back bit by bit. Only takes effect on a new file,
                    // and only before the switch to WAL writes its header.
                    statement.execute("PRAGMA auto_vacuum=INCREMENTAL");
                    // Readers don't wait for the writer, and the writer doesn't wait for readers.
                    statement.execute("PRAGMA journal_mode=WAL");
                }
            }
        } catch (SQLException e) {
            throw new DatabaseException("Couldn't connect to " + database.description + ": " + e.getMessage(), e);
        }
        return database;
    }

    private static Database sqlite(Path file, String prefix) throws DatabaseException {
        load(SQLITE_DRIVER, "SQLite");
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
        } catch (java.io.IOException e) {
            throw new DatabaseException("Couldn't make the folder for " + file + ": " + e.getMessage(), e);
        }
        return new Database(Dialect.SQLITE, "jdbc:sqlite:" + file.toAbsolutePath(), new Properties(), prefix,
            "the SQLite database " + file.getFileName());
    }

    private static Database mysql(StorageSettings.MySql settings, String prefix) throws DatabaseException {
        load(MYSQL_DRIVER, "MySQL");
        String url = "jdbc:mysql://" + settings.host() + ":" + settings.port() + "/" + settings.database()
            // Batched inserts as one statement: many times faster for a writer.
            + "?rewriteBatchedStatements=true&characterEncoding=utf8"
            + (settings.properties().isBlank() ? "" : "&" + settings.properties().strip());
        Properties properties = new Properties();
        properties.setProperty("user", settings.username());
        properties.setProperty("password", settings.password());
        // Without these, a connection whose packets are dropped (a firewall, a database host
        // that vanished) waits forever, and so does the plugin's database thread. Ten seconds
        // to connect; five minutes for one reply, since purges and imports run long queries.
        // An owner's own value in storage.mysql.properties wins.
        defaultTo(properties, settings.properties(), "connectTimeout", "10000");
        defaultTo(properties, settings.properties(), "socketTimeout", "300000");
        return new Database(Dialect.MYSQL, url, properties, prefix,
            "MySQL at " + settings.host() + ":" + settings.port() + "/" + settings.database());
    }

    /** Sets {@code key} unless the owner's extra connection properties already set it. */
    static void defaultTo(Properties properties, String ownerProperties, String key, String value) {
        for (String pair : ownerProperties.split("&")) {
            if (pair.strip().regionMatches(true, 0, key + "=", 0, key.length() + 1)) {
                return;
            }
        }
        properties.setProperty(key, value);
    }

    private static void load(String driver, String name) throws DatabaseException {
        try {
            Class.forName(driver);
        } catch (ClassNotFoundException e) {
            throw new DatabaseException("This server doesn't include the " + name + " driver; Paper and Folia do.", e);
        }
    }

    public Dialect dialect() {
        return dialect;
    }

    /** Where the data is, for the console: "the SQLite database xrewind.db", "MySQL at host:3306/db". */
    public String description() {
        return description;
    }

    /** A table's full name, with the prefix. */
    public String table(String name) {
        return prefix + name;
    }

    /**
     * The statement with its placeholders filled in: {@code {p}} the table prefix, and
     * {@code {rowid}}, {@code {blob}} and {@code {table}} (after a CREATE TABLE's columns) as
     * {@link Dialect} says.
     */
    public String sql(String statement) {
        return statement.replace("{p}", prefix).replace("{rowid}", dialect.rowId()).replace("{blob}", dialect.blob())
            .replace("{table}", dialect.tableOptions());
    }

    /** A new connection, which the caller closes. On SQLite, it waits up to 5 seconds for a lock. */
    public Connection connect() throws SQLException {
        Connection connection = DriverManager.getConnection(url, properties);
        if (dialect == Dialect.SQLITE) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA busy_timeout=5000");
                // Safe with WAL: a power cut can lose the last moments, never corrupt the file.
                statement.execute("PRAGMA synchronous=NORMAL");
            } catch (SQLException e) {
                connection.close();
                throw e;
            }
        }
        return connection;
    }

    /**
     * Brings the tables up to date: runs every step newer than the database's schema
     * version, in order, and records each. A database already newer than the last step
     * (made by a newer version of the plugin) is refused, never changed.
     *
     * @return the schema version the database is at now
     */
    public int migrate(List<SchemaStep> steps) throws DatabaseException {
        int latest = 0;
        for (SchemaStep step : steps) {
            if (step.version() != latest + 1) {
                throw new IllegalArgumentException("Schema steps must be numbered 1, 2, 3...; found " + step.version() + " after " + latest);
            }
            latest = step.version();
        }
        try (Connection connection = connect()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql("CREATE TABLE IF NOT EXISTS {p}meta (name VARCHAR(64) PRIMARY KEY, value VARCHAR(255) NOT NULL){table}"));
            }
            int current = schemaVersion(connection);
            if (current > latest) {
                throw new DatabaseException(description + " was made by a newer version (schema " + current
                    + ", this one knows up to " + latest + "). Update the plugin, or point it at another database.");
            }
            for (SchemaStep step : steps.subList(current, latest)) {
                connection.setAutoCommit(false);
                try {
                    step.work().run(connection, this);
                    setSchemaVersion(connection, step.version());
                    connection.commit();
                } catch (SQLException | RuntimeException e) {
                    connection.rollback();
                    throw new DatabaseException("Couldn't update " + description + " to schema " + step.version()
                        + " (" + step.description() + "): " + e.getMessage(), e);
                } finally {
                    connection.setAutoCommit(true);
                }
            }
            return latest;
        } catch (SQLException e) {
            throw new DatabaseException("Couldn't read " + description + ": " + e.getMessage(), e);
        }
    }

    private int schemaVersion(Connection connection) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(sql("SELECT value FROM {p}meta WHERE name = 'schema'"));
             ResultSet result = query.executeQuery()) {
            return result.next() ? Integer.parseInt(result.getString(1)) : 0;
        }
    }

    private void setSchemaVersion(Connection connection, int version) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(sql("UPDATE {p}meta SET value = ? WHERE name = 'schema'"))) {
            update.setString(1, Integer.toString(version));
            if (update.executeUpdate() > 0) {
                return;
            }
        }
        try (PreparedStatement insert = connection.prepareStatement(sql("INSERT INTO {p}meta (name, value) VALUES ('schema', ?)"))) {
            insert.setString(1, Integer.toString(version));
            insert.executeUpdate();
        }
    }
}
