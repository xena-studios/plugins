package co.xenastudios.xmoney.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.xenastudios.xmoney.api.TransactionKind;
import co.xenastudios.xmoney.api.TransactionResult.Status;
import co.xenastudios.xmoney.money.Money;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The ledger on one server: memory is the truth, and the database follows it exactly. */
class LedgerTest {

    @TempDir
    Path folder;
    Bench bench;
    Ledger ledger;

    @BeforeEach
    void start() throws Exception {
        bench = new Bench(Bench.sqlite(folder.resolve("money.db")), false);
        ledger = bench.ledger;
    }

    @AfterEach
    void stop() {
        bench.close();
    }

    private Outcome now(java.util.concurrent.CompletableFuture<Outcome> future) {
        assertTrue(future.isDone(), "on one server a change never waits for the database");
        return future.join();
    }

    @Test
    void aJoiningPlayerGetsTheStartingBalanceOnce() throws Exception {
        UUID id = UUID.randomUUID();
        Account steve = ledger.open(id, "Steve", 100 * Money.ONE);
        assertEquals(100 * Money.ONE, steve.balance());
        assertSame(steve, ledger.open(id, "Steve", 100 * Money.ONE));
        assertEquals(100 * Money.ONE, steve.balance());
        bench.flush();
        assertEquals(100 * Money.ONE, Bench.stored(bench.database).get(id));
        Bench.assertLedgerMatchesBalances(bench.database);
    }

    @Test
    void changesAreCheckedAndSaved() throws Exception {
        Account steve = ledger.open(UUID.randomUUID(), "Steve", 0);
        Account alex = ledger.open(UUID.randomUUID(), "Alex", 0);
        assertEquals(Status.SUCCESS, now(ledger.deposit(steve, 50 * Money.ONE, Bench.CAUSE)).status());
        assertEquals(Status.INSUFFICIENT_FUNDS, now(ledger.withdraw(steve, 51 * Money.ONE, Bench.CAUSE)).status());
        assertEquals(Status.INVALID_AMOUNT, now(ledger.withdraw(steve, -1, Bench.CAUSE)).status());
        assertEquals(Status.INVALID_AMOUNT, now(ledger.deposit(steve, 0, Bench.CAUSE)).status());
        Outcome paid = now(ledger.transfer(steve, alex, 20 * Money.ONE, Bench.CAUSE));
        assertEquals(Status.SUCCESS, paid.status());
        assertEquals(30 * Money.ONE, paid.balance());
        assertEquals(20 * Money.ONE, alex.balance());
        assertEquals(Status.SAME_ACCOUNT, now(ledger.transfer(steve, steve, 1, Bench.CAUSE)).status());
        assertEquals(Status.INSUFFICIENT_FUNDS, now(ledger.transfer(alex, steve, 21 * Money.ONE, Bench.CAUSE)).status());
        assertEquals(Status.BALANCE_LIMIT, now(ledger.deposit(steve, Bench.MAX, Bench.CAUSE)).status());
        assertEquals(Status.SUCCESS, now(ledger.set(alex, 7 * Money.ONE, Bench.CAUSE)).status());
        assertEquals(7 * Money.ONE, alex.balance());
        assertEquals(37 * Money.ONE, bench.accounts.total());
        bench.flush();
        assertEquals(30 * Money.ONE, Bench.stored(bench.database).get(steve.id()));
        assertEquals(7 * Money.ONE, Bench.stored(bench.database).get(alex.id()));
        Bench.assertLedgerMatchesBalances(bench.database);
    }

    @Test
    void historyRecordsWhoAndWhy() throws Exception {
        Account steve = ledger.open(UUID.randomUUID(), "Steve", 10 * Money.ONE);
        Account alex = ledger.open(UUID.randomUUID(), "Alex", 0);
        ledger.transfer(steve, alex, 4 * Money.ONE, new Cause(TransactionKind.PAY, "Steve", ""));
        ledger.deposit(alex, Money.ONE, new Cause(TransactionKind.PLUGIN, "xVote", "vote reward"));
        bench.flush();
        List<Store.Row> rows = bench.store.history(alex.id(), 0, 10);
        assertEquals(2, rows.size());
        assertEquals(TransactionKind.PLUGIN, rows.get(0).kind());
        assertEquals("xVote", rows.get(0).source());
        assertEquals("vote reward", rows.get(0).reason());
        assertEquals(5 * Money.ONE, rows.get(0).balance());
        assertEquals(TransactionKind.PAY, rows.get(1).kind());
        assertEquals(steve.id(), rows.get(1).other());
        assertEquals(4 * Money.ONE, rows.get(1).amount());
        assertEquals(2, bench.store.count(steve.id()), "the starting balance and the payment");
    }

