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
        assertEquals(Writer.IMPORT_CHUNK, payment, "the payment went in after the first chunk, not after the whole import");
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
