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
    final Faults faults = new Faults();
    /** Warnings and worse the writer logged. */
    final java.util.List<String> logged = new java.util.concurrent.CopyOnWriteArrayList<>();

    Bench(Database database, boolean shared) throws Exception {
        this(database, ChangeFeed.off(), shared);
    }

    Bench(Database database, ChangeFeed feed, boolean shared) throws Exception {
        this.database = database;
        this.store = Store.of(database);
        store.load(accounts);
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord record) {
                if (record.getLevel().intValue() >= java.util.logging.Level.WARNING.intValue()) {
                    logged.add(record.getMessage() + (record.getThrown() == null ? "" : " (" + record.getThrown() + ")"));
                }
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        });
        writer = new Writer(database, accounts, feed, shared, logger);
        writer.wrap = faults::wrap;
        writer.start();
        ledger = new Ledger(accounts, writer, shared, () -> MAX);
    }

    static Database sqlite(Path file) throws Exception {
        return Database.open(StorageSettings.defaults("xmoney_"), file);
    }

    void flush() {
        ledger.flush().orTimeout(30, TimeUnit.SECONDS).join();
    }

    /**
     * Holds the writer until the returned latch is counted down: everything queued after
     * this waits behind it, as behind a slow write.
     */
    java.util.concurrent.CountDownLatch hold() throws InterruptedException {
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch held = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CompletableFuture<Void> reached = new java.util.concurrent.CompletableFuture<>();
        // Registered before it's queued, so it runs on the writer's thread when the writer gets there.
        reached.thenRun(() -> {
            held.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        writer.add(new Op.Flush(reached));
        held.await();
        return release;
    }

    /** The ledger rows of one account, as kind and amount, oldest first. */
    static java.util.List<String> rows(Database database, UUID id) throws Exception {
        java.util.List<String> rows = new java.util.ArrayList<>();
        try (Connection connection = database.connect();
             java.sql.PreparedStatement query = connection.prepareStatement(
                 database.sql("SELECT kind, amount FROM {p}transactions WHERE uuid = ? ORDER BY id"))) {
            query.setString(1, id.toString());
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    rows.add(result.getString(1) + " " + result.getLong(2));
                }
            }
        }
        return rows;
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
