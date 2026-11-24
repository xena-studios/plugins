package co.xenastudios.core.jvm.db

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.db.CorruptValueException
import co.xenastudios.core.common.db.DbError
import co.xenastudios.core.common.db.Dialect
import co.xenastudios.core.common.db.Row
import co.xenastudios.core.common.db.SqlSession
import co.xenastudios.core.common.db.SqlValue
import co.xenastudios.core.common.db.Statement
import java.math.BigDecimal
import java.math.BigInteger
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.SQLIntegrityConstraintViolationException
import java.sql.SQLNonTransientConnectionException
import java.sql.SQLRecoverableException
import java.sql.SQLTransientConnectionException
import java.sql.Types

/** A [SqlSession] on one JDBC connection, used by one thread at a time. */
internal class JdbcSession(val connection: Connection, override val dialect: Dialect) : SqlSession {
    private var inTransaction = false

    override fun execute(statement: Statement): Outcome<Int, DbError> = jdbc {
        connection.prepareStatement(statement.sql).use { prepared ->
            bind(prepared, statement.args)
            prepared.executeUpdate()
        }
    }

    override fun insertForId(statement: Statement): Outcome<Long, DbError> = jdbc {
        connection.prepareStatement(statement.sql, java.sql.Statement.RETURN_GENERATED_KEYS).use { prepared ->
            bind(prepared, statement.args)
            prepared.executeUpdate()
            prepared.generatedKeys.use { keys ->
                if (!keys.next()) throw SQLException("the insert gave no key: ${statement.sql}")
                keys.getLong(1)
            }
        }
    }

    override fun query(statement: Statement): Outcome<List<Row>, DbError> = jdbc {
        connection.prepareStatement(statement.sql).use { prepared ->
            bind(prepared, statement.args)
            prepared.executeQuery().use { read(it) }
        }
    }

    override fun <T> transaction(work: (SqlSession) -> Outcome<T, DbError>): Outcome<T, DbError> {
        check(!inTransaction) { "transactions don't nest" }
        inTransaction = true
        try {
            jdbc { connection.autoCommit = false }.let { if (it is Outcome.Err) return it }
            val result = contain { work(this) }
            if (result is Outcome.Err) {
                rollbackQuietly()
                return result
            }
            return when (val committed = jdbc { connection.commit() }) {
                is Outcome.Ok -> result
                is Outcome.Err -> {
                    rollbackQuietly()
                    committed
                }
            }
        } finally {
            inTransaction = false
            try {
                if (!connection.isClosed) connection.autoCommit = true
            } catch (e: SQLException) {
                // The connection is broken; whoever uses it next finds out.
            }
        }
    }

    /** Runs a batch's statements in order, binding consecutive ones with the same SQL as one JDBC batch. */
    fun executeBatched(statements: List<Statement>) {
        var i = 0
        while (i < statements.size) {
            val sql = statements[i].sql
            var end = i + 1
            while (end < statements.size && statements[end].sql == sql) end++
            connection.prepareStatement(sql).use { prepared ->
                if (end - i == 1) {
                    bind(prepared, statements[i].args)
                    prepared.executeUpdate()
                } else {
                    for (k in i until end) {
                        bind(prepared, statements[k].args)
                        prepared.addBatch()
                    }
                    prepared.executeBatch()
                }
            }
            i = end
        }
    }

    fun rollbackQuietly() {
        try {
            if (!connection.isClosed) connection.rollback()
        } catch (e: SQLException) {
            // Nothing was committed; a broken connection rolls back on its own.
        }
    }

    private inline fun <T> jdbc(block: () -> T): Outcome<T, DbError> = try {
        Outcome.Ok(block())
    } catch (e: SQLException) {
        Outcome.Err(errorOf(e, dialect))
    }

