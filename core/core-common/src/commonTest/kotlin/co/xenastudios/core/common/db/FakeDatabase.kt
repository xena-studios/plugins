package co.xenastudios.core.common.db

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.onSettled
import co.xenastudios.core.api.pendingOf
import co.xenastudios.core.common.Outcome
import kotlin.test.fail

/**
 * A database for common tests, on the JVM and Wasm: it runs nothing, records every statement,
 * and answers each with what [answer] says (one changed row, no rows, by default).
 */
class FakeSession(override val dialect: Dialect = Dialect.Sqlite.CURRENT) : SqlSession {
    /** Every statement run, in order, with `BEGIN`, `COMMIT` and `ROLLBACK` around transactions. */
    val log = ArrayList<String>()
    var answer: (Statement) -> Outcome<List<Row>, DbError> = { Outcome.Ok(emptyList()) }
    private var inTransaction = false

    override fun execute(statement: Statement): Outcome<Int, DbError> {
        log += statement.toString()
        return when (val result = answer(statement)) {
            is Outcome.Ok -> Outcome.Ok(1)
            is Outcome.Err -> result
        }
    }

    override fun insertForId(statement: Statement): Outcome<Long, DbError> = when (val result = execute(statement)) {
        is Outcome.Ok -> Outcome.Ok(log.size.toLong())
        is Outcome.Err -> result
    }

    override fun query(statement: Statement): Outcome<List<Row>, DbError> {
        log += statement.toString()
        return answer(statement)
    }

    override fun <T> transaction(work: (SqlSession) -> Outcome<T, DbError>): Outcome<T, DbError> {
        check(!inTransaction) { "transactions don't nest" }
        inTransaction = true
        log += "BEGIN"
        try {
            val result = work(this)
            log += if (result is Outcome.Ok) "COMMIT" else "ROLLBACK"
            return result
        } finally {
            inTransaction = false
        }
    }
}

/** A [Database] that runs everything at once on a [FakeSession]. */
class FakeDatabase(val session: FakeSession = FakeSession(), prefix: String = "test_") : Database {
    override val dialect: Dialect get() = session.dialect
    override val sql: Sql = Sql(session.dialect, prefix)
    override val description: String = "the fake database"

    override fun write(statements: List<Statement>): Pending<Outcome<Unit, DbError>> =
        pendingOf(session.transaction { tx ->
            when (val ran = tx.executeAll(statements)) {
                is Outcome.Ok -> Outcome.Ok(Unit)
                is Outcome.Err -> ran
            }
        })

    override fun <T> transaction(work: (SqlSession) -> Outcome<T, DbError>): Pending<Outcome<T, DbError>> =
        pendingOf(session.transaction(work))

    override fun <T> session(work: (SqlSession) -> Outcome<T, DbError>): Pending<Outcome<T, DbError>> = pendingOf(work(session))

    override fun <T> read(work: (SqlSession) -> Outcome<T, DbError>): Pending<Outcome<T, DbError>> = pendingOf(work(session))

    override fun close(): Pending<Unit> = pendingOf(Unit)
}

/** The value it settled with; fails the test if it hasn't settled, or failed. */
fun <T> Pending<T>.settled(): T {
    var settled = false
    var result: T? = null
    var failure: Throwable? = null
    onSettled { value, error ->
        settled = true
        result = value
        failure = error
    }
    if (!settled) fail("it hasn't settled")
    failure?.let { throw it }
    @Suppress("UNCHECKED_CAST")
    return result as T
}

/** The value of an [Outcome.Ok]; fails the test with the error otherwise. */
fun <T> Outcome<T, DbError>.ok(): T = when (this) {
    is Outcome.Ok -> value
    is Outcome.Err -> fail("expected a value, got $error")
}

/** One row with these columns and values. */
fun row(vararg pairs: Pair<String, SqlValue>): Row = Row(pairs.map { it.first }, pairs.map { it.second })
