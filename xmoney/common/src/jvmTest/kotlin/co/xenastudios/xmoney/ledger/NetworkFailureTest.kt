package co.xenastudios.xmoney.ledger

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.Outcome as DbOutcome
import co.xenastudios.xmoney.api.TransactionKind
import co.xenastudios.xmoney.api.TransactionResult.Status
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Network mode while the shared database fails: what's refused is never taken, what's done is
 * done once, and nothing is guessed at. A connection wrapper ([Faults]) makes the writer's
 * database go away, lose a commit's answer, or crawl.
 */
class NetworkFailureTest {
    private val folder = Files.createTempDirectory("xmoney-network-failure")
    private val platform = TestPlatform()
    private val faults = Faults()
    private val database = Bench.sqlite(folder.resolve("money.db"), platform, faults)
    private val ledger = Bench.ledger(database, platform, shared = true)
    private val one = 10_000L
    private val steve = ledger.open(PlayerRef.of(Uuid.random()), "Steve", 0)

    init {
        ledger.deposit(steve, 100 * one, CAUSE).await()
        ledger.flush().await()
    }

    @AfterTest
    fun stop() {
        faults.down = false
        database.close().await()
        platform.scheduler.close()
        folder.toFile().deleteRecursively()
    }

    private fun stored() = Bench.stored(database)[steve.id]

    private fun awaitDown() {
        val deadline = System.currentTimeMillis() + 10_000
        while (database.available && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertFalse(database.available, "the database never went away")
    }

    @Test
    fun whileTheDatabaseIsAwayWithdrawalsAreRefusedAtOnceAndNeverTakenLater() {
        faults.down = true
        // A deposit's save finds the database gone, and waits for it.
        ledger.deposit(steve, one, CAUSE)
        awaitDown()
        // Refused before withdraw returns: nothing waits for a database that's away (a time
        // limit here would only measure how busy the machine is).
        val refused = ledger.withdraw(steve, 10 * one, CAUSE)
        assertTrue(refused.isDone, "a withdrawal waited for a database that's away")
        assertEquals(Status.UNAVAILABLE, refused.await().status)
        faults.down = false
        ledger.flush().await(30)
        assertEquals(101 * one, stored())
        assertEquals(101 * one, steve.balance)
        Bench.assertLedgerMatchesBalances(database)
    }

    @Test
    fun anAbandonedChangeNeverRuns() {
        val hold = CountDownLatch(1)
        database.session { hold.await(); DbOutcome.Ok(Unit) }
        val result = ledger.withdraw(steve, 10 * one, CAUSE)
        assertTrue(ledger.abandon(result, Outcome(Status.UNAVAILABLE, 0, steve.balance)))
        assertEquals(Status.UNAVAILABLE, result.await().status)
        hold.countDown()
        ledger.flush().await()
        assertEquals(100 * one, stored())
        assertEquals(100 * one, steve.balance)
    }

    @Test
    fun aStartedChangeCantBeAbandonedAndIsWaitedFor() {
        faults.slow = 100
        val before = faults.executions.get()
        val result = ledger.withdraw(steve, 10 * one, CAUSE)
        // Started: its first statement is running (and crawling), however long the writer took to get to it.
        val deadline = System.nanoTime() + 15_000_000_000L
        while (faults.executions.get() == before) {
            assertTrue(System.nanoTime() < deadline, "the writer never started the withdrawal")
            Thread.sleep(1)
        }
        assertFalse(ledger.abandon(result, Outcome(Status.UNAVAILABLE, 0, steve.balance)), "a started change was abandoned")
        faults.slow = 0
        assertEquals(Status.SUCCESS, result.await().status)
        assertEquals(90 * one, stored())
    }

    @Test
    fun aCommitWhoseAnswerWasLostIsReportedDoneAndAppliedOnce() {
        faults.loseCommit = true
        assertEquals(Status.SUCCESS, ledger.withdraw(steve, 10 * one, CAUSE).await(30).status)
        ledger.flush().await()
        assertEquals(90 * one, stored())
        assertEquals(90 * one, steve.balance)
        Bench.assertLedgerMatchesBalances(database)
    }

    @Test
    fun aLostCommitOfDepositsIsSavedOnce() {
        faults.loseCommit = true
        ledger.deposit(steve, 5 * one, CAUSE)
        ledger.flush().await(30)
        assertEquals(105 * one, stored())
        assertEquals(105 * one, steve.balance)
        Bench.assertLedgerMatchesBalances(database)
    }

    @Test
    fun aChangeTooSlowToReachItsCommitIsRefusedAndRolledBack() {
        ledger.budgetMillis = 50
        faults.slow = 30
        assertEquals(Status.UNAVAILABLE, ledger.withdraw(steve, 10 * one, CAUSE).await().status)
        faults.slow = 0
        ledger.flush().await()
        assertEquals(100 * one, stored())
        assertEquals(100 * one, steve.balance)
    }

    @Test
    fun aChangeNotStartedInTimeIsRefusedEvenIfNobodyWaits() {
        ledger.waitMillis = 200
        val hold = CountDownLatch(1)
        database.session { hold.await(); DbOutcome.Ok(Unit) }
        val result = ledger.withdraw(steve, 10 * one, CAUSE)
        assertEquals(Status.UNAVAILABLE, result.await(5).status)
        hold.countDown()
        ledger.flush().await()
        assertEquals(100 * one, stored())
    }

    @Test
    fun anImportWaitsOutAnOutageRatherThanDropAnything() {
        val targets = (1..120).map { ledger.account(PlayerRef.of(Uuid.random()), "P$it", true) to it * one }
        faults.down = true
        faults.killNext = true
        val set = ledger.setAll(targets, Cause(TransactionKind.IMPORT, "CMI", ""))
        awaitDown()
        Thread.sleep(300)
        faults.down = false
        assertEquals(120, set.await(60))
        val stored = Bench.stored(database)
        for ((account, balance) in targets) assertEquals(balance, stored[account.id])
        Bench.assertLedgerMatchesBalances(database)
    }
}
