package co.xenastudios.core.jvm.admin

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.pendingOf
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.admin.MigrationListener
import co.xenastudios.core.common.admin.StorageMigration
import co.xenastudios.core.common.admin.StorageSwitch
import co.xenastudios.core.common.db.Database
import co.xenastudios.core.common.db.DatabaseSettings
import co.xenastudios.core.common.db.DbError
import co.xenastudios.core.common.db.Migrations
import co.xenastudios.core.common.db.ascending
import co.xenastudios.core.common.db.values
import co.xenastudios.core.common.db.write
import co.xenastudios.core.common.platform.Capability
import co.xenastudios.core.common.platform.Platform
import co.xenastudios.core.common.platform.SystemClock
import co.xenastudios.core.common.platform.Threading
import co.xenastudios.core.common.scheduler.Scheduler
import co.xenastudios.core.common.scheduler.Task
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.jvm.db.Accounts
import co.xenastudios.core.jvm.db.DbFixtures
import co.xenastudios.core.jvm.db.JdbcDatabase
import co.xenastudios.core.jvm.db.JdbcLimits
import co.xenastudios.core.jvm.db.Ledger
import co.xenastudios.core.jvm.db.MIGRATIONS
import co.xenastudios.core.jvm.db.MySqlFixture
import co.xenastudios.core.jvm.db.await
import co.xenastudios.core.jvm.db.ok
import co.xenastudios.core.jvm.db.serverDrivers
import co.xenastudios.core.testing.MemoryStorage
import co.xenastudios.core.testing.RecordingLog
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.uuid.Uuid
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir

/**
 * `/<plugin> migrate` moving real rows: from SQLite to another SQLite file standing in for the
 * other type, and to MariaDB when `XR_MYSQL` is set, as CI sets it. The migration's own steps
 * run on real threads here, as they do on a server.
 */
class StorageMigrationJdbcTest {

    @TempDir
    lateinit var dir: Path

    private val opened = ArrayList<JdbcDatabase>()
    private val pool = Executors.newSingleThreadExecutor()

    /** Just enough of a platform: async work on a thread of its own, the real clock. */
    private val platform = object : Platform {
        override val name: String get() = "Test"
        override val threading: Threading get() = Threading.MAIN_THREAD
        override val capabilities: Set<Capability> get() = setOf(Capability.SQL)
        override val clock = SystemClock
        override val storage = MemoryStorage()
        override val log = RecordingLog()
        override val development: Boolean get() = false
        override val scheduler = object : Scheduler {
            override fun run(where: Where, delay: Duration, period: Duration?, retired: (() -> Unit)?, action: (Task) -> Unit): Task {
                val task = object : Task {
                    override val isCancelled: Boolean get() = false
                    override fun cancel() {}
                }
                pool.execute { action(task) }
                return task
            }

            override fun owns(where: Where): Boolean = false
        }
    }

    @AfterTest
    fun close() {
        MySqlFixture.drop(opened.lastOrNull { it.dialect.shared })
        opened.forEach { it.close().await() }
        pool.shutdown()
    }

    private fun sqlite(name: String): JdbcDatabase = DbFixtures.sqlite(dir.resolve(name), limits = JdbcLimits()).also { opened += it }

    private class Done : MigrationListener {
        val lines = mutableListOf<String>()
        val finished = CompletableFuture<List<String>>()
        override fun notEmpty(rows: Map<String, Long>) = end("not empty $rows")
        override fun started() { lines += "started" }
        override fun copied(table: String, rows: Long) { lines += "copied $rows of $table" }
        override fun finished(rows: Long) = end("finished $rows")
        override fun failed(reason: String) = end("failed: $reason")

        private fun end(line: String) {
            lines += line
            finished.complete(lines)
        }
    }

    private fun fill(database: Database, accounts: Int, ledger: Int, from: Int = 1) {
        database.write((from until from + accounts).flatMap { n ->
            database.sql.upsert(values(Accounts) {
                it[Accounts.player] = Uuid.random()
                it[Accounts.name] = "player$n"
                it[Accounts.balance] = n * 1.5
            })
        }).await().ok()
        database.write((1..ledger).map { n -> database.sql.insert(values(Ledger) { it[Ledger.n] = n }) }).await().ok()
    }

