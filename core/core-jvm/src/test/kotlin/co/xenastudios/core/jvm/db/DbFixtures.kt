package co.xenastudios.core.jvm.db

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.db.ColumnType
import co.xenastudios.core.common.db.DatabaseSettings
import co.xenastudios.core.common.db.DbError
import co.xenastudios.core.common.db.Migration
import co.xenastudios.core.common.db.Table
import co.xenastudios.core.common.db.SqlVersion
import co.xenastudios.core.testing.RecordingLog
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLNonTransientConnectionException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.fail

object Accounts : Table("accounts") {
    val player = column("player", ColumnType.UUID)
    val balance = column("balance", ColumnType.DOUBLE)
    val name = column("name", ColumnType.text(16))

    init {
        primaryKey(player)
        index(name, unique = true)
    }
}

/** No key but the row id, so a write run twice shows up as two rows. */
object Ledger : Table("ledger") {
    val id = rowId()
    val n = column("n", ColumnType.INT)
}

val MIGRATIONS = listOf(
    Migration(1, "accounts") { it.createTable(Accounts) },
    Migration(2, "ledger") { it.createTable(Ledger) },
)

/** The value, waiting up to ten seconds for it. */
fun <T> CompletableFuture<T>.await(): T = get(10, TimeUnit.SECONDS)

fun <T> Outcome<T, DbError>.ok(): T = when (this) {
    is Outcome.Ok -> value
    is Outcome.Err -> fail("expected a value, got $error")
}

fun Outcome<*, DbError>.kind(): DbError.Kind? = (this as? Outcome.Err)?.error?.kind

/** The test class path's own SQLite driver, as if the server had it. */
val serverDrivers = Drivers.serverOnly(DbFixtures::class.java.classLoader)

object DbFixtures {
    /** A database on [file], migrated, with [chaos] between it and every connection. */
    fun sqlite(
        file: Path,
        log: RecordingLog = RecordingLog(),
        limits: JdbcLimits = JdbcLimits(maxBackoffMillis = 200),
        chaos: Chaos? = null,
        prefix: String = "t_",
        minimum: SqlVersion = co.xenastudios.core.common.db.Dialect.Sqlite.MINIMUM,
        drivers: Drivers = serverDrivers,
    ): JdbcDatabase {
        val database = JdbcDatabase.openWith(
            DatabaseSettings(prefix = prefix), file, drivers, log, "Test", limits, minimum,
            wrap = { connect -> if (chaos == null) connect else ({ chaos.wrap(connect()) }) },
        ).ok()
        database.session { co.xenastudios.core.common.db.Migrations.run(it, database.sql, MIGRATIONS) }.await().ok()
        return database
    }
}

/**
 * Stands between the database and its connections, and kills them on demand: the connection is
 * closed under the writer, as a dropped network or a killed MySQL thread would, and the call
 * fails as a driver's does when its connection is gone.
 */
class Chaos {
    /** Kill the connection after this many more statements run (0 = never). */
    val statementsLeft = AtomicInteger()

    /** Kill the connection on its next commit: before it reaches the database, or after. */
    @Volatile
    var onCommit: When? = null

    /** Refuse new connections, as a database that's down does. */
    @Volatile
    var down = false

    val kills = AtomicInteger()

    enum class When { BEFORE, AFTER }

    fun wrap(real: Connection): Connection {
        if (down) {
            real.close()
            throw SQLNonTransientConnectionException("the database is down (chaos)", "08001")
        }
        return Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java), handler(real) { method, args ->
            when (method.name) {
                "prepareStatement" -> statement(real, method.invoke(real, *args.orEmpty()) as PreparedStatement)
                "commit" -> when (onCommit.also { onCommit = null }) {
                    When.BEFORE -> kill(real)
                    When.AFTER -> {
                        real.commit()
                        kill(real)
                    }
                    null -> real.commit()
                }
                else -> method.invoke(real, *args.orEmpty())
            }
        }) as Connection
    }

    private fun statement(real: Connection, statement: PreparedStatement): PreparedStatement =
        Proxy.newProxyInstance(PreparedStatement::class.java.classLoader, arrayOf(PreparedStatement::class.java), handler(statement) { method, args ->
            if (method.name.startsWith("execute") && statementsLeft.get() > 0 && statementsLeft.decrementAndGet() == 0) kill(real)
            method.invoke(statement, *args.orEmpty())
        }) as PreparedStatement

    private fun kill(real: Connection): Nothing {
        kills.incrementAndGet()
        real.close()
        throw SQLNonTransientConnectionException("the connection was killed (chaos)", "08S01")
    }

    private fun handler(target: Any, call: (java.lang.reflect.Method, Array<Any?>?) -> Any?) = InvocationHandler { _, method, args ->
        if (method.declaringClass == Any::class.java) return@InvocationHandler method.invoke(target, *args.orEmpty())
        try {
            call(method, args)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }
}
