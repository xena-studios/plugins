package co.xenastudios.xmoney.ledger

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.onSettled
import co.xenastudios.xmoney.api.TransactionKind
import co.xenastudios.xmoney.api.TransactionResult.Status
import java.nio.file.Files
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** One server on a real SQLite file: memory is the truth, and the database follows it exactly. */
class LedgerTest {
    private val folder = Files.createTempDirectory("xmoney-ledger")
    private val file = folder.resolve("money.db")
    private val platform = TestPlatform()
    private var database = Bench.sqlite(file, platform)
    private var ledger = Bench.ledger(database, platform)
    private val one = 10_000L

    @AfterTest
    fun stop() {
        database.close().await()
        platform.scheduler.close()
        folder.toFile().deleteRecursively()
    }

    private fun restart() {
        ledger.flush().await()
        database.close().await()
        database = Bench.sqlite(file, platform)
        ledger = Bench.ledger(database, platform)
    }

    private fun player() = PlayerRef.of(Uuid.random())

    @Test
    fun aJoiningPlayerGetsTheStartingBalanceOnce() {
        val steve = player()
        assertEquals(50 * one, ledger.open(steve, "Steve", 50 * one).balance)
        assertEquals(50 * one, ledger.open(steve, "Steve", 50 * one).balance)
        restart()
        assertEquals(50 * one, ledger.open(steve, "Steve", 50 * one).balance)
        Bench.assertLedgerMatchesBalances(database)
    }

    @Test
    fun changesAreCheckedAndSaved() {
        val steve = ledger.open(player(), "Steve", 0)
        val alex = ledger.open(player(), "Alex", 0)
        assertEquals(Status.SUCCESS, ledger.deposit(steve, 100 * one, CAUSE).await().status)
        assertEquals(Status.INSUFFICIENT_FUNDS, ledger.withdraw(steve, 101 * one, CAUSE).await().status)
        assertEquals(Status.INVALID_AMOUNT, ledger.deposit(steve, 0, CAUSE).await().status)
        assertEquals(Status.SAME_ACCOUNT, ledger.transfer(steve, steve, one, CAUSE).await().status)
        assertEquals(Status.SUCCESS, ledger.transfer(steve, alex, 30 * one, CAUSE).await().status)
        assertEquals(Status.SUCCESS, ledger.set(alex, 5 * one, CAUSE).await().status)
        assertEquals(70 * one, steve.balance)
        assertEquals(5 * one, alex.balance)
        ledger.flush().await()
        assertEquals(mapOf(steve.id to 70 * one, alex.id to 5 * one), Bench.stored(database))
        Bench.assertLedgerMatchesBalances(database)
    }

    @Test
    fun maxBalanceIsALimit() {
        val small = Bench.ledger(database, platform, max = 10 * one)
        val steve = small.open(player(), "Steve", 0)
        assertEquals(Status.SUCCESS, small.deposit(steve, 10 * one, CAUSE).await().status)
        assertEquals(Status.BALANCE_LIMIT, small.deposit(steve, 1, CAUSE).await().status)
        assertEquals(Status.BALANCE_LIMIT, small.set(steve, 11 * one, CAUSE).await().status)
    }

    @Test
    fun balancesNamesAndHistoryComeBackAfterARestart() {
        val steve = ledger.open(player(), "Steve", 0)
        val other = player()
        ledger.deposit(steve, 12 * one, Cause(TransactionKind.PLUGIN, "xVote", "vote reward"), other).await()
        ledger.withdraw(steve, 2 * one, Cause(TransactionKind.TAKE, "Console", "")).await()
        restart()
        val again = ledger.accounts[steve.id]!!
        assertEquals(10 * one, again.balance)
        assertEquals(again, ledger.accounts.named("steve"))
        val (count, rows) = ledger.history(steve.id, 0, 10).await().ok()
        assertEquals(2, count)
        assertEquals(listOf(TransactionKind.TAKE, TransactionKind.PLUGIN), rows.map { it.kind })
        assertEquals("vote reward", rows[1].reason)
        assertEquals(other, rows[1].other)
        assertEquals(listOf(10 * one, 12 * one), rows.map { it.balance })
    }

    @Test
    fun theTopListAndTotalFollowEveryChange() {
        val a = ledger.open(player(), "A", 0)
        val b = ledger.open(player(), "B", 0)
        val bank = ledger.named("town-Spawn")!!
        ledger.deposit(a, 5 * one, CAUSE).await()
        ledger.deposit(b, 9 * one, CAUSE).await()
        ledger.deposit(bank, 100 * one, CAUSE).await()
        assertEquals(listOf("B", "A"), ledger.accounts.top(1, 10).map { it.account.name })
        assertEquals(14 * one, ledger.accounts.total())
        ledger.transfer(b, a, 8 * one, CAUSE).await()
        assertEquals(listOf("A", "B"), ledger.accounts.top(1, 10).map { it.account.name })
        assertEquals(14 * one, ledger.accounts.total())
    }

