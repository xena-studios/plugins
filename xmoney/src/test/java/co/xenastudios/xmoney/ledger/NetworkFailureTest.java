package co.xenastudios.xmoney.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.xenastudios.xmoney.Bank;
import co.xenastudios.xmoney.api.TransactionKind;
import co.xenastudios.xmoney.api.TransactionResult.Status;
import co.xenastudios.xmoney.config.XMConfig;
import co.xenastudios.xmoney.money.Money;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Network mode when the shared database is slow, away, or loses an answer: a caller told a
 * change failed was never charged, a started change is answered with what really happened,
 * and nothing is applied twice.
 */
class NetworkFailureTest {

    @TempDir
    Path folder;
    Bench bench;
    Account steve;

    @BeforeEach
    void start() throws Exception {
        bench = new Bench(Bench.sqlite(folder.resolve("money.db")), true);
        bench.ledger.waitMs = 200;
        steve = bench.ledger.open(UUID.randomUUID(), "Steve", 100 * Money.ONE);
        bench.flush();
        assertEquals(100 * Money.ONE, steve.balance());
    }

    @AfterEach
    void stop() {
        bench.faults.down = false;
        bench.close();
    }

    private Bank bank(boolean serverThread) {
        return new Bank(bench.ledger, () -> XMConfig.DEFAULTS, Logger.getAnonymousLogger(), () -> serverThread);
    }

    private long stored() throws Exception {
        return Bench.stored(bench.database).get(steve.id());
    }

    @Test
    void aWithdrawalQueuedBehindAFailingWriteIsRefusedAndNeverTakenLater() throws Exception {
        CountDownLatch release = bench.hold();
        bench.faults.down = true;
        bench.ledger.deposit(steve, Money.ONE, Bench.CAUSE); // can't be written while it's down
        CompletableFuture<Outcome> withdrawal = bench.ledger.withdraw(steve, 10 * Money.ONE, Bench.CAUSE);
        release.countDown();
        Outcome outcome = withdrawal.get(10, TimeUnit.SECONDS);
        assertEquals(Status.UNAVAILABLE, outcome.status(), "not started by its deadline: refused");
        // The database comes back: the deposit is saved, the refused withdrawal never runs.
        bench.faults.down = false;
        bench.flush();
        assertEquals(101 * Money.ONE, stored());
        assertEquals(101 * Money.ONE, steve.balance());
        assertEquals(List.of("START 1000000", "PLUGIN 10000"), Bench.rows(bench.database, steve.id()));
        Bench.assertLedgerMatchesBalances(bench.database);
    }

    @Test
    void whileTheWriterIsFailingWithdrawalsAreRefusedAtOnce() throws Exception {
        bench.faults.down = true;
        bench.ledger.deposit(steve, Money.ONE, Bench.CAUSE);
        long until = System.currentTimeMillis() + 10_000;
        while (!bench.writer.failing()) {
            assertTrue(System.currentTimeMillis() < until, "the writer never noticed");
            Thread.sleep(10);
        }
        CompletableFuture<Outcome> refused = bench.ledger.withdraw(steve, Money.ONE, Bench.CAUSE);
        assertTrue(refused.isDone(), "refused on the spot, not after a wait");
        assertEquals(Status.UNAVAILABLE, refused.join().status());
        assertEquals(Status.UNAVAILABLE, bench.ledger.transfer(steve, bench.ledger.account(UUID.randomUUID(), "Alex", true),
            Money.ONE, Bench.CAUSE).join().status());
        bench.faults.down = false;
        bench.flush();
        assertFalse(bench.writer.failing());
        assertEquals(101 * Money.ONE, stored());
    }