    private fun migrate(source: JdbcDatabase, target: () -> JdbcDatabase, force: Boolean = false): Pair<List<String>, List<DatabaseSettings.Type>> {
        val switched = ArrayList<DatabaseSettings.Type>()
        val storage = object : StorageSwitch {
            override val currentType: DatabaseSettings.Type get() = DatabaseSettings.Type.SQLITE
            override val current: Database get() = source
            override fun open(type: DatabaseSettings.Type): Pending<Outcome<Database, DbError>> = pendingOf(Outcome.Ok(target()))
            override fun switchTo(type: DatabaseSettings.Type): Pending<Outcome<Unit, String>> {
                switched += type
                return pendingOf(Outcome.Ok(Unit))
            }
        }
        val done = Done()
        StorageMigration(platform, storage, listOf(Accounts, Ledger), MIGRATIONS, batch = 100).run(DatabaseSettings.Type.MYSQL, force, done)
        return done.finished.get(30, TimeUnit.SECONDS) to switched
    }

    /** Every row of both tables, in key order, as text, to compare two databases. */
    private fun contents(database: Database): List<String> = database.read { session ->
        val accounts = session.query(database.sql.select(Accounts, order = listOf(Accounts.player.ascending))).ok()
            .map { "${it[Accounts.player]} ${it[Accounts.name]} ${it[Accounts.balance]}" }
        val ledger = session.query(database.sql.select(Ledger, order = listOf(Ledger.id.ascending))).ok()
            .map { "${it[Ledger.id]} ${it[Ledger.n]}" }
        Outcome.Ok(accounts + ledger)
    }.await().ok()

    @Test
    fun sqliteToAnotherSqliteFile() {
        val source = sqlite("source.db")
        fill(source, accounts = 250, ledger = 120)
        val target = dir.resolve("target.db")
        val (lines, switched) = migrate(source, { sqlite("target.db") })
        assertEquals(listOf("started", "copied 250 of accounts", "copied 120 of ledger", "finished 370"), lines)
        assertEquals(listOf(DatabaseSettings.Type.MYSQL), switched)

        // The target was closed before the switch; opened again, it has the same rows and schema.
        val reopened = DbFixtures.sqlite(target).also { opened += it }
        assertEquals(contents(source), contents(reopened))
        assertEquals(2, reopened.session { Migrations.version(it, reopened.sql) }.await().ok())

        // A second run finds the target full, and leaves it as it is unless forced.
        val (again, notSwitched) = migrate(source, { sqlite("target.db") })
        assertEquals(listOf("not empty {accounts=250, ledger=120}"), again)
        assertEquals(emptyList(), notSwitched)
        fill(source, accounts = 1, ledger = 0, from = 1000)
        val (forced, _) = migrate(source, { sqlite("target.db") }, force = true)
        assertEquals("finished 371", forced.last())
        assertEquals(contents(source), contents(DbFixtures.sqlite(target).also { opened += it }))
    }

    @Test
    fun sqliteToMariaDb() {
        val prefix = "m" + Uuid.random().toHexString().take(8) + "_"
        val settings = MySqlFixture.settings(prefix)
        assumeTrue(settings != null, "XR_MYSQL isn't set")
        val source = sqlite("source.db")
        fill(source, accounts = 300, ledger = 150)
        val target = {
            JdbcDatabase.open(settings!!, dir.resolve("unused.db"), serverDrivers, RecordingLog(), "Test").ok().also { opened += it }
        }
        val (lines, switched) = migrate(source, target)
        assertEquals(listOf("started", "copied 300 of accounts", "copied 150 of ledger", "finished 450"), lines)
        assertEquals(listOf(DatabaseSettings.Type.MYSQL), switched)
        val mysql = target()
        assertEquals(contents(source), contents(mysql))
    }
}
