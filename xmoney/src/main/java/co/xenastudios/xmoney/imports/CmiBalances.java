package co.xenastudios.xmoney.imports;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * CMI's balances, from its SQLite database ({@code plugins/CMI/cmi.sqlite.db}), opened
 * read-only. CMI's {@code users} table is found by its columns' names rather than their
 * places, so a CMI version that adds columns still reads; if the columns aren't there, the
 * message names the ones that are. CMI on MySQL isn't read directly: while CMI is still
 * installed, {@code /xmoney import vault} reads it through Vault instead.
 */
public final class CmiBalances implements Source {

    private static final List<String> UUID_COLUMNS = List.of("player_uuid", "uuid");
    private static final List<String> NAME_COLUMNS = List.of("username", "name", "player_name");
    private static final List<String> BALANCE_COLUMNS = List.of("balance", "money");

    private final Path file;
    private final Executor executor;

    public CmiBalances(Path plugins, Executor executor) {
        this.file = plugins.resolve("CMI").resolve("cmi.sqlite.db");
        this.executor = executor;
    }

    @Override
    public String name() {
        return "CMI";
    }

    @Override
    public CompletableFuture<List<Found>> read() {
        return CompletableFuture.supplyAsync(this::readNow, executor);
    }

    List<Found> readNow() {
        if (!Files.isRegularFile(file)) {
            throw new ImportException("there's no plugins/CMI/cmi.sqlite.db. If CMI keeps its data in MySQL, import with"
                + " /xmoney import vault while CMI is still installed.", true);
        }
        Properties properties = new Properties();
        properties.setProperty("open_mode", "1"); // Read-only: CMI's file is never changed.
        // CMI may be writing to it right now: wait for its lock a while rather than fail at once.
        properties.setProperty("busy_timeout", "5000");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath(), properties);
             Statement statement = connection.createStatement()) {
            Map<String, String> columns = new LinkedHashMap<>();
            try (ResultSet result = statement.executeQuery("PRAGMA table_info(users)")) {
                while (result.next()) {
                    String column = result.getString("name");
                    columns.put(column.toLowerCase(Locale.ROOT), column);
                }
            }
            if (columns.isEmpty()) {
                throw new ImportException("cmi.sqlite.db has no users table.", false);
            }
            String uuid = pick(columns, UUID_COLUMNS);
            String name = pick(columns, NAME_COLUMNS);
            String balance = pick(columns, BALANCE_COLUMNS);
            if (uuid == null || balance == null) {
                throw new ImportException("CMI's users table doesn't have the columns expected (a uuid and a balance). It has: "
                    + String.join(", ", columns.values()), false);
            }
            List<Found> found = new ArrayList<>();
            String query = "SELECT \"" + uuid + "\", " + (name == null ? "NULL" : "\"" + name + "\"") + ", CAST(\"" + balance
                + "\" AS TEXT) FROM users";
            try (ResultSet result = statement.executeQuery(query)) {
                while (result.next()) {
                    UUID id;
                    try {
                        id = UUID.fromString(result.getString(1));
                    } catch (IllegalArgumentException | NullPointerException e) {
                        continue;
                    }
                    String amount = result.getString(3);
                    found.add(new Found(id, result.getString(2), true, amount == null ? "0" : amount));
                }
            }
            return found;
        } catch (SQLException e) {
            throw new ImportException("couldn't read cmi.sqlite.db: " + e.getMessage(), false);
        }
    }

    private static String pick(Map<String, String> columns, List<String> wanted) {
        for (String column : wanted) {
            String real = columns.get(column);
            if (real != null) {
                return real;
            }
        }
        return null;
    }
}
