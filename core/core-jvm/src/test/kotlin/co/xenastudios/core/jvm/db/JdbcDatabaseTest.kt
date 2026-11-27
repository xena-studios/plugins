package co.xenastudios.core.jvm.db

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.db.DatabaseSettings
import co.xenastudios.core.common.db.DbError
import co.xenastudios.core.common.db.Dialect
import co.xenastudios.core.common.db.Migrations
import co.xenastudios.core.common.db.SqlRecordStore
import co.xenastudios.core.common.db.SqlValue
import co.xenastudios.core.common.db.SqlVersion
import co.xenastudios.core.common.db.ascending
import co.xenastudios.core.common.db.eq
import co.xenastudios.core.common.db.executeAll
import co.xenastudios.core.common.db.migrate
import co.xenastudios.core.common.db.queryOne
import co.xenastudios.core.common.db.values
import co.xenastudios.core.common.db.write
import co.xenastudios.core.common.storage.RecordType
import co.xenastudios.core.common.db.Row
import co.xenastudios.core.common.db.Values
import co.xenastudios.core.testing.RecordingLog
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CountDownLatch
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class JdbcDatabaseTest {
    private val folder: Path = Files.createTempDirectory("xena-db")
    private val file = folder.resolve("data/test.db")
    private val open = ArrayList<JdbcDatabase>()
    private val steve = Uuid.parse("8667ba71-b85a-4004-af54-457a9734eed7")
    private val alex = Uuid.parse("ec561538-f3fd-461d-aff5-086b22154bce")

    private fun database(log: RecordingLog = RecordingLog(), limits: JdbcLimits = JdbcLimits(maxBackoffMillis = 200), chaos: Chaos? = null) =
        DbFixtures.sqlite(file, log, limits, chaos).also { open += it }

    @AfterTest
    fun close() {
        open.forEach { it.close().await() }
        folder.toFile().deleteRecursively()
    }

    private fun account(player: Uuid, name: String, balance: Double) = values(Accounts) {
        it[Accounts.player] = player
        it[Accounts.name] = name
        it[Accounts.balance] = balance
    }

    private fun balances(database: JdbcDatabase): Map<String, Double> = database.read { session ->
        session.query(database.sql.select(Accounts, order = listOf(Accounts.name.ascending)))
    }.await().ok().associate { it[Accounts.name] to it[Accounts.balance] }

    @Test
    fun opensSqliteInWalAndMigratesOnce() {
        val log = RecordingLog()
        val database = database(log)
        assertTrue(database.dialect is Dialect.Sqlite)
        assertTrue(log.lines.single().message.startsWith("Using the SQLite database test.db: SQLite 3."))
        val mode = database.read { it.queryOne(database.sql.raw("PRAGMA journal_mode")) { row -> row.values[0] } }.await().ok()
        assertEquals(SqlValue.Text("wal"), mode)
        // Running the migrations again does nothing; a newer database is refused.
        assertEquals(2, database.migrate(MIGRATIONS).await().ok())
        val newer = database.migrate(MIGRATIONS.take(1)).await()
        assertEquals(DbError.Kind.NEWER_SCHEMA, newer.kind())
    }

    @Test
    fun closingWaitsForTheReadersSoNoConnectionKeepsTheFiles() {
        val database = database()
        database.write(database.sql.upsert(account(steve, "Steve", 10.0))).await().ok()
        // Reads on the reader threads, each opening its own connection.
        repeat(4) { assertEquals(1, balances(database).size) }
        database.close().await()
        // SQLite removes the -wal and -shm files when its last connection closes: with every
        // connection closed by the time close() answers, they're gone, and nothing writes
        // them again afterwards.
        val wal = file.resolveSibling("test.db-wal")
        val shm = file.resolveSibling("test.db-shm")
        assertTrue(!Files.exists(wal) && !Files.exists(shm), "a connection was still open after close()")
        Thread.sleep(200)
        assertTrue(!Files.exists(wal) && !Files.exists(shm), "a connection closed after close() answered")
    }

    @Test
    fun writesLandInOrderAndUpsertsReplace() {
        val database = database()
        database.write(database.sql.upsert(account(steve, "Steve", 10.0))).await().ok()
        database.write(database.sql.upsert(account(steve, "Steve", 25.0)) + database.sql.upsert(account(alex, "Alex", 5.0))).await().ok()
        assertEquals(mapOf("Alex" to 5.0, "Steve" to 25.0), balances(database))
        val ignored = database.write(database.sql.insertIgnore(account(steve, "Other", 99.0))).await()
        assertEquals(Outcome.Ok(Unit), ignored)
        assertEquals(25.0, balances(database)["Steve"])
    }

    @Test
    fun theTwoStatementUpsertWorksOnRealSqlite() {
        // As on Spigot 1.8.8's SQLite 3.7.2: the same database, with statements written for it.
        val real = database()
        val old = JdbcDatabase.prepare(
            JdbcDatabase(Dialect.Sqlite(SqlVersion(3, 7, 2)), "t_", "old", { org.sqlite.JDBC().connect("jdbc:sqlite:$file", java.util.Properties()) }, RecordingLog(), JdbcLimits(), "Old"),
            "old", RecordingLog(),
        ).ok().also { open += it }
        assertEquals(2, old.sql.upsert(account(steve, "Steve", 1.0)).size)
        old.write(old.sql.upsert(account(steve, "Steve", 1.0))).await().ok()
        old.write(old.sql.upsert(account(steve, "Steve", 2.0))).await().ok()
        assertEquals(mapOf("Steve" to 2.0), balances(real))
    }

    @Test
    fun aBurstOfWritesIsOneTransaction() {
        val log = RecordingLog()
        val database = database(log)
        val hold = CountDownLatch(1)
        // Holds the writer so the burst queues up behind it.
        database.session { hold.await(); Outcome.Ok(Unit) }
        val writes = (1..500).map { n -> database.write(database.sql.insert(values(Ledger) { it[Ledger.n] = n })) }
        hold.countDown()
        writes.forEach { it.await().ok() }
        // One batch: the writer's marker moved once for all 500.
        val marker = database.read { it.query(database.sql.select(Migrations.Meta)) }.await().ok()
            .single { it[Migrations.Meta.key].startsWith("writer:") }
        assertEquals("1", marker[Migrations.Meta.value])
        assertEquals(500L, count(database))
    }

    @Test
    fun aWriteTheDatabaseRefusesFailsAloneAndTheRestOfItsBatchLands() {
        val log = RecordingLog()
        val database = database(log)
        val hold = CountDownLatch(1)
        database.session { hold.await(); Outcome.Ok(Unit) }
        val first = database.write(database.sql.insert(account(steve, "Steve", 1.0)))
        // The same unique name: the database refuses it.
        val clash = database.write(database.sql.insert(account(alex, "Steve", 2.0)))
        val last = database.write(database.sql.insert(account(alex, "Alex", 3.0)))
        hold.countDown()
        assertEquals(Outcome.Ok(Unit), first.await())
        assertEquals(DbError.Kind.CONSTRAINT, clash.await().kind())
        assertEquals(Outcome.Ok(Unit), last.await())
        assertEquals(mapOf("Alex" to 3.0, "Steve" to 1.0), balances(database))
        assertTrue(log.lines.any { it.message.startsWith("the SQLite database test.db refused a write") })
    }

    @Test
    fun pastItsBoundsTheQueueWarnsThenRefuses() {
        val log = RecordingLog()
        val database = database(log, JdbcLimits(warnAt = 3, capacity = 5))
        val hold = CountDownLatch(1)
        database.session { hold.await(); Outcome.Ok(Unit) }
        // Give the writer time to take the held job off the queue.
        while (database.waiting > 0) Thread.sleep(5)
        val writes = (1..7).map { n -> database.write(database.sql.insert(values(Ledger) { it[Ledger.n] = n })) }
        assertEquals(5, database.waiting)
        assertEquals(2L, database.refused)
        assertEquals(DbError.Kind.OVERLOADED, writes[6].await().kind())
        assertEquals(2, log.lines.count { it.message.contains("writes are waiting") })
        hold.countDown()
        writes.take(5).forEach { assertEquals(Outcome.Ok(Unit), it.await()) }
        assertEquals(5L, count(database))
    }

    @Test
    fun aTransactionThatReturnsAnErrorIsRolledBack() {
        val database = database()
        database.write(database.sql.insert(account(steve, "Steve", 10.0))).await().ok()
        val withdraw = { amount: Double ->
            database.transaction { tx ->
                val balance = tx.queryOne(database.sql.select(Accounts, Accounts.player eq steve)) { it[Accounts.balance] }.ok()!!
                tx.execute(database.sql.update(values(Accounts) { it[Accounts.balance] = balance - amount }, Accounts.player eq steve))
                if (balance < amount) Outcome.Err(DbError(DbError.Kind.CONSTRAINT, "not enough")) else Outcome.Ok(balance - amount)
            }.await()
        }
        assertEquals(Outcome.Ok(4.0), withdraw(6.0))
        assertEquals(DbError.Kind.CONSTRAINT, withdraw(6.0).kind())
        assertEquals(4.0, balances(database)["Steve"])
        // Work that throws is rolled back and reported, and the writer carries on.
        val thrown = database.transaction<Unit> { tx ->
            tx.executeAll(listOf(database.sql.delete(Accounts, Accounts.player eq steve)))
            error("a bug")
        }.await()
        assertEquals(DbError.Kind.OTHER, thrown.kind())
        assertEquals(4.0, balances(database)["Steve"])
    }

    @Test
    fun aValueThatCantBeReadIsCorrupt() {
        val database = database()
        database.write(database.sql.raw("INSERT INTO t_accounts (player, balance, name) VALUES (?, ?, ?)",
            SqlValue.Text("not a uuid"), SqlValue.Real(1.0), SqlValue.Text("Odd"))).await().ok()
        val read = database.read { it.query(database.sql.select(Accounts)).let { rows -> Outcome.Ok(rows.ok().map { r -> r[Accounts.player] }) } }.await()
        assertEquals(DbError.Kind.CORRUPT, read.kind())
    }

    @Test
    fun rowIdsComeBackFromInserts() {
        val database = database()
        val ids = database.session { s -> Outcome.Ok((1..3).map { n -> s.insertForId(database.sql.insert(values(Ledger) { it[Ledger.n] = n })).ok() }) }
            .await().ok()
        assertEquals(listOf(1L, 2L, 3L), ids)
    }

    @Test
    fun readersNeverWrite() {
        val database = database()
        val write = database.read { it.execute(database.sql.insert(values(Ledger) { row -> row[Ledger.n] = 1 })) }.await()
        assertTrue(write is Outcome.Err)
        assertEquals(0L, count(database))
    }

    @Test
    fun closingFinishesWhatsQueuedThenRefuses() {
        val database = database()
        val writes = (1..50).map { n -> database.write(database.sql.insert(values(Ledger) { it[Ledger.n] = n })) }
        database.close().await()
        writes.forEach { assertEquals(Outcome.Ok(Unit), it.await()) }
        assertEquals(DbError.Kind.CLOSED, database.write(database.sql.delete(Ledger, Ledger.n eq 1)).await().kind())
        assertEquals(DbError.Kind.CLOSED, database.read { Outcome.Ok(1) }.await().kind())
        val again = database()
        assertEquals(50L, count(again))
        // A clean close takes its writer's marker with it: only the new writer's is there.
        val keys = again.read { it.query(again.sql.select(Migrations.Meta)) }.await().ok().map { it[Migrations.Meta.key] }
        assertEquals(1, keys.count { it.startsWith("writer:") })
    }

    @Test
    fun aRecordStoreOnTheDatabase() {
        val database = database()
        val store = SqlRecordStore(database, AccountRecords)
        store.put(Account(steve, "Steve", 3.0)).await().ok()
        store.put(Account(steve, "Steve", 4.0)).await().ok()
        assertEquals(Account(steve, "Steve", 4.0), store.get(steve).await().ok())
        assertEquals(1, store.all().await().ok().size)
        store.remove(steve).await().ok()
        assertNull(store.get(steve).await().ok())
    }

    @Test
    fun aServerWithoutADriverIsToldWhichToInstall() {
        val none = URLClassLoader(arrayOf(), ClassLoader.getSystemClassLoader().parent)
        val result = JdbcDatabase.open(DatabaseSettings(prefix = "t_"), file, Drivers.serverOnly(none), RecordingLog())
        assertEquals(DbError.Kind.NO_DRIVER, result.kind())
        assertTrue((result as Outcome.Err).error.detail.contains("install sqlite-jdbc"))
    }

    @Test
    fun aDriverIsFetchedWhenTheServerHasNoneOrAnOldOne() {
        val jar = Paths.get(org.sqlite.JDBC::class.java.protectionDomain.codeSource.location.toURI())
        val fetched = ArrayList<DriverLibrary>()
        val fetcher = DriverFetcher { library -> fetched += library; Outcome.Ok(jar) }
        val none = URLClassLoader(arrayOf(), ClassLoader.getSystemClassLoader().parent)
        val log = RecordingLog()
        val database = DbFixtures.sqlite(file, log, drivers = Drivers(none, fetcher)).also { open += it }
        assertEquals(listOf(DriverLibrary.SQLITE), fetched)
        assertTrue(log.lines.single().message.contains("downloaded org.xerial:sqlite-jdbc"))
        database.write(database.sql.insert(values(Ledger) { it[Ledger.n] = 1 })).await().ok()

        // The server has one, but it's older than the core needs: the fetched one is tried next.
        val tooOld = JdbcDatabase.openWith(DatabaseSettings(prefix = "t_"), folder.resolve("other.db"), Drivers(javaClass.classLoader, fetcher),
            RecordingLog(), "Test", JdbcLimits(), SqlVersion(99, 0, 0))
        val detail = (tooOld as Outcome.Err).error.detail
        assertEquals(DbError.Kind.NO_DRIVER, tooOld.error.kind)
        assertTrue(detail.contains("the server's SQLite is 3.") && detail.contains("downloaded org.xerial:sqlite-jdbc"), detail)
        assertEquals(2, fetched.size)
    }

    @Test
    fun theWriterSaysWhetherItCanReachTheDatabase() {
        val chaos = Chaos()
        val database = database(chaos = chaos)
        assertTrue(database.available)
        val held = Uuid.random()
        // The writer's connection dies, and new ones are refused: the write waits, and the database is down.
        chaos.down = true
        chaos.statementsLeft.set(1)
        val write = database.write(database.sql.upsert(account(held, "Held", 1.0)))
        val deadline = System.nanoTime() + 5_000_000_000
        while (database.available && System.nanoTime() < deadline) Thread.sleep(10)
        assertFalse(database.available, "the writer didn't notice the database went away")
        chaos.down = false
        assertEquals(Outcome.Ok(Unit), write.await())
        assertTrue(database.available)
    }

    @Test
    fun theLostCommitLookupLocksOnMysqlOnly() {
        val mysql = JdbcDatabase(Dialect.MySql, "p_", "MySQL", { throw java.sql.SQLException("unused") }, RecordingLog(), JdbcLimits(), "Test")
        assertTrue(mysql.markerQuery().sql.endsWith(" LOCK IN SHARE MODE"), mysql.markerQuery().sql)
        val sqlite = JdbcDatabase(Dialect.Sqlite.CURRENT, "p_", "SQLite", { throw java.sql.SQLException("unused") }, RecordingLog(), JdbcLimits(), "Test")
        assertFalse("LOCK" in sqlite.markerQuery().sql, sqlite.markerQuery().sql)
    }

    private fun count(database: JdbcDatabase): Long =
        database.read { it.queryOne(database.sql.count(Ledger)) { row -> row.long(0)!! } }.await().ok()!!

    data class Account(val player: Uuid, val name: String, val balance: Double)

    object AccountRecords : RecordType<Uuid, Account>(Accounts, Accounts.player) {
        override fun keyOf(value: Account) = value.player
        override fun write(value: Account, values: Values) {
            values[Accounts.player] = value.player
            values[Accounts.name] = value.name
            values[Accounts.balance] = value.balance
        }
        override fun read(row: Row) = Account(row[Accounts.player], row[Accounts.name], row[Accounts.balance])
    }

    @Test
    fun aDriverWithoutIsValidIsAskedWithASelect() {
        // Spigot 1.8.8's bundled SQLite driver predates JDBC 4: isValid throws AbstractMethodError.
        val real = java.sql.DriverManager.getConnection("jdbc:sqlite:" + folder.resolve("old-driver.db"))
        val old = java.lang.reflect.Proxy.newProxyInstance(
            javaClass.classLoader, arrayOf(java.sql.Connection::class.java),
        ) { _, method, args ->
            if (method.name == "isValid") throw AbstractMethodError("org.sqlite.Conn.isValid(I)Z")
            try {
                method.invoke(real, *(args ?: emptyArray()))
            } catch (e: java.lang.reflect.InvocationTargetException) {
                throw e.targetException
            }
        } as java.sql.Connection
        assertTrue(JdbcDatabase.valid(old))
        real.close()
        assertFalse(JdbcDatabase.valid(old))
    }
}
