package co.xenastudios.core.jvm.db

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.db.DbError
import co.xenastudios.core.common.db.queryOne
import co.xenastudios.core.common.db.values
import co.xenastudios.core.common.db.write
import co.xenastudios.core.testing.RecordingLog
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Kills the database's connections under it, mid-batch and at the commit, and checks the one
 * promise the writer makes: a write reported done is in the database, once.
 */
class ChaosTest {
    private val folder = Files.createTempDirectory("xena-chaos")
    private val file = folder.resolve("chaos.db")

    @AfterTest
    fun cleanUp() {
        folder.toFile().deleteRecursively()
    }

    private fun insert(database: JdbcDatabase, n: Int) = database.write(database.sql.insert(values(Ledger) { it[Ledger.n] = n }))

    /** Every n in the ledger, with how many times each is there. */
    private fun ledger(database: JdbcDatabase): Map<Int, Int> = database.read { it.query(database.sql.select(Ledger)) }.await().ok()
        .groupingBy { it[Ledger.n] }.eachCount()

    @Test
    fun connectionsKilledMidBatchAndAtCommitLoseNothingAcknowledged() {
        val chaos = Chaos()
        val log = RecordingLog()
        val database = DbFixtures.sqlite(file, log, chaos = chaos)
        val acknowledged = Collections.synchronizedSet(HashSet<Int>())
        val writes = Collections.synchronizedList(ArrayList<CompletableFuture<Outcome<Unit, DbError>>>())
        val writing = AtomicBoolean(true)
        val monkey = thread(name = "chaos monkey") {
            val random = ThreadLocalRandom.current()
            while (writing.get()) {
                when (random.nextInt(3)) {
                    0 -> chaos.statementsLeft.set(random.nextInt(1, 60))
                    1 -> chaos.onCommit = Chaos.When.BEFORE
                    else -> chaos.onCommit = Chaos.When.AFTER
                }
                Thread.sleep(random.nextLong(1, 15))
            }
        }
        // Four threads write until there have been at least 30 kills, and 2,000 writes.
        val written = java.util.concurrent.atomic.AtomicInteger()
        val writers = (0 until 4).map { w ->
            thread(name = "writer $w") {
                var i = 0
                while ((i < 500 || chaos.kills.get() < 30) && i < 50_000) {
                    val n = w * 50_000 + i
                    writes += insert(database, n).whenComplete { result, _ -> if (result == Outcome.Ok(Unit)) acknowledged += n }
                    written.incrementAndGet()
                    if (i++ % 20 == 0) Thread.sleep(1)
                }
            }
        }
        writers.forEach { it.join() }
        writes.toList().forEach { it.await() }
        writing.set(false)
        monkey.join()
        chaos.statementsLeft.set(0)
        chaos.onCommit = null

        assertTrue(chaos.kills.get() >= 30, "the connection was killed ${chaos.kills.get()} times")
        // The database came back each time, so every write went through...
        assertEquals(written.get(), acknowledged.size)
        // ...and each is there exactly once: a commit whose answer was lost wasn't run again.
        val rows = ledger(database)
        assertEquals(acknowledged.toSet(), rows.keys)
        assertEquals(emptyMap(), rows.filterValues { it != 1 })
        assertTrue(log.lines.any { it.message.startsWith("Lost the SQLite database chaos.db") })
        database.close().await()
    }

    @Test
    fun aCommitWhoseAnswerIsLostIsFoundNotRepeated() {
        val chaos = Chaos()
        val database = DbFixtures.sqlite(file, chaos = chaos)
        chaos.onCommit = Chaos.When.AFTER
        assertEquals(Outcome.Ok(Unit), insert(database, 1).await())
        chaos.onCommit = Chaos.When.AFTER
        val paid = database.transaction { tx ->
            tx.execute(database.sql.insert(values(Ledger) { it[Ledger.n] = 2 })).let { if (it is Outcome.Err) it else Outcome.Ok("paid") }
        }.await()
        assertEquals(Outcome.Ok("paid"), paid)
        chaos.onCommit = Chaos.When.BEFORE
        assertEquals(Outcome.Ok(Unit), insert(database, 3).await())
        assertEquals(mapOf(1 to 1, 2 to 1, 3 to 1), ledger(database))
        assertEquals(3, chaos.kills.get())
        database.close().await()
    }

    @Test
    fun writesWaitWhileTheDatabaseIsDown() {
        val chaos = Chaos()
        val log = RecordingLog()
        val database = DbFixtures.sqlite(file, log, chaos = chaos)
        insert(database, 0).await().ok()
        chaos.down = true
        chaos.statementsLeft.set(1)
        val waiting = (1..20).map { insert(database, it) }
        Thread.sleep(300)
        assertTrue(waiting.none { it.isDone }, "nothing is reported written while the database is down")
        assertTrue(log.lines.any { it.message.startsWith("Lost the SQLite database chaos.db") })
        chaos.down = false
        waiting.forEach { assertEquals(Outcome.Ok(Unit), it.await()) }
        assertTrue(log.lines.any { it.message.startsWith("the SQLite database chaos.db is back") })
        assertEquals((0..20).toSet(), ledger(database).keys)
        database.close().await()
    }

    @Test
    fun closingWhileDownFailsWhatsLeftAfterTheWaitAndNothingElse() {
        val chaos = Chaos()
        val database = DbFixtures.sqlite(file, chaos = chaos, limits = JdbcLimits(closeWaitMillis = 300, maxBackoffMillis = 50))
        val done = (1..5).map { insert(database, it) }
        done.forEach { it.await().ok() }
        chaos.down = true
        chaos.statementsLeft.set(1)
        val stuck = (6..10).map { insert(database, it) }
        val started = System.nanoTime()
        database.close().await()
        assertTrue(System.nanoTime() - started < 5_000_000_000L, "close waits a bounded time")
        stuck.forEach { assertEquals(DbError.Kind.CLOSED, it.await().kind()) }
        chaos.down = false
        val again = DbFixtures.sqlite(file)
        assertEquals((1..5).toSet(), ledger(again).keys)
        assertEquals(5L, again.read { it.queryOne(again.sql.count(Ledger)) { r -> r.long(0)!! } }.await().ok())
        again.close().await()
    }
}
