package co.xenastudios.xmoney.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.xenastudios.core.db.ChangeFeed;
import co.xenastudios.core.db.Database;
import co.xenastudios.core.db.NetworkSettings;
import co.xenastudios.core.db.StorageSettings;
import co.xenastudios.core.db.FeedChecks;
import co.xenastudios.xmoney.money.Money;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Two servers on one MySQL or MariaDB database, with the core's real change feed between
 * them. Runs only with XR_MYSQL=host:port:database:user:password, as CI does, or against a
 * throwaway container:
 * {@code docker run -d -p 3307:3306 -e MARIADB_ROOT_PASSWORD=pw -e MARIADB_DATABASE=xr mariadb}.
 */
@EnabledIfEnvironmentVariable(named = "XR_MYSQL", matches = ".+")
class MySqlTest {

    final List<ChangeFeed> feeds = new ArrayList<>();
    Bench lobby;
    Bench survival;

    Thread checker;

    @BeforeEach
    void start() throws Exception {
        String prefix = "m" + Long.toHexString(System.nanoTime()) + "_";
        lobby = server(prefix);
        survival = server(prefix);
        // What each feed's repeating task does on a server, every 200 ms.
        checker = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                feeds.forEach(FeedChecks::check);
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        checker.setDaemon(true);
        checker.start();
    }

    private static StorageSettings settings(String prefix, String properties) {
        String[] parts = System.getenv("XR_MYSQL").split(":");
        return new StorageSettings(StorageSettings.Type.MYSQL, prefix,
            new StorageSettings.MySql(parts[0], Integer.parseInt(parts[1]), parts[2], parts[3], parts[4], properties));
    }

    private Bench server(String prefix) throws Exception {
        // As on a server: the writer's connections are the short-waiting ones.
        Database database = Store.forWriter(settings(prefix, ""), Path.of("unused"), null);
        database.migrate(Schema.STEPS);
        ChangeFeed feed = ChangeFeed.open(database, new NetworkSettings(true, 1), Logger.getLogger("test"));
        Bench bench = new Bench(database, feed, true);
        bench.ledger.listen(feed);
        feeds.add(feed);
        return bench;
    }

