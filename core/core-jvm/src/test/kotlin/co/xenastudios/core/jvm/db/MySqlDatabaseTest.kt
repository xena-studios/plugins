package co.xenastudios.core.jvm.db

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.db.DbError
import co.xenastudios.core.common.db.Dialect
import co.xenastudios.core.common.db.Migrations
import co.xenastudios.core.common.db.SqlVersion
import co.xenastudios.core.common.db.executeAll
import co.xenastudios.core.common.db.values
import co.xenastudios.core.common.db.write
import co.xenastudios.core.testing.RecordingLog
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import org.junit.jupiter.api.Assumptions.assumeTrue

/** The SQL MySQL and MariaDB users run, on a real one: `XR_MYSQL=host:port:database:user:password`, as CI sets it. */
class MySqlDatabaseTest {
    private val prefix = "t" + Uuid.random().toHexString().take(8) + "_"
    private val opened = ArrayList<JdbcDatabase>()

    private fun database(chaos: Chaos? = null, migrate: Boolean = true): JdbcDatabase {
        val settings = MySqlFixture.settings(prefix)
        assumeTrue(settings != null, "XR_MYSQL isn't set")
        val database = JdbcDatabase.openWith(settings!!, Files.createTempDirectory("unused").resolve("x.db"), serverDrivers, RecordingLog(),
            "Test", JdbcLimits(maxBackoffMillis = 50), SqlVersion(0, 0, 0),
            wrap = { connect -> if (chaos == null) connect else ({ chaos.wrap(connect()) }) }).ok()
        opened += database
        if (migrate) database.session { Migrations.run(it, database.sql, MIGRATIONS) }.await().ok()
        return database
    }

    @AfterTest
    fun cleanUp() {
        MySqlFixture.drop(opened.firstOrNull())
        opened.forEach { it.close().await() }
    }

    private fun account(player: Uuid, name: String, balance: Double) = values(Accounts) {
        it[Accounts.player] = player
        it[Accounts.name] = name
        it[Accounts.balance] = balance
    }

    @Test
    fun theLostCommitLookupWaitsForACommitStillBeingMade() {
        val database = database()
        val settings = MySqlFixture.settings(prefix)!!
        val driver = serverDrivers.mysql().first().ok().driver
        val properties = java.util.Properties().apply {
            setProperty("user", settings.mysql.username)
            setProperty("password", settings.mysql.password)
        }
        fun connect() = driver.connect("jdbc:mysql://${settings.mysql.host}:${settings.mysql.port}/${settings.mysql.database}", properties)
        val lookup = database.markerQuery()
        // A commit the server is still making: the writer's marker changed, not yet committed.
        val committing = connect()
        val reading = connect()
        try {
            committing.autoCommit = false
            committing.prepareStatement("UPDATE ${prefix}meta SET value = '7' WHERE name = ?").use {
                it.setString(1, (lookup.args.single() as co.xenastudios.core.common.db.SqlValue.Text).value)
                assertEquals(1, it.executeUpdate())
            }
            val seen = java.util.concurrent.CompletableFuture.supplyAsync {
                reading.prepareStatement(lookup.sql).use {
                    it.setString(1, (lookup.args.single() as co.xenastudios.core.common.db.SqlValue.Text).value)
                    it.executeQuery().use { rows -> rows.next(); rows.getString(1) }
                }
            }
            Thread.sleep(500)
            assertTrue(!seen.isDone, "the lookup read past a commit still being made: ${if (seen.isDone) seen.get() else ""}")
            committing.commit()
            assertEquals("7", seen.get(10, java.util.concurrent.TimeUnit.SECONDS))
        } finally {
            committing.close()
            reading.close()
        }
    }

    @Test
    fun aStepThatGotPartwayRunsAgain() {
        val database = database(migrate = false)
        assertEquals(Dialect.MySql, database.dialect)
        // As if step 1 failed after MySQL committed its table and index: both are there, the version isn't.
        database.session { it.executeAll(database.sql.createTable(Accounts)) }.await().ok()
        assertEquals(2, database.session { Migrations.run(it, database.sql, MIGRATIONS) }.await().ok())
        assertEquals(2, database.session { Migrations.run(it, database.sql, MIGRATIONS) }.await().ok())
    }

    @Test
    fun upsertsBatchesAndARefusedWrite() {
        val database = database()
        val steve = Uuid.random()
        val alex = Uuid.random()
        val hold = CountDownLatch(1)
        database.session { hold.await(); Outcome.Ok(Unit) }
        val writes = listOf(
            database.write(database.sql.upsert(account(steve, "Steve", 1.0))),
            database.write(database.sql.upsert(account(steve, "Steve", 7.5))),
            database.write(database.sql.insert(account(alex, "Steve", 2.0))),
            database.write(database.sql.upsert(account(alex, "Alex", 3.0))),
        ) + (1..200).map { n -> database.write(listOf(database.sql.insert(values(Ledger) { it[Ledger.n] = n }))) }
        hold.countDown()
        assertEquals(DbError.Kind.CONSTRAINT, writes[2].await().kind())
        (writes - writes[2]).forEach { assertEquals(Outcome.Ok(Unit), it.await()) }
        val balances = database.read { it.query(database.sql.select(Accounts)) }.await().ok().associate { it[Accounts.name] to it[Accounts.balance] }
        assertEquals(mapOf("Steve" to 7.5, "Alex" to 3.0), balances)
        val ids = database.session { s -> s.insertForId(database.sql.insert(values(Ledger) { it[Ledger.n] = 0 })) }.await().ok()
        assertEquals(201L, ids)
    }

    @Test
    fun aKilledConnectionLosesNothingAcknowledgedAndRepeatsNothing() {
        val chaos = Chaos()
        val database = database(chaos)
        val writes = (0 until 300).map { n ->
            when (n % 50) {
                10 -> chaos.statementsLeft.set(3)
                25 -> chaos.onCommit = Chaos.When.AFTER
                40 -> chaos.onCommit = Chaos.When.BEFORE
            }
            database.write(listOf(database.sql.insert(values(Ledger) { it[Ledger.n] = n }))).also { if (n % 10 == 0) it.await() }
        }
        writes.forEach { assertEquals(Outcome.Ok(Unit), it.await()) }
        assertTrue(chaos.kills.get() >= 5, "killed ${chaos.kills.get()} times")
        val rows = database.read { it.query(database.sql.select(Ledger)) }.await().ok().groupingBy { it[Ledger.n] }.eachCount()
        assertEquals((0 until 300).toSet(), rows.keys)
        assertEquals(emptyMap(), rows.filterValues { it != 1 })
    }
}
