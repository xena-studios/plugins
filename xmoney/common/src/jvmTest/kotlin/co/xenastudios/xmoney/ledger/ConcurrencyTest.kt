package co.xenastudios.xmoney.ledger

import co.xenastudios.core.api.PlayerRef
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * 16 threads paying each other at random, for one server: every unit is conserved, no balance
 * goes below zero, and once saved the database matches memory and its own ledger exactly.
 */
class ConcurrencyTest {
    private val folder = Files.createTempDirectory("xmoney-concurrency")
    private val platform = TestPlatform()
    private val database = Bench.sqlite(folder.resolve("money.db"), platform)

    @AfterTest
    fun stop() {
        database.close().await()
        platform.scheduler.close()
        folder.toFile().deleteRecursively()
    }

    @Test
    fun transfersConserveMoney() {
        val ledger = Bench.ledger(database, platform)
        val start = 1_000 * 10_000L
        val players = (1..20).map { ledger.open(PlayerRef.of(Uuid.random()), "P$it", start) }
        val threads = 16
        val done = CountDownLatch(threads)
        repeat(threads) {
            Thread {
                val random = ThreadLocalRandom.current()
                repeat(2_000) {
                    val from = players[random.nextInt(players.size)]
                    val to = players[random.nextInt(players.size)]
                    ledger.transfer(from, to, 1 + random.nextLong(100 * 10_000L), CAUSE)
                }
                done.countDown()
            }.start()
        }
        assertTrue(done.await(120, TimeUnit.SECONDS))
        assertEquals(players.size * start, players.sumOf { it.balance }, "money was made or lost in memory")
        assertTrue(players.all { it.balance >= 0 })
        assertEquals(players.size * start, ledger.accounts.total())
        ledger.flush().await(60)
        assertEquals(players.associate { it.id to it.balance }, Bench.stored(database), "the database doesn't match memory")
        Bench.assertLedgerMatchesBalances(database)
    }
}
