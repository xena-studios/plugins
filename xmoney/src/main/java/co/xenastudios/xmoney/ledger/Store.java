package co.xenastudios.xmoney.ledger;

import co.xenastudios.core.db.Database;
import co.xenastudios.core.db.DatabaseException;
import co.xenastudios.core.db.StorageSettings;
import co.xenastudios.xmoney.api.TransactionKind;
import co.xenastudios.xmoney.money.Format;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** xMoney's database: opening it, reading every account at startup, and reading history. */
public final class Store {

    /** The SQLite file, in the plugin's folder. */
    public static final String FILE = "money.db";

    private final Database database;

    private Store(Database database) {
        this.database = database;
    }

    /** Opens the database and brings its tables up to date. Blocks: call it while the server starts. */
    public static Store open(StorageSettings settings, Path folder) throws DatabaseException {
        Database database = Database.open(settings, folder.resolve(FILE));
        database.migrate(Schema.STEPS);
        return new Store(database);
    }

    public static Store of(Database database) throws DatabaseException {
        database.migrate(Schema.STEPS);
        return new Store(database);
    }

    public Database database() {
        return database;
    }

    public String description() {
        return database.description();
    }

    /** Reads every account and balance into memory. Blocks. */
    public void load(Accounts accounts) throws SQLException {
        Map<UUID, Long> balances = new HashMap<>();
        try (Connection connection = database.connect();
             PreparedStatement query = connection.prepareStatement(database.sql("SELECT uuid, balance FROM {p}balances WHERE currency = ?"))) {
            query.setString(1, Format.currencyId());
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    balances.put(UUID.fromString(result.getString(1)), result.getLong(2));
                }
            }
            try (PreparedStatement names = connection.prepareStatement(database.sql("SELECT uuid, name, player FROM {p}accounts"));
                 ResultSet result = names.executeQuery()) {
                while (result.next()) {
                    UUID id = UUID.fromString(result.getString(1));
                    Long balance = balances.remove(id);
                    accounts.load(id, result.getString(2), result.getInt(3) != 0, balance == null ? 0 : balance);
                }
            }
        }
        // A balance without a name, which the writer never leaves; kept rather than lost.
        balances.forEach((id, balance) -> accounts.load(id, id.toString().substring(0, 8), true, balance));
    }

    /** One row of a player's history. Amounts in units. */
    public record Row(long id, long time, long amount, long balance, TransactionKind kind, String source, String reason, UUID other) {}

    /** How many ledger rows a player has. Blocks: off the server's threads. */
    public long count(UUID player) throws SQLException {
        try (Connection connection = database.connect();
             PreparedStatement query = connection.prepareStatement(database.sql("SELECT COUNT(*) FROM {p}transactions WHERE uuid = ?"))) {
            query.setString(1, player.toString());
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? result.getLong(1) : 0;
            }
        }
    }

    /** A page of a player's history, newest first. Blocks: off the server's threads. */
    public List<Row> history(UUID player, long offset, int limit) throws SQLException {
        List<Row> rows = new ArrayList<>(limit);
        try (Connection connection = database.connect();
             PreparedStatement query = connection.prepareStatement(database.sql(
                 "SELECT id, time, amount, balance, kind, source, reason, other FROM {p}transactions WHERE uuid = ?"
                     + " ORDER BY id DESC LIMIT ? OFFSET ?"))) {
            query.setString(1, player.toString());
            query.setInt(2, limit);
            query.setLong(3, offset);
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    String other = result.getString(8);
                    rows.add(new Row(result.getLong(1), result.getLong(2), result.getLong(3), result.getLong(4),
                        kind(result.getString(5)), result.getString(6), result.getString(7), other == null ? null : UUID.fromString(other)));
                }
            }
        }
        return rows;
    }

    private static TransactionKind kind(String stored) {
        try {
            return TransactionKind.valueOf(stored);
        } catch (IllegalArgumentException e) {
            return TransactionKind.PLUGIN;
        }
    }
}
