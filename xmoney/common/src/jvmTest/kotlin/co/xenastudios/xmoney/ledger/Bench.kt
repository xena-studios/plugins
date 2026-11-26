package co.xenastudios.xmoney.ledger

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.db.ChangeFeed
import co.xenastudios.core.common.db.Database
import co.xenastudios.core.common.db.DatabaseSettings
import co.xenastudios.core.common.db.NetworkSettings
import co.xenastudios.core.common.db.SqlValue
import co.xenastudios.core.common.db.SqlVersion
import co.xenastudios.core.common.db.migrate
import co.xenastudios.core.common.platform.Capability
import co.xenastudios.core.common.platform.Platform
import co.xenastudios.core.common.platform.SystemClock
import co.xenastudios.core.common.platform.Threading
import co.xenastudios.core.jvm.db.Drivers
import co.xenastudios.core.jvm.db.JdbcDatabase
import co.xenastudios.core.jvm.db.JdbcLimits
import co.xenastudios.core.jvm.scheduler.ProxyScheduler
import co.xenastudios.core.testing.MemoryStorage
import co.xenastudios.core.testing.RecordingLog
import co.xenastudios.xmoney.api.TransactionKind
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLNonTransientConnectionException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/** A real-time platform for the ledger's tests: a scheduler thread of its own, the system clock. */
class TestPlatform : Platform {
    override val name: String = "Test"
    override val threading: Threading = Threading.PROXY
    override val capabilities: Set<Capability> = emptySet()
    override val log: RecordingLog = RecordingLog()
    override val scheduler: ProxyScheduler = ProxyScheduler("test", log) { false }
    override val clock = SystemClock
    override val storage = MemoryStorage()
    override val development: Boolean = true
}

/** The value, waiting up to [seconds] for it. */
fun <T> CompletableFuture<T>.await(seconds: Long = 15): T = get(seconds, TimeUnit.SECONDS)

fun <T, E> Outcome<T, E>.ok(): T = when (this) {
    is Outcome.Ok -> value
    is Outcome.Err -> throw AssertionError("expected a value, got $error")
}

val CAUSE = Cause(TransactionKind.PLUGIN, "Test", "")

/**
 * A connection wrapper that makes the database go away on cue: refuse new connections ([down]),
 * kill the connection at its next statement ([killNext]), lose a commit's answer after it landed
 * ([loseCommit]), or hold each statement up ([slow]).
 */
class Faults {
    @Volatile var down = false
    @Volatile var killNext = false
    @Volatile var loseCommit = false
    @Volatile var slow = 0L
    @Volatile var commits = 0

    fun wrap(connect: () -> Connection): () -> Connection = {
        if (down) throw SQLNonTransientConnectionException("the database is down (test)", "08001")
        val real = connect()
        Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java), handler(real) { method, args ->
            when (method.name) {
                "prepareStatement" -> statement(real, method.invoke(real, *args.orEmpty()) as PreparedStatement)
                "commit" -> {
                    if (down) kill(real)
                    method.invoke(real)
                    commits++
                    if (loseCommit) {
                        loseCommit = false
                        kill(real)
                    }
                    null
                }
                else -> method.invoke(real, *args.orEmpty())
            }
        }) as Connection
    }

    private fun statement(real: Connection, statement: PreparedStatement): PreparedStatement =
        Proxy.newProxyInstance(PreparedStatement::class.java.classLoader, arrayOf(PreparedStatement::class.java), handler(statement) { method, args ->
            if (method.name.startsWith("execute")) {
                if (slow > 0) Thread.sleep(slow)
                if (down || killNext) {
                    killNext = false
                    kill(real)
                }
            }
            method.invoke(statement, *args.orEmpty())
        }) as PreparedStatement

    private fun kill(real: Connection): Nothing {
        real.close()
        throw SQLNonTransientConnectionException("the connection was killed (test)", "08006")
    }

    private fun handler(target: Any, call: (java.lang.reflect.Method, Array<Any?>?) -> Any?): InvocationHandler = InvocationHandler { _, method, args ->
        try {
            call(method, args)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }
}

