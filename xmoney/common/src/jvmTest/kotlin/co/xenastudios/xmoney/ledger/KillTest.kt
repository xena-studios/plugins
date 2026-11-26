package co.xenastudios.xmoney.ledger

import co.xenastudios.core.api.PlayerRef
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * A server killed mid-write (SIGKILL: no shutdown, no flush) never leaves the database
 * disagreeing with itself. A separate JVM pays players back and forth as fast as it can; it's
 * killed while the writer is busy, three times over on the same file, and after each the balances
 * are exactly their ledger rows added up, none is below zero, and the total is what it started
 * as, since only transfers ran.
 */
class KillTest {
    private val folder = Files.createTempDirectory("xmoney-kill")

    @AfterTest
    fun clean() {
        folder.toFile().deleteRecursively()
    }

    @Test
    fun killedMidWriteTheLedgerAndBalancesAgree() {
        val file = folder.resolve("money.db")
        val java = Paths.get(System.getProperty("java.home"), "bin", "java").toString()
        repeat(3) {
            val hammer = ProcessBuilder(java, "-cp", System.getProperty("xmoney.testClasspath"), Hammer::class.java.name, file.toString())
                .redirectErrorStream(true).start()
            BufferedReader(InputStreamReader(hammer.inputStream, StandardCharsets.UTF_8)).use { out ->
                val deadline = System.currentTimeMillis() + 60_000
                var ready = false
                while (!ready && System.currentTimeMillis() < deadline) {
                    val line = out.readLine() ?: break
                    ready = line == "READY"
                }
                assertTrue(ready, "the hammer never got going")
                Thread.sleep(150L + ThreadLocalRandom.current().nextInt(300))
                hammer.destroyForcibly()
                assertTrue(hammer.waitFor(30, TimeUnit.SECONDS))
            }
            val platform = TestPlatform()
            val database = Bench.sqlite(file, platform)
            try {
                val stored = Bench.stored(database)
                assertEquals(PLAYERS, stored.size)
                assertEquals(PLAYERS * START, stored.values.sum(), "money was made or lost")
                assertTrue(stored.values.all { it >= 0 }, "a balance is below zero")
                Bench.assertLedgerMatchesBalances(database)
            } finally {
                database.close().await()
                platform.scheduler.close()
            }
        }
    }

    /** Runs in its own JVM: makes the players if the file is new, then pays them at random until it's killed. */
    object Hammer {
        @JvmStatic
        fun main(args: Array<String>) {
            val platform = TestPlatform()
            val database = Bench.sqlite(Paths.get(args[0]), platform)
            val ledger = Bench.ledger(database, platform)
            val saved = java.util.concurrent.atomic.AtomicLong()
            ledger.hooks = object : LedgerHooks {
                override fun refuse(account: PlayerRef, change: Long, cause: Cause, other: PlayerRef?): String? = null
                override fun saved(entries: List<SavedChange>) {
                    saved.addAndGet(entries.size.toLong())
                }
            }
            val players = ArrayList(ledger.accounts.all())
            if (players.isEmpty()) repeat(PLAYERS) { players += ledger.open(PlayerRef.of(Uuid.random()), "P$it", START) }
            repeat(8) {
                Thread {
                    val random = ThreadLocalRandom.current()
                    while (true) {
                        ledger.transfer(players[random.nextInt(players.size)], players[random.nextInt(players.size)], 1 + random.nextLong(50 * 10_000L), CAUSE)
                        while (ledger.waiting > 20_000) Thread.yield()
                    }
                }.apply { isDaemon = true }.start()
            }
            while (saved.get() < 5_000) Thread.sleep(5)
            println("READY")
            System.out.flush()
            Thread.sleep(Long.MAX_VALUE)
        }
    }

    companion object {
        const val PLAYERS = 30
        const val START = 1_000 * 10_000L
    }
}
