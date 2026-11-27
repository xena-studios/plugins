package co.xenastudios.xmoney.ledger

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.db.Database
import co.xenastudios.core.common.db.DbError
import co.xenastudios.core.common.db.SqlSession
import co.xenastudios.core.jvm.db.JdbcDatabase
import co.xenastudios.xmoney.api.TransactionResult.Status
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import org.junit.Assume.assumeTrue

/**
 * Two servers sharing one database, in network mode: the database decides what takes money, so
 * the same money is never spent twice, the starting balance is given once, and each server's
 * memory follows the other's changes. On one SQLite file, and on MariaDB in [MySqlSharedTest].
 */
open class SharedTest {
    protected val folder = Files.createTempDirectory("xmoney-shared")
    protected val platform = TestPlatform()
    private val opened = ArrayList<JdbcDatabase>()
    private val one = 10_000L

    protected open fun database(): JdbcDatabase = Bench.sqlite(folder.resolve("money.db"), platform)

    private fun server(): Ledger = database().also { opened += it }.let { Bench.ledger(it, platform, shared = true) }

    @AfterTest
    fun stop() {
        opened.forEach { it.close().await() }
        platform.scheduler.close()
        folder.toFile().deleteRecursively()
    }

    private fun player() = PlayerRef.of(Uuid.random())

    @Test
    fun onlyTheFirstServerGivesTheStartingBalance() {
        val a = server()
        val b = server()
        val steve = player()
        a.open(steve, "Steve", 50 * one)
        a.flush().await()
        b.open(steve, "Steve", 50 * one)
        b.flush().await()
        assertEquals(50 * one, b.accounts[steve]!!.balance)
        assertEquals(50 * one, Bench.stored(opened[0])[steve])
        Bench.assertLedgerMatchesBalances(opened[0])
    }

    /**
     * #187: B's database settling the new account's transaction before `open` returns, as a quick
     * writer thread does for a caller held up (CI's two cores under load). B found no account to
     * give the balance it read to, as the account is only indexed once `open` has queued its first
     * write, and kept 0.
     */
    @Test
    fun aSecondServerKeepsTheStartingBalanceItReadsHoweverQuickItsDatabaseIs() {
        val a = server()
        val real = database().also { opened += it }
        val quick = object : Database by real {
            override fun <T> transaction(work: (SqlSession) -> Outcome<T, DbError>): Pending<Outcome<T, DbError>> =
                real.transaction(work).also { it.await() }
        }
        val b = Bench.ledger(quick, platform, shared = true)
        val steve = player()
        a.open(steve, "Steve", 50 * one)
        a.flush().await()
        b.open(steve, "Steve", 50 * one)
        b.flush().await()
        assertEquals(50 * one, b.accounts[steve]!!.balance)
        assertEquals(50 * one, Bench.stored(opened[0])[steve])
        Bench.assertLedgerMatchesBalances(opened[0])
    }

    @Test
    fun twoServersCantSpendTheSameMoneyTwice() {
        val a = server()
        val b = server()
        val steve = player()
        val onA = a.open(steve, "Steve", 0)
        a.deposit(onA, 100 * one, CAUSE).await()
        a.flush().await()
        val onB = b.open(steve, "Steve", 0)
        b.refresh(steve)
        b.flush().await()
        assertEquals(100 * one, onB.balance)
        val taken = AtomicInteger()
        val done = CountDownLatch(8)
        for (ledger in listOf(a, b)) repeat(4) {
            Thread {
                val account = ledger.accounts[steve]!!
                repeat(10) { if (ledger.withdraw(account, 7 * one, CAUSE).await().status == Status.SUCCESS) taken.incrementAndGet() }
                done.countDown()
            }.start()
        }
        assertTrue(done.await(60, TimeUnit.SECONDS))
        assertEquals(14, taken.get(), "7 at a time out of 100 is 14 withdrawals, not more")
        assertEquals(2 * one, Bench.stored(opened[0])[steve])
        Bench.assertLedgerMatchesBalances(opened[0])
    }

    @Test
    fun aTransferWaitsForTheDatabaseAndMovesBoth() {
        val a = server()
        val steve = a.open(player(), "Steve", 0)
        val alex = a.open(player(), "Alex", 0)
        a.deposit(steve, 10 * one, CAUSE).await()
        assertEquals(Status.SUCCESS, a.transfer(steve, alex, 4 * one, CAUSE).await().status)
        assertEquals(Status.INSUFFICIENT_FUNDS, a.transfer(steve, alex, 40 * one, CAUSE).await().status)
        assertEquals(6 * one, steve.balance)
        assertEquals(4 * one, alex.balance)
        a.flush().await()
        Bench.assertLedgerMatchesBalances(opened[0])
    }

    @Test
    fun theOtherServerHearsOfAChangeAndAResyncKeepsWhatsUnsaved() {
        val a = server()
        val b = server()
        val steve = player()
        a.open(steve, "Steve", 0)
        b.open(steve, "Steve", 0)
        a.deposit(a.accounts[steve]!!, 30 * one, CAUSE).await()
        a.flush().await()
        b.resync()
        b.flush().await()
        assertEquals(30 * one, b.accounts[steve]!!.balance)
        assertEquals(Status.SUCCESS, b.set(b.accounts[steve]!!, 5 * one, CAUSE).await().status)
        a.refresh(steve)
        a.flush().await()
        assertEquals(5 * one, a.accounts[steve]!!.balance)
        Bench.assertLedgerMatchesBalances(opened[0])
    }

    @Test
    fun twoServersFirstDepositsToAnAccountDontFailEachOther() {
        val a = server()
        val b = server()
        val bank = a.named("town-Spawn")!!
        val sameOnB = b.named("town-Spawn")!!
        assertEquals(bank.id, sameOnB.id)
        val done = CountDownLatch(2)
        for ((ledger, account) in listOf(a to bank, b to sameOnB)) Thread {
            repeat(50) { ledger.deposit(account, one, CAUSE) }
            ledger.flush().await()
            done.countDown()
        }.start()
        assertTrue(done.await(60, TimeUnit.SECONDS))
        assertEquals(100 * one, Bench.stored(opened[0])[bank.id])
        Bench.assertLedgerMatchesBalances(opened[0])
    }

    @Test
    fun anImportIsSetInChunksAndTheOthersReadItAgain() {
        val a = server()
        val b = server()
        val targets = (1..250).map { a.account(player(), "P$it", true) to it * one }
        assertEquals(250, a.setAll(targets, Cause(co.xenastudios.xmoney.api.TransactionKind.IMPORT, "EssentialsX", "")).await())
        b.resync()
        b.flush().await()
        for ((account, balance) in targets) assertEquals(balance, b.accounts[account.id]!!.balance)
        Bench.assertLedgerMatchesBalances(opened[0])
    }
}

/** [SharedTest] on MariaDB or MySQL, with `XR_MYSQL` set, as CI sets it. */
class MySqlSharedTest : SharedTest() {
    private val prefix = "xm" + Uuid.random().toHexString().take(8) + "_"

    override fun database(): JdbcDatabase {
        val database = Bench.mysql(prefix, platform)
        assumeTrue("XR_MYSQL isn't set", database != null)
        return database!!
    }
}