object Bench {
    private val drivers = Drivers.serverOnly(Bench::class.java.classLoader)

    /** xMoney's tables on SQLite at [file], migrated. */
    fun sqlite(file: Path, platform: Platform = TestPlatform(), faults: Faults? = null, prefix: String = "xmoney_"): JdbcDatabase =
        open(DatabaseSettings(DatabaseSettings.Type.SQLITE, prefix), file, platform, faults)

    /** MariaDB or MySQL from `XR_MYSQL=host:port:database:user:password`, or `null` without it. */
    fun mysql(prefix: String, platform: Platform = TestPlatform(), faults: Faults? = null): JdbcDatabase? {
        val value = System.getenv("XR_MYSQL")?.takeIf { it.isNotBlank() } ?: return null
        val (host, port, database, user, password) = value.split(':', limit = 5)
        return open(DatabaseSettings(DatabaseSettings.Type.MYSQL, prefix, DatabaseSettings.MySql(host, port.toInt(), database, user, password)), java.nio.file.Paths.get("unused.db"), platform, faults)
    }

    private fun open(settings: DatabaseSettings, file: Path, platform: Platform, faults: Faults?): JdbcDatabase {
        val database = JdbcDatabase.openWith(
            settings, file, drivers, platform.log, "Test", JdbcLimits(maxBackoffMillis = 100), SqlVersion(0, 0, 0),
            wrap = { connect -> faults?.wrap(connect) ?: connect },
        ).ok()
        database.migrate(Tables.MIGRATIONS).await().ok()
        return database
    }

    /** A ledger on [database], with every account in it read into memory, and its change feed when [shared]. */
    fun ledger(database: Database, platform: Platform = TestPlatform(), shared: Boolean = false, max: Long = Long.MAX_VALUE / 4): Ledger {
        val feed = if (shared) ChangeFeed.openOnAny(database, NetworkSettings(true, 1.seconds), platform.scheduler, platform.clock, platform.log, kotlin.uuid.Uuid.random().toHexString().take(16)).await().ok().let(Feed::of) else null
        val ledger = Ledger(Accounts(), database, feed, shared, platform) { max }
        ledger.load(database.read { ledger.readAll(it) }.await().ok())
        if (shared) ledger.listen()
        return ledger
    }

    /** Every balance as stored. */
    fun stored(database: Database): Map<PlayerRef, Long> = database.read { session ->
        session.query(database.sql.raw("SELECT uuid, balance FROM {p}balances"))
    }.await().ok().associate { PlayerRef.parseOrNull((it.values[0] as SqlValue.Text).value)!! to it.long(1)!! }

    /** Every account's ledger rows added up. */
    fun ledgerSums(database: Database): Map<PlayerRef, Long> = database.read { session ->
        session.query(database.sql.raw("SELECT uuid, SUM(amount) FROM {p}transactions GROUP BY uuid"))
    }.await().ok().associate { PlayerRef.parseOrNull((it.values[0] as SqlValue.Text).value)!! to it.long(1)!! }

    /** Each balance is exactly its ledger rows added up, and each row's balance is the running total. */
    fun assertLedgerMatchesBalances(database: Database) {
        val stored = stored(database)
        val sums = ledgerSums(database)
        assertEquals(stored.filterValues { it != 0L }, sums.filterValues { it != 0L }, "the balances and the ledger disagree")
        val rows = database.read { session ->
            session.query(database.sql.raw("SELECT uuid, amount, balance FROM {p}transactions ORDER BY id"))
        }.await().ok()
        val running = HashMap<String, Long>()
        for (row in rows) {
            val id = (row.values[0] as SqlValue.Text).value
            val total = running.getOrDefault(id, 0L) + row.long(1)!!
            running[id] = total
            assertEquals(total, row.long(2), "a ledger row's balance isn't the running total for $id")
        }
    }
}