    @Test
    void aServerThreadIsRefusedQuicklyAndNothingIsTaken() throws Exception {
        CountDownLatch release = bench.hold();
        long before = System.nanoTime();
        Outcome outcome = bank(true).await(bench.ledger.withdraw(steve, 5 * Money.ONE, Bench.CAUSE), steve);
        long waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before);
        assertEquals(Status.UNAVAILABLE, outcome.status());
        assertTrue(waited < Bank.MAX_SERVER_THREAD_WAIT_MS, "a server thread waited " + waited + "ms");
        release.countDown();
        bench.flush();
        assertEquals(100 * Money.ONE, stored(), "the abandoned withdrawal never ran");
        assertEquals(100 * Money.ONE, steve.balance());
    }

    @Test
    void offTheServerThreadsACallerWaitsLonger() throws Exception {
        CountDownLatch release = bench.hold();
        CompletableFuture<Outcome> pending = bench.ledger.withdraw(steve, 5 * Money.ONE, Bench.CAUSE);
        Thread.sleep(100); // past network-wait-ms, inside the ledger's own wait
        release.countDown();
        assertEquals(Status.SUCCESS, bank(false).await(pending, steve).status());
        assertEquals(95 * Money.ONE, stored());
    }

    @Test
    void aStartedChangeIsWaitedForNotGuessedAt() throws Exception {
        // The database is slow to take it up, but it has started: the caller gets its real outcome.
        bench.faults.slowText = "balance >= ?";
        bench.faults.slowMillis = 400;
        Outcome outcome = bank(true).await(bench.ledger.withdraw(steve, 5 * Money.ONE, Bench.CAUSE), steve);
        assertEquals(Status.SUCCESS, outcome.status(), "a started change isn't reported as failed");
        assertEquals(95 * Money.ONE, outcome.balance());
        assertEquals(95 * Money.ONE, stored());
    }

    @Test
    void anAbandonedChangeCantBeStartedAndAStartedOneCantBeAbandoned() {
        Outcome refused = Outcome.failed(Status.UNAVAILABLE, 0);
        Pending queued = new Pending(Long.MAX_VALUE);
        assertTrue(queued.abandon(refused));
        assertFalse(queued.start(System.currentTimeMillis(), refused));
        assertEquals(refused, queued.join());

        Pending started = new Pending(Long.MAX_VALUE);
        assertTrue(started.start(System.currentTimeMillis(), refused));
        assertFalse(started.abandon(refused), "started: its real outcome is coming");
        assertFalse(started.isDone());

        Pending late = new Pending(System.currentTimeMillis() - 1);
        assertFalse(late.start(System.currentTimeMillis(), refused), "past its deadline: never run");
        assertEquals(Status.UNAVAILABLE, late.join().status());
    }

    @Test
    void aFailureBeforeTheCommitIsReportedForWhatItWasAndNotRetried() throws Exception {
        bench.faults.failText = "balance >= ?";
        bench.faults.failures.set(1);
        Outcome outcome = bench.ledger.withdraw(steve, 5 * Money.ONE, Bench.CAUSE).get(10, TimeUnit.SECONDS);
        assertEquals(Status.UNAVAILABLE, outcome.status());
        bench.flush();
        assertEquals(100 * Money.ONE, stored(), "rolled back, and not run again behind the caller's back");
        assertEquals(List.of("START 1000000"), Bench.rows(bench.database, steve.id()));
    }

    @Test
    void aCommitWhoseAnswerWasLostIsReportedAsDoneAndNotAppliedTwice() throws Exception {
        List<Entry> heard = new ArrayList<>();
        bench.ledger.events(new Events() {
            @Override
            public void saved(List<Entry> entries) {
                heard.addAll(entries);
            }
        });
        bench.faults.lostCommits.set(1);
        Outcome outcome = bench.ledger.withdraw(steve, 5 * Money.ONE, Bench.CAUSE).get(10, TimeUnit.SECONDS);
        assertEquals(Status.SUCCESS, outcome.status(), "it went through, so it's reported as done");
        assertEquals(5 * Money.ONE, outcome.amount());
        assertEquals(95 * Money.ONE, outcome.balance());
        assertEquals(95 * Money.ONE, stored());
        assertEquals(List.of("START 1000000", "PLUGIN -50000"), Bench.rows(bench.database, steve.id()), "applied once");
        assertEquals(1, heard.size(), "other plugins hear about it once");

        // A set whose answer is lost reports how much it moved, not 0.
        bench.faults.lostCommits.set(1);
        Outcome set = bench.ledger.set(steve, 20 * Money.ONE, new Cause(TransactionKind.SET, "Console", "")).get(10, TimeUnit.SECONDS);
        assertEquals(Status.SUCCESS, set.status());
        assertEquals(75 * Money.ONE, set.amount());
        assertEquals(20 * Money.ONE, steve.balance());
        assertEquals(2, heard.size());
        Bench.assertLedgerMatchesBalances(bench.database);
    }

    @Test
    void aLostCommitOfDepositsIsSavedOnce() throws Exception {
        bench.faults.lostCommits.set(1);
        for (int i = 0; i < 5; i++) {
            bench.ledger.deposit(steve, Money.ONE, Bench.CAUSE);
        }
        bench.flush();
        assertEquals(105 * Money.ONE, stored());
        assertEquals(105 * Money.ONE, steve.balance(), "nothing left pending twice");
        Bench.assertLedgerMatchesBalances(bench.database);
    }

    @Test
    void anImportIsSetInChunksWithPaymentsBetween() throws Exception {
        int count = Writer.IMPORT_CHUNK * 2 + 7;
        List<Ledger.Target> targets = targets(count);
        bench.flush();
        List<Entry> heard = new java.util.concurrent.CopyOnWriteArrayList<>();
        bench.ledger.events(new Events() {
            @Override
            public void saved(List<Entry> entries) {
                heard.addAll(entries);
            }
        });
        bench.ledger.waitMs = Ledger.WAIT_MS; // as for a command: a chunk takes longer than the tests' 200ms
        bench.faults.lostCommits.set(1); // the first chunk's answer is lost, and it isn't set twice
        CountDownLatch release = bench.hold();
        CompletableFuture<Integer> imported = bench.ledger.setAll(targets, new Cause(TransactionKind.IMPORT, "Test", ""));
        CompletableFuture<Outcome> paid = bench.ledger.withdraw(steve, Money.ONE, Bench.CAUSE);
        release.countDown();
        assertEquals(count, imported.get(60, TimeUnit.SECONDS));
        assertEquals(Status.SUCCESS, paid.get(10, TimeUnit.SECONDS).status());
        int payment = 0;
        for (int i = 0; i < heard.size(); i++) {
            if (heard.get(i).cause().kind() != TransactionKind.IMPORT) {
                payment = i;
            }
        }
        assertTrue(payment > 0 && payment <= Writer.IMPORT_CHUNK,
            "the payment went in after the first chunk, not after the whole import: after " + payment);
        assertEquals(count + 1, heard.size());
        assertImported(targets);
    }

    @Test
    void anImportWaitsForTheDatabaseRatherThanDropAnything() throws Exception {
        List<Ledger.Target> targets = targets(Writer.IMPORT_CHUNK + 3);
        bench.flush();
        CountDownLatch release = bench.hold();
        bench.faults.down = true;
        CompletableFuture<Integer> imported = bench.ledger.setAll(targets, new Cause(TransactionKind.IMPORT, "Test", ""));
        release.countDown();
        Thread.sleep(500);
        assertFalse(imported.isDone(), "still waiting for the database");
        bench.faults.down = false;
        assertEquals(targets.size(), imported.get(60, TimeUnit.SECONDS));
        assertImported(targets);
    }

    private List<Ledger.Target> targets(int count) {
        List<Ledger.Target> targets = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            targets.add(new Ledger.Target(bench.ledger.account(UUID.randomUUID(), "P" + i, true), (i + 1) * Money.ONE));
        }
        return targets;
    }

    private void assertImported(List<Ledger.Target> targets) throws Exception {
        bench.flush();
        var stored = Bench.stored(bench.database);
        for (Ledger.Target target : targets) {
            assertEquals(target.balance(), stored.get(target.account().id()));
            assertEquals(target.balance(), target.account().balance());
        }
        Bench.assertLedgerMatchesBalances(bench.database);
    }

    private void awaitRecovered() throws InterruptedException {
        long until = System.currentTimeMillis() + 10_000;
        while (bench.writer.failing()) {
            assertTrue(System.currentTimeMillis() < until, "the writer never noticed the database was back");
            Thread.sleep(20);
        }
    }

    @Test
    void aWithdrawalOnlyOutageRecovers() throws Exception {
        // The database goes away while a started withdrawal runs: it's answered (nothing
        // taken) and gone, so the writer has nothing left to save.
        bench.faults.failText = "balance >= ?";
        bench.faults.failures.set(1);
        Outcome refused = bench.ledger.withdraw(steve, Money.ONE, Bench.CAUSE).get(10, TimeUnit.SECONDS);
        assertEquals(Status.UNAVAILABLE, refused.status(), "the database is away: refused, nothing taken");
        assertTrue(bench.writer.failing());
        bench.faults.down = true;
        assertEquals(Status.UNAVAILABLE, bench.ledger.withdraw(steve, Money.ONE, Bench.CAUSE).join().status(), "refused at once meanwhile");
        Thread.sleep(1_500); // the writer's checks fail meanwhile
        assertTrue(bench.writer.failing());
        // Only the writer's own check can notice it's back: no change reaches the writer to try.
        bench.faults.down = false;
        awaitRecovered();
        Outcome paid = bench.ledger.withdraw(steve, Money.ONE, Bench.CAUSE).get(10, TimeUnit.SECONDS);
        assertEquals(Status.SUCCESS, paid.status());
        assertEquals(99 * Money.ONE, stored());
    }

    @Test
    void aConnectionTheDatabaseClosedWhileIdleIsReplacedBeforeAPayment() throws Exception {
        bench.writer.idleCheckNanos = TimeUnit.MILLISECONDS.toNanos(50);
        bench.faults.killOpen(); // MySQL's wait_timeout closed it while the server was quiet
        Thread.sleep(100);
        Outcome paid = bench.ledger.withdraw(steve, Money.ONE, Bench.CAUSE).get(10, TimeUnit.SECONDS);
        assertEquals(Status.SUCCESS, paid.status(), "checked and replaced first, not tried and failed");
        assertFalse(bench.writer.failing());
        assertTrue(bench.logged.isEmpty(), "nothing failed: " + bench.logged);
        assertEquals(99 * Money.ONE, stored());
    }

    @Test
    void onANetworkRetriesAreAtMostTwoSecondsApart() throws Exception {
        bench.faults.down = true;
        bench.ledger.deposit(steve, Money.ONE, Bench.CAUSE);
        Thread.sleep(8_000); // pauses of 0.5, 1, 2, 2, 2 s: doubling, they'd be 4 and then 8 by now
        bench.faults.down = false;
        long before = System.nanoTime();
        bench.flush();
        long waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before);
        assertTrue(waited < Writer.NETWORK_MAX_PAUSE_MS + 1_000, "saved " + waited + "ms after the database came back");
        assertEquals(101 * Money.ONE, stored());
    }

    @Test
    void aNetworkPaymentNobodyWaitsWithATimeoutIsStillAnsweredInTime() throws Exception {
        CountDownLatch release = bench.hold();
        CompletableFuture<Outcome> pending = bench.ledger.withdraw(steve, 5 * Money.ONE, Bench.CAUSE);
        // As the API and the commands use it: nobody calls Bank#await, or gives up on it.
        assertEquals(Status.UNAVAILABLE, pending.get(5, TimeUnit.SECONDS).status());
        release.countDown();
        bench.flush();
        assertEquals(100 * Money.ONE, stored(), "answered as refused, so never run");
    }

    @Test
    void aStartedChangeTooSlowToReachItsCommitIsRefusedAndRolledBack() throws Exception {
        bench.writer.checkedBudgetNanos = TimeUnit.MILLISECONDS.toNanos(100);
        bench.faults.slowText = "balance >= ?";
        bench.faults.slowMillis = 300; // the UPDATE that takes the money runs, late
        Outcome outcome = bench.ledger.withdraw(steve, 5 * Money.ONE, Bench.CAUSE).get(10, TimeUnit.SECONDS);
        assertEquals(Status.UNAVAILABLE, outcome.status());
        bench.faults.slowMillis = 0;
        bench.flush();
        assertEquals(100 * Money.ONE, stored(), "rolled back before the commit, so the refusal is true");
        assertEquals(List.of("START 1000000"), Bench.rows(bench.database, steve.id()));
        assertFalse(bench.writer.failing(), "a slow database isn't a failing one");
    }

    @Test
    void stoppingAnswersAStartedChangeWhoseCommitWasNeverSent() throws Exception {
        bench.writer.checkedBudgetNanos = TimeUnit.SECONDS.toNanos(60);
        bench.faults.slowText = "balance >= ?";
        bench.faults.stubbornMillis = 7_000; // stuck, as in a socket read, past stop's 5 s
        Pending pending = (Pending) bench.ledger.withdraw(steve, 5 * Money.ONE, Bench.CAUSE);
        long until = System.currentTimeMillis() + 5_000;
        while (!pending.started()) {
            assertTrue(System.currentTimeMillis() < until);
            Thread.sleep(10);
        }
        bench.writer.stop(100, TimeUnit.MILLISECONDS);
        assertEquals(Status.UNAVAILABLE, pending.get(1, TimeUnit.SECONDS).status(), "answered, not left waiting");
        Thread.sleep(3_000); // the writer comes unstuck, and must not commit it now
        assertEquals(100 * Money.ONE, stored());
        assertEquals(List.of("START 1000000"), Bench.rows(bench.database, steve.id()));
    }

    @Test
    void aBatchKeepsTheOrderOfItsRowsAndEachRowsBalance() throws Exception {
        Account alex = bench.ledger.account(UUID.randomUUID(), "Alex", true);
        bench.flush();
        CountDownLatch release = bench.hold();
        // Saved together, balances in uuid order; rows in the order they happened.
        bench.ledger.deposit(steve, Money.ONE, Bench.CAUSE);
        bench.ledger.deposit(alex, 2 * Money.ONE, Bench.CAUSE);
        bench.ledger.deposit(steve, 3 * Money.ONE, Bench.CAUSE);
        release.countDown();
        bench.flush();
        List<String> rows = new ArrayList<>();
        try (java.sql.Connection connection = bench.database.connect();
             java.sql.PreparedStatement query = connection.prepareStatement(
                 bench.database.sql("SELECT uuid, amount, balance FROM {p}transactions WHERE kind = ? ORDER BY id"))) {
            query.setString(1, TransactionKind.PLUGIN.name());
            try (java.sql.ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    rows.add((result.getString(1).equals(steve.id().toString()) ? "steve" : "alex")
                        + " " + result.getLong(2) / Money.ONE + " " + result.getLong(3) / Money.ONE);
                }
            }
        }
        assertEquals(List.of("steve 1 101", "alex 2 2", "steve 3 104"), rows);
        assertEquals(104 * Money.ONE, steve.balance());
        Bench.assertLedgerMatchesBalances(bench.database);
    }

    @Test
    void tooManyWaitingChangesAreRefused() throws Exception {
        CountDownLatch release = bench.hold();
        for (int i = 0; i < Writer.MAX_WAITING; i++) {
            bench.writer.add(new Op.Name(steve));
        }
        assertEquals(Status.UNAVAILABLE, bench.ledger.deposit(steve, Money.ONE, Bench.CAUSE).join().status());
        assertTrue(bench.logged.stream().anyMatch(line -> line.contains("are waiting")), "the console is told");
        release.countDown();
        bench.flush();
        assertEquals(Status.SUCCESS, bench.ledger.deposit(steve, Money.ONE, Bench.CAUSE).join().status());
    }
}