    @Test
    fun nameBasedAccountsHave1xsUuids() {
        // 1.x: UUID.nameUUIDFromBytes(("xMoney account:" + name.toLowerCase()).getBytes(UTF_8)).
        for (name in listOf("town-Spawn", "Nation_Ünïcode", "x")) {
            val expected = UUID.nameUUIDFromBytes(("xMoney account:" + name.lowercase()).toByteArray(Charsets.UTF_8)).toString()
            assertEquals(expected, NameUuid.of(name).toString())
        }
        assertEquals(3, NameUuid.version(NameUuid.of("x")))
    }

    @Test
    fun hooksCanRefuse() {
        val steve = ledger.open(player(), "Steve", 10 * one)
        ledger.hooks = object : LedgerHooks {
            override fun refuse(account: PlayerRef, change: Long, cause: Cause, other: PlayerRef?) = if (change < 0) "no" else null
            override fun saved(entries: List<SavedChange>) = Unit
        }
        assertEquals(Status.CANCELLED, ledger.withdraw(steve, one, CAUSE).await().status)
        assertEquals(Status.SUCCESS, ledger.deposit(steve, one, CAUSE).await().status)
    }

    @Test
    fun theSavedHookHearsEachChangeOnceItsSaved() {
        val saved = java.util.concurrent.CopyOnWriteArrayList<SavedChange>()
        ledger.hooks = object : LedgerHooks {
            override fun refuse(account: PlayerRef, change: Long, cause: Cause, other: PlayerRef?): String? = null
            override fun saved(entries: List<SavedChange>) {
                saved += entries
            }
        }
        val a = ledger.open(player(), "A", 0)
        val b = ledger.open(player(), "B", 0)
        ledger.deposit(a, 10 * one, CAUSE)
        ledger.transfer(a, b, 4 * one, CAUSE)
        ledger.flush().await()
        assertEquals(listOf(10 * one, -4 * one, 4 * one), saved.map { it.change })
        assertEquals(listOf(10 * one, 6 * one, 4 * one), saved.map { it.balance })
    }

    @Test
    fun oldLedgerRowsArePrunedSliceBySliceAndBalancesStay() {
        val steve = ledger.open(player(), "Steve", 0)
        repeat(30) { ledger.deposit(steve, one, CAUSE) }
        ledger.flush().await()
        val later = System.currentTimeMillis() + 10_000
        repeat(5) { ledger.deposit(steve, one, CAUSE) }
        ledger.flush().await()
        assertEquals(0, ledger.prune(System.currentTimeMillis() - 60_000).await())
        val deleted = ledger.prune(later).await()
        assertEquals(35, deleted)
        restart()
        assertEquals(35 * one, ledger.accounts[steve.id]!!.balance)
    }

    @Test
    fun forgettingAPlayerDeletesTheirRows() {
        val steve = ledger.open(player(), "Steve", 5 * one)
        ledger.deposit(steve, one, CAUSE).await()
        ledger.flush().await()
        assertTrue(ledger.forget(steve.id).await().ok() >= 3)
        assertNull(ledger.accounts[steve.id])
        assertEquals(Status.INSUFFICIENT_FUNDS, ledger.withdraw(steve, one, CAUSE).await().status)
        restart()
        assertNull(ledger.accounts[steve.id])
        assertEquals(emptyMap(), Bench.stored(database))
    }

    @Test
    fun anImportSetsBalancesAsImports() {
        val steve = ledger.open(player(), "Steve", 0)
        val stranger = ledger.account(player(), "Stranger", true)
        assertEquals(2, ledger.setAll(listOf(steve to 7 * one, stranger to 3 * one), Cause(TransactionKind.IMPORT, "EssentialsX", "")).await())
        assertEquals(emptySet(), ledger.changedHere().await().ok())
        ledger.deposit(steve, one, CAUSE).await()
        assertEquals(setOf(steve.id), ledger.changedHere().await().ok())
    }

    @Test
    fun aFailedWriteIsSaidAndBalancesInMemoryStay() {
        var settled = false
        // A statement the database refuses (the table is gone): logged, not thrown.
        database.write(listOf(database.sql.raw("DROP TABLE {p}transactions"))).await()
        val steve = ledger.open(player(), "Steve", 0)
        ledger.deposit(steve, one, CAUSE).onSettled { _, _ -> settled = true }
        ledger.flush().await()
        assertTrue(settled)
        assertEquals(one, steve.balance)
        assertTrue(ledger.problem != null)
        assertTrue(platform.log.lines.any { "Couldn't save" in it.message }, platform.log.lines.toString())
    }
}