    @AfterEach
    void stop() throws InterruptedException {
        checker.interrupt();
        checker.join();
        feeds.forEach(ChangeFeed::close);
        lobby.close();
        survival.close();
    }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out waiting: " + what);
            }
            Thread.sleep(50);
        }
    }

    @Test
    void aChangeOnOneServerReachesTheOther() throws Exception {
        UUID id = UUID.randomUUID();
        Account onLobby = lobby.ledger.open(id, "Steve", 100 * Money.ONE);
        lobby.flush();
        await(() -> survival.accounts.get(id) != null && survival.accounts.get(id).balance() == 100 * Money.ONE,
            "the other server to hear about the new account");
        Account onSurvival = survival.accounts.get(id);
        assertEquals("Steve", onSurvival.name());

        assertTrue(survival.ledger.withdraw(onSurvival, 30 * Money.ONE, Bench.CAUSE).get(30, TimeUnit.SECONDS).success());
        assertEquals(70 * Money.ONE, onSurvival.balance());
        await(() -> onLobby.balance() == 70 * Money.ONE, "the first server to hear about the withdrawal");

        lobby.ledger.deposit(onLobby, 5 * Money.ONE, Bench.CAUSE);
        await(() -> onSurvival.balance() == 75 * Money.ONE, "the second server to hear about the deposit");
        Bench.assertLedgerMatchesBalances(lobby.database);
    }

    @Test
    void twoServersCantSpendTheSameMoneyTwice() throws Exception {
        UUID id = UUID.randomUUID();
        long start = 500 * Money.ONE;
        long price = 9 * Money.ONE;
        Account onLobby = lobby.ledger.open(id, "Alex", start);
        lobby.flush();
        await(() -> survival.accounts.get(id) != null, "the other server to see the account");
        Account onSurvival = survival.accounts.get(id);
        AtomicInteger bought = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> work = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            Bench server = t % 2 == 0 ? lobby : survival;
            Account account = t % 2 == 0 ? onLobby : onSurvival;
            work.add(pool.submit(() -> {
                for (int i = 0; i < 20; i++) {
                    if (server.ledger.withdraw(account, price, Bench.CAUSE).orTimeout(30, TimeUnit.SECONDS).join().success()) {
                        bought.incrementAndGet();
                    }
                }
            }));
        }
        for (Future<?> future : work) {
            future.get(2, TimeUnit.MINUTES);
        }
        pool.shutdown();
        long stored = Bench.stored(lobby.database).get(id);
        assertEquals(start - bought.get() * price, stored);
        assertTrue(stored >= 0 && stored < price, "the money should run out, and never past zero: " + stored);
        Bench.assertLedgerMatchesBalances(lobby.database);
        await(() -> onLobby.balance() == stored && onSurvival.balance() == stored, "both servers to agree with the database");
    }

    @Test
    void twoServersFirstDepositsToAnAccountBothLand() throws Exception {
        for (int round = 0; round < 20; round++) {
            UUID id = UUID.randomUUID();
            Account onLobby = lobby.ledger.account(id, "Town" + round, false);
            Account onSurvival = survival.ledger.account(id, "Town" + round, false);
            java.util.concurrent.CountDownLatch lobbyHeld = lobby.hold();
            java.util.concurrent.CountDownLatch survivalHeld = survival.hold();
            lobby.ledger.deposit(onLobby, 3 * Money.ONE, Bench.CAUSE);
            survival.ledger.deposit(onSurvival, 4 * Money.ONE, Bench.CAUSE);
            lobbyHeld.countDown();
            survivalHeld.countDown();
            lobby.flush();
            survival.flush();
            assertEquals(7 * Money.ONE, Bench.stored(lobby.database).get(id));
        }
        assertTrue(lobby.logged.isEmpty() && survival.logged.isEmpty(),
            "a first deposit on both at once made a write fail: " + lobby.logged + survival.logged);
        Bench.assertLedgerMatchesBalances(lobby.database);
    }

    @Test
    void theWritersConnectionsWaitAtMostThreeSecondsWhateverTheOwnerSet() throws Exception {
        Database writes = Store.forWriter(settings("unused_", "connectTimeout=600000&socketTimeout=600000"), Path.of("unused"), null);
        try (Connection connection = writes.connect()) {
            assertEquals(Writer.NETWORK_TIMEOUT_MS, connection.getNetworkTimeout(), "the handshake waits 3 s at most too");
        }
    }

    @Test
    void theLostCommitCheckWaitsForACommitStillInProgress() throws Exception {
        Database database = lobby.database;
        for (boolean lands : new boolean[] {true, false}) {
            long commitId = new java.security.SecureRandom().nextLong() & Long.MAX_VALUE;
            try (Connection slow = database.connect(); Connection asking = database.connect()) {
                slow.setAutoCommit(false);
                asking.setAutoCommit(false);
                try (PreparedStatement insert = slow.prepareStatement(database.sql("INSERT INTO {p}transactions"
                    + " (time, uuid, currency, amount, balance, kind, source, reason, other, commit_id) VALUES (?, ?, ?, 0, 0, ?, ?, ?, NULL, ?)"))) {
                    insert.setLong(1, System.currentTimeMillis());
                    insert.setString(2, UUID.randomUUID().toString());
                    insert.setString(3, "default");
                    insert.setString(4, "PLUGIN");
                    insert.setString(5, "Test");
                    insert.setString(6, "");
                    insert.setLong(7, commitId);
                    insert.executeUpdate();
                }
                // A commit the database is still making: the writer's check must wait for it.
                CompletableFuture<Boolean> seen = CompletableFuture.supplyAsync(() -> {
                    try {
                        return lobby.writer.committed(asking, commitId);
                    } catch (SQLException e) {
                        throw new CompletionException(e);
                    }
                });
                Thread.sleep(500);
                assertFalse(seen.isDone(), "answered before the commit in progress landed or rolled back");
                if (lands) {
                    slow.commit();
                } else {
                    slow.rollback();
                }
                assertEquals(lands, seen.get(10, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void anImportOnOneServerReachesTheOtherInOneResync() throws Exception {
        List<Ledger.Target> targets = new ArrayList<>();
        for (int i = 0; i < Writer.IMPORT_CHUNK * 2 + 5; i++) {
            targets.add(new Ledger.Target(lobby.ledger.account(UUID.randomUUID(), "I" + i, true), (i + 1) * Money.ONE));
        }
        int imported = lobby.ledger.setAll(targets, new Cause(co.xenastudios.xmoney.api.TransactionKind.IMPORT, "Test", ""))
            .get(2, TimeUnit.MINUTES);
        assertEquals(targets.size(), imported);
        await(() -> targets.stream().allMatch(target -> survival.accounts.get(target.account().id()) != null
            && survival.accounts.get(target.account().id()).balance() == target.balance()), "the other server to read the import");
        Bench.assertLedgerMatchesBalances(lobby.database);
    }
}