    @Test
    void balancesComeBackAfterARestart() throws Exception {
        Account steve = ledger.open(UUID.randomUUID(), "Steve", 0);
        ledger.deposit(steve, 1234_5600L, Bench.CAUSE);
        Account bank = ledger.named("town-Foo");
        ledger.deposit(bank, 99 * Money.ONE, Bench.CAUSE);
        bench.close();
        try (Bench again = new Bench(bench.database, false)) {
            Account loaded = again.accounts.get(steve.id());
            assertNotNull(loaded);
            assertEquals(1234_5600L, loaded.balance());
            assertEquals("Steve", loaded.name());
            Account town = again.accounts.named("TOWN-foo");
            assertNotNull(town);
            assertFalse(town.player(), "a name-made account isn't a player's");
            assertEquals(99 * Money.ONE, town.balance());
            assertEquals(1, again.accounts.ranked(), "only players are in the top list");
        }
    }

    @Test
    void theTopListFollowsEveryChange() {
        Account a = ledger.open(UUID.randomUUID(), "A", 10);
        Account b = ledger.open(UUID.randomUUID(), "B", 20);
        Account c = ledger.open(UUID.randomUUID(), "C", 30);
        assertEquals(List.of("C", "B", "A"), names());
        ledger.deposit(a, 100, Bench.CAUSE);
        ledger.withdraw(c, 25, Bench.CAUSE);
        assertEquals(List.of("A", "B", "C"), names());
        assertEquals(List.of("B"), bench.accounts.top(2, 1).stream().map(r -> r.account().name()).toList());
        ledger.transfer(a, b, 110, Bench.CAUSE);
        assertEquals("B", names().getFirst());
        assertEquals(135, bench.accounts.total());
        assertSame(b, bench.accounts.named("b"));
    }

    private List<String> names() {
        return bench.accounts.top(1, 10).stream().map(r -> r.account().name()).toList();
    }

    @Test
    void aRenamedPlayerIsFoundByTheNewName() {
        Account steve = ledger.open(UUID.randomUUID(), "Steve", 0);
        ledger.open(steve.id(), "Steven", 0);
        assertSame(steve, bench.accounts.named("steven"));
        assertEquals(null, bench.accounts.named("steve"));
    }

    @Test
    void handlersCanRefuse() {
        Account steve = ledger.open(UUID.randomUUID(), "Steve", 100);
        ledger.events(new Events() {
            @Override
            public boolean allow(UUID player, long delta, Cause cause, UUID other) {
                return delta > -50;
            }
        });
        assertEquals(Status.SUCCESS, now(ledger.withdraw(steve, 10, Bench.CAUSE)).status());
        assertEquals(Status.CANCELLED, now(ledger.withdraw(steve, 60, Bench.CAUSE)).status());
        assertEquals(90, steve.balance());
    }

    @Test
    void oldLedgerRowsArePrunedAndBalancesStay() throws Exception {
        Account steve = ledger.open(UUID.randomUUID(), "Steve", 0);
        for (int i = 0; i < 12_000; i++) {
            ledger.deposit(steve, 1, Bench.CAUSE);
        }
        bench.flush();
        ledger.prune(System.currentTimeMillis() + 1);
        bench.flush();
        // Slices of 5,000 between other writes: three slices, each re-queued behind the flush.
        for (int i = 0; i < 5 && bench.store.count(steve.id()) > 0; i++) {
            bench.flush();
        }
        assertEquals(0, bench.store.count(steve.id()));
        assertEquals(12_000L, Bench.stored(bench.database).get(steve.id()));
    }
}
