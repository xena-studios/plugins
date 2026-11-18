package co.xenastudios.xmoney.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.xenastudios.xmoney.api.TransactionResult.Status;
import co.xenastudios.xmoney.money.Money;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Two servers sharing one database (network mode), here two ledgers on one SQLite file with
 * the change feed stood in for by {@code refresh}: withdrawals are decided by the database, so
 * spending the same money on both at once can't overspend it, and each server's memory comes
 * back to the database's balance.
 */
class SharedTest {

    @TempDir
    Path folder;
    Bench lobby;
    Bench survival;

    @BeforeEach
    void start() throws Exception {
        Path file = folder.resolve("money.db");
        lobby = new Bench(Bench.sqlite(file), true);
        survival = new Bench(Bench.sqlite(file), true);
    }

    @AfterEach
    void stop() {
        lobby.close();
        survival.close();
    }

    @Test
    void onlyTheFirstServerGivesTheStartingBalance() throws Exception {
        UUID id = UUID.randomUUID();
        lobby.ledger.open(id, "Steve", 100 * Money.ONE);
        lobby.flush();
        Account there = survival.ledger.open(id, "Steve", 100 * Money.ONE);
        survival.flush();
        assertEquals(100 * Money.ONE, there.balance(), "the second server read the first's balance");
        assertEquals(100 * Money.ONE, Bench.stored(lobby.database).get(id));
        Bench.assertLedgerMatchesBalances(lobby.database);
    }

    @Test
    void twoServersCantSpendTheSameMoneyTwice() throws Exception {
        UUID id = UUID.randomUUID();
        long start = 1_000 * Money.ONE;
        long price = 7 * Money.ONE;
        Account onLobby = lobby.ledger.open(id, "Steve", start);
        lobby.flush();
        survival.ledger.refresh(id);
        survival.flush();
        Account onSurvival = survival.accounts.get(id);
        assertNotNull(onSurvival);
        assertEquals(start, onSurvival.balance());

        AtomicInteger bought = new AtomicInteger();
        AtomicLong deposited = new AtomicLong();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> work = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            Bench server = t % 2 == 0 ? lobby : survival;
            Account account = t % 2 == 0 ? onLobby : onSurvival;
            boolean depositor = t == 7;
            work.add(pool.submit(() -> {
                for (int i = 0; i < 60; i++) {
                    if (depositor && i % 6 == 0) {
                        server.ledger.deposit(account, Money.ONE, Bench.CAUSE).join();
                        deposited.addAndGet(Money.ONE);
                        continue;
                    }
                    Outcome outcome = server.ledger.withdraw(account, price, Bench.CAUSE).orTimeout(30, TimeUnit.SECONDS).join();
                    if (outcome.success()) {
                        bought.incrementAndGet();
                    } else {
                        assertEquals(Status.INSUFFICIENT_FUNDS, outcome.status());
                    }
                }
            }));
        }
        for (Future<?> future : work) {
            future.get(2, TimeUnit.MINUTES);
        }
        pool.shutdown();
        lobby.flush();
        survival.flush();
        long expected = start + deposited.get() - bought.get() * price;
        long stored = Bench.stored(lobby.database).get(id);
        assertTrue(stored >= 0, "overspent: " + stored);
        assertTrue(bought.get() * price <= start + deposited.get(), "more was bought than there was money");
        assertEquals(expected, stored, "the shared balance isn't what was bought and deposited");
        Bench.assertLedgerMatchesBalances(lobby.database);
        // Each server's memory catches up with the other's changes when told about them.
        lobby.ledger.refresh(id);
        survival.ledger.refresh(id);
        lobby.flush();
        survival.flush();
        assertEquals(stored, onLobby.balance());
        assertEquals(stored, onSurvival.balance());
    }

    @Test
    void aTransferWaitsForTheDatabaseAndMovesBoth() throws Exception {
        Account steve = lobby.ledger.open(UUID.randomUUID(), "Steve", 50 * Money.ONE);
        Account alex = lobby.ledger.open(UUID.randomUUID(), "Alex", 0);
        lobby.flush();
        CompletableFuture<Outcome> paid = lobby.ledger.transfer(steve, alex, 20 * Money.ONE, Bench.CAUSE);
        Outcome outcome = paid.orTimeout(30, TimeUnit.SECONDS).join();
        assertEquals(Status.SUCCESS, outcome.status());
        assertEquals(30 * Money.ONE, outcome.balance());
        assertEquals(20 * Money.ONE, alex.balance());
        assertEquals(Status.INSUFFICIENT_FUNDS, lobby.ledger.transfer(alex, steve, 21 * Money.ONE, Bench.CAUSE).join().status());
        assertEquals(Status.SUCCESS, lobby.ledger.set(alex, 3 * Money.ONE, Bench.CAUSE).join().status());
        assertEquals(3 * Money.ONE, alex.balance());
        lobby.flush();
        Bench.assertLedgerMatchesBalances(lobby.database);
    }
}
