package co.xenastudios.core.jvm.db

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.db.Change
import co.xenastudios.core.common.db.ChangeFeed
import co.xenastudios.core.common.db.DatabaseSettings
import co.xenastudios.core.common.db.DbError
import co.xenastudios.core.common.db.NetworkSettings
import co.xenastudios.core.common.db.SqlValue
import co.xenastudios.core.common.db.queryOne
import co.xenastudios.core.common.db.values
import co.xenastudios.core.common.db.write
import co.xenastudios.core.testing.FakePlatform
import java.nio.file.Files
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Two servers sharing one database, each with its own [JdbcDatabase], fake platform and feed:
 * the network protocol end to end. Run on SQLite (two databases on one file) and, with
 * `XR_MYSQL` set, on MySQL or MariaDB.
 */
abstract class ChangeFeedContract {
    protected abstract fun database(): JdbcDatabase

    private val opened = ArrayList<JdbcDatabase>()
    private val feeds = ArrayList<ChangeFeed>()

    protected open fun cleanUp(databases: List<JdbcDatabase>) = Unit

    @AfterTest
    fun closeAll() {
        feeds.forEach { it.close() }
        cleanUp(opened)
        opened.forEach { it.close().await() }
    }

    /** A server: its database, platform and feed, and what its subscribers heard. */
    inner class Server(val origin: String) {
        val platform = FakePlatform()
        val database = database().also { opened += it }
        val feed: ChangeFeed = ChangeFeed.openOnAny(database, NetworkSettings(enabled = true), platform.scheduler, platform.clock,
            platform.log, origin).await().ok().also { feeds += it }
        val heard: MutableList<Change> = Collections.synchronizedList(ArrayList())
        var resyncs = 0

        init {
            feed.subscribe("ban") { heard += it }
            feed.subscribe("pay") { heard += it }
            feed.onResync { resyncs++ }
        }

        fun publish(topic: String, payload: String) = database.write(feed.publishing(topic, payload)).await().ok()

        fun check() = feed.check().await()

        fun payloads() = heard.map { it.payload }

        fun rows(): Long = database.session { it.queryOne(database.sql.count(ChangeFeed.Changes)) { r -> r.long(0)!! } }.await().ok()!!
    }

    @Test
    fun othersChangesArriveInOrderAndItsOwnDont() {
        val a = Server("aaaa")
        val b = Server("bbbb")
        a.publish("ban", "steve")
        a.publish("pay", "alex")
        b.publish("ban", "notch")
        b.check()
        assertEquals(listOf("steve", "alex"), b.payloads())
        a.check()
        assertEquals(listOf("notch"), a.payloads())
        b.check()
        assertEquals(2, b.heard.size, "read once")
    }

    @Test
    fun changesFromBeforeAStartArentReplayed() {
        val a = Server("aaaa")
        a.publish("ban", "steve")
        val b = Server("bbbb")
        b.check()
        assertEquals(emptyList(), b.heard)
    }

    @Test
    fun aChangeThatRollsBackIsNeverHeardOf() {
        val a = Server("aaaa")
        val b = Server("bbbb")
        val account = values(Accounts) {
            it[Accounts.player] = Uuid.random()
            it[Accounts.name] = "Steve"
            it[Accounts.balance] = 1.0
        }
        a.database.write(listOf(a.database.sql.insert(account)) + a.feed.publishing("pay", "first")).await().ok()
        // The same account again: the insert fails, so the change published with it rolls back.
        val failed = a.database.write(a.feed.publishing("pay", "second") + a.database.sql.insert(account)).await()
        assertEquals(DbError.Kind.CONSTRAINT, failed.kind())
        b.check()
        assertEquals(listOf("first"), b.payloads())
    }

    @Test
    fun aLateCommitIsDeliveredWhenItLands() {
        val a = Server("aaaa")
        val b = Server("bbbb")
        a.publish("ban", "first")
        val first = a.database.read { it.queryOne(a.database.sql.raw("SELECT MAX(id) FROM {p}changes")) { r -> r.long(0)!! } }.await().ok()!!
        // Row first+2 commits before first+1, which took its id earlier.
        insertWithId(a, first + 2, "third")
        b.check()
        assertEquals(listOf("first", "third"), b.payloads())
        insertWithId(a, first + 1, "second")
        b.check()
        assertEquals(listOf("first", "third", "second"), b.payloads())
    }

    @Test
    fun aServerThatStoppedCheckingReadsAgainAndPrunesOldRows() {
        val a = Server("aaaa")
        val b = Server("bbbb")
        repeat(3) { a.publish("ban", "p$it") }
        b.check()
        assertEquals(0, b.resyncs)
        // Eleven minutes with no check: rows it never saw may be gone, so it reads again, and prunes.
        b.platform.scheduler.advance(11.minutes)
        b.check()
        assertEquals(1, b.resyncs)
        assertEquals(1L, b.rows(), "pruned to the newest row, which always stays")
    }

    private fun insertWithId(server: Server, id: Long, payload: String) {
        server.database.write(server.database.sql.raw("INSERT INTO {p}changes (id, origin, topic, payload, created) VALUES (?, ?, ?, ?, ?)",
            SqlValue.Integer(id), SqlValue.Text(server.origin), SqlValue.Text("ban"), SqlValue.Text(payload),
            SqlValue.Integer(server.platform.clock.now().toEpochMilliseconds()))).await().ok()
    }

}

class ChangeFeedTest : ChangeFeedContract() {
    private val folder = Files.createTempDirectory("xena-feed")

    override fun database(): JdbcDatabase = DbFixtures.sqlite(folder.resolve("shared.db"))

    @AfterTest
    fun deleteFolder() {
        folder.toFile().deleteRecursively()
    }
}

/** The same on a real MySQL or MariaDB: `XR_MYSQL=host:port:database:user:password`, as CI sets it. */
class ChangeFeedMySqlTest : ChangeFeedContract() {
    private val prefix = "t" + Uuid.random().toHexString().take(8) + "_"

    override fun database(): JdbcDatabase {
        val settings = MySqlFixture.settings(prefix)
        assumeTrue(settings != null, "XR_MYSQL isn't set")
        val database = JdbcDatabase.open(settings!!, Files.createTempDirectory("unused").resolve("x.db"), serverDrivers,
            co.xenastudios.core.testing.RecordingLog(), "Test", JdbcLimits(maxBackoffMillis = 200)).ok()
        database.session { co.xenastudios.core.common.db.Migrations.run(it, database.sql, MIGRATIONS) }.await().ok()
        return database
    }

    override fun cleanUp(databases: List<JdbcDatabase>) = MySqlFixture.drop(databases.firstOrNull())
}

object MySqlFixture {
    /** The settings `XR_MYSQL` gives, or `null` if it isn't set. */
    fun settings(prefix: String): DatabaseSettings? {
        val value = System.getenv("XR_MYSQL")?.takeIf { it.isNotBlank() } ?: return null
        val (host, port, database, user, password) = value.split(':', limit = 5)
        return DatabaseSettings(DatabaseSettings.Type.MYSQL, prefix, DatabaseSettings.MySql(host, port.toInt(), database, user, password))
    }

    /** Drops every table with the database's prefix, so runs don't meet. */
    fun drop(database: JdbcDatabase?) {
        database ?: return
        database.session { session ->
            val tables = session.query(database.sql.raw("SHOW TABLES LIKE '${database.sql.prefix}%'")).ok()
            tables.forEach { session.execute(database.sql.raw("DROP TABLE " + (it.values[0] as SqlValue.Text).value)) }
            Outcome.Ok(Unit)
        }.await()
    }
}
