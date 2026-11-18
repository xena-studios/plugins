package co.xenastudios.xmoney.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;

import co.xenastudios.core.db.ChangeFeed;
import co.xenastudios.core.db.Database;
import co.xenastudios.core.db.StorageSettings;
import co.xenastudios.xmoney.api.TransactionKind;
import co.xenastudios.xmoney.money.Money;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/** One "server" for tests: accounts, a writer and a ledger on a database, with no Bukkit. */
public final class Bench implements AutoCloseable {

    static final long MAX = 1_000_000_000L * Money.ONE;
    static final Cause CAUSE = new Cause(TransactionKind.PLUGIN, "Test", "");

    final Database database;
    final Store store;
    final Accounts accounts = new Accounts();
    final Writer writer;
    final Ledger ledger;

    Bench(Database database, boolean shared) throws Exception {
        this(database, ChangeFeed.off(), shared);
    }

    Bench(Database database, ChangeFeed feed, boolean shared) throws Exception {
        this.database = database;
        this.store = Store.of(database);
        store.load(accounts);
        writer = new Writer(database, accounts, feed, shared, Logger.getLogger("test"));
        writer.start();
        ledger = new Ledger(accounts, writer, shared, () -> MAX);
    }

    static Database sqlite(Path file) throws Exception {
        return Database.open(StorageSettings.defaults("xmoney_"), file);
    }

    void flush() {
        ledger.flush().orTimeout(30, TimeUnit.SECONDS).join();
    }

    @Override
    public void close() {
        writer.stop(10, TimeUnit.SECONDS);
    }

    /** Every balance in the database. */
    static Map<UUID, Long> stored(Database database) throws Exception {
        Map<UUID, Long> balances = new HashMap<>();
        try (Connection connection = database.connect(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(database.sql("SELECT uuid, balance FROM {p}balances"))) {
            while (result.next()) {
                balances.put(UUID.fromString(result.getString(1)), result.getLong(2));
            }
        }
        return balances;
    }

    /** Every account's ledger rows added up. */
    static Map<UUID, Long> ledgerSums(Database database) throws Exception {
        Map<UUID, Long> sums = new HashMap<>();
        try (Connection connection = database.connect(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(database.sql("SELECT uuid, SUM(amount) FROM {p}transactions GROUP BY uuid"))) {
            while (result.next()) {
                sums.put(UUID.fromString(result.getString(1)), result.getLong(2));
            }
        }
        return sums;
    }

    /** The database agrees with itself: each balance is its ledger rows added up. */
    static void assertLedgerMatchesBalances(Database database) throws Exception {
        Map<UUID, Long> stored = stored(database);
        Map<UUID, Long> sums = ledgerSums(database);
        stored.values().removeIf(balance -> balance == 0);
        sums.values().removeIf(sum -> sum == 0);
        assertEquals(sums, stored, "balances in the database aren't their ledger rows added up");
    }
}