    companion object {
        fun bind(prepared: PreparedStatement, args: List<SqlValue>) {
            for ((index, value) in args.withIndex()) {
                val at = index + 1
                when (value) {
                    SqlValue.Null -> prepared.setNull(at, Types.NULL)
                    is SqlValue.Integer -> prepared.setLong(at, value.value)
                    is SqlValue.Real -> prepared.setDouble(at, value.value)
                    is SqlValue.Text -> prepared.setString(at, value.value)
                    is SqlValue.Bytes -> prepared.setBytes(at, value.value)
                }
            }
        }

        fun read(result: ResultSet): List<Row> {
            val meta = result.metaData
            val names = (1..meta.columnCount).map { meta.getColumnLabel(it).lowercase() }
            val rows = ArrayList<Row>()
            while (result.next()) {
                rows += Row(names, names.indices.map { valueOf(result.getObject(it + 1)) })
            }
            return rows
        }

        private fun valueOf(value: Any?): SqlValue = when (value) {
            null -> SqlValue.Null
            is Long -> SqlValue.Integer(value)
            is Int -> SqlValue.Integer(value.toLong())
            is Short -> SqlValue.Integer(value.toLong())
            is Byte -> SqlValue.Integer(value.toLong())
            is Boolean -> SqlValue.Integer(if (value) 1 else 0)
            is BigInteger -> SqlValue.Integer(value.toLong())
            is BigDecimal -> if (value.scale() <= 0) SqlValue.Integer(value.toLong()) else SqlValue.Real(value.toDouble())
            is Double -> SqlValue.Real(value)
            is Float -> SqlValue.Real(value.toDouble())
            is ByteArray -> SqlValue.Bytes(value)
            else -> SqlValue.Text(value.toString())
        }

        /**
         * Runs [work], turning what it throws into an error: a value it couldn't read is
         * [DbError.Kind.CORRUPT], anything else [DbError.Kind.OTHER]. Errors the JVM can't recover
         * from go through.
         */
        inline fun <T> contain(work: () -> Outcome<T, DbError>): Outcome<T, DbError> = try {
            work()
        } catch (e: CorruptValueException) {
            Outcome.Err(DbError(DbError.Kind.CORRUPT, e.message ?: e.toString()))
        } catch (e: SQLException) {
            Outcome.Err(DbError(DbError.Kind.OTHER, e.toString()))
        } catch (e: RuntimeException) {
            Outcome.Err(DbError(DbError.Kind.OTHER, "the database work threw $e"))
        }

        /** What a driver's exception means, by its type, SQLState and the database's own error number. */
        fun errorOf(e: SQLException, dialect: Dialect): DbError {
            val state = e.sqlState
            val code = e.errorCode
            val message = e.message ?: e.toString()
            val kind = when {
                e is SQLNonTransientConnectionException || e is SQLTransientConnectionException || e is SQLRecoverableException -> DbError.Kind.UNAVAILABLE
                state != null && state.startsWith("08") -> DbError.Kind.UNAVAILABLE
                dialect == Dialect.MySql -> when (code) {
                    1050, 1060, 1061 -> DbError.Kind.ALREADY_EXISTS
                    1213, 1205 -> DbError.Kind.BUSY
                    1062, 1048, 1451, 1452, 1364 -> DbError.Kind.CONSTRAINT
                    1064, 1146, 1054, 1149 -> DbError.Kind.BAD_STATEMENT
                    // The server went away mid-statement, or the connection was killed.
                    2006, 2013, 1927, 1053 -> DbError.Kind.UNAVAILABLE
                    else -> if (e is SQLIntegrityConstraintViolationException) DbError.Kind.CONSTRAINT else DbError.Kind.OTHER
                }
                // SQLite's result codes (the low byte, in case the driver reports an extended one).
                else -> when (code and 0xFF) {
                    5, 6 -> DbError.Kind.BUSY
                    19 -> DbError.Kind.CONSTRAINT
                    1 -> when {
                        "already exists" in message || "duplicate column name" in message -> DbError.Kind.ALREADY_EXISTS
                        "syntax error" in message || "no such" in message || "has no column" in message -> DbError.Kind.BAD_STATEMENT
                        else -> DbError.Kind.OTHER
                    }
                    // SQLITE_IOERR, SQLITE_CANTOPEN, SQLITE_NOTADB: the file, not the statement.
                    10, 14, 26 -> DbError.Kind.UNAVAILABLE
                    else -> if (message.contains("closed", ignoreCase = true)) DbError.Kind.UNAVAILABLE else DbError.Kind.OTHER
                }
            }
            return DbError(kind, message, state, code)
        }
    }
}
