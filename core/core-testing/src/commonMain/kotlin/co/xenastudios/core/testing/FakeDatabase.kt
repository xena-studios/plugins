package co.xenastudios.core.testing

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.onSettled
import co.xenastudios.core.api.pendingOf
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.db.Database
import co.xenastudios.core.common.db.DbError
import co.xenastudios.core.common.db.Dialect
import co.xenastudios.core.common.db.Row
import co.xenastudios.core.common.db.Sql
import co.xenastudios.core.common.db.SqlSession
import co.xenastudios.core.common.db.SqlValue
import co.xenastudios.core.common.db.Statement
import co.xenastudios.core.common.db.executeAll

/**
 * A database session for tests, on the JVM and Wasm: it runs nothing, records every statement
 * in [log], and answers each with what [answer] says (one changed row, no rows, by default).
 * Set [answer] to return rows for a query, or an error to test what a plugin does when the
 * database says no.
 */
public class FakeSession(override val dialect: Dialect = Dialect.Sqlite.CURRENT) : SqlSession {
    /** Every statement run, in order, with `BEGIN`, `COMMIT` and `ROLLBACK` around transactions. */
    public val log: MutableList<String> = ArrayList()

    /** What each statement gets back. */
    public var answer: (Statement) -> Outcome<List<Row>, DbError> = { Outcome.Ok(emptyList()) }

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

/**
 * A [Database] for a plugin's tests that runs everything at once, on the test's thread, on a
 * [FakeSession]: every [Pending] it returns has already settled. For what a plugin sends the
 * database and what it does with the answer; the SQL itself is tested on real databases in
 * core-jvm.
 */
public class FakeDatabase(public val session: FakeSession = FakeSession(), prefix: String = "test_") : Database {
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
public fun <T> Pending<T>.settled(): T {
    var settled = false
    var result: T? = null
    var failure: Throwable? = null
    onSettled { value, error ->
        settled = true
        result = value
        failure = error
    }
    if (!settled) throw AssertionError("it hasn't settled")
    failure?.let { throw it }
    @Suppress("UNCHECKED_CAST")
    return result as T
}

/** The value of an [Outcome.Ok]; fails the test with the error otherwise. */
public fun <T, E> Outcome<T, E>.ok(): T = when (this) {
    is Outcome.Ok -> value
    is Outcome.Err -> throw AssertionError("expected a value, got $error")
}

/** One row with these columns and values, as a query would return it. */
public fun row(vararg pairs: Pair<String, SqlValue>): Row = Row(pairs.map { it.first }, pairs.map { it.second })
