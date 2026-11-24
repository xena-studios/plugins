package co.xenastudios.core.common.db

import co.xenastudios.core.api.Pending
import co.xenastudios.core.common.Outcome

/**
 * One connection to the database, used by one thread at a time: what [Database.read],
 * [Database.session] and [Database.transaction] hand their work. Every call waits for the
 * database, so a session is only ever given out on the database's own threads.
 */
public interface SqlSession {
    /** The database's dialect. */
    public val dialect: Dialect

    /** Runs [statement]; returns how many rows it changed. */
    public fun execute(statement: Statement): Outcome<Int, DbError>

    /** Runs an `INSERT` into a table with a [Table.rowId], and returns the id it was given. */
    public fun insertForId(statement: Statement): Outcome<Long, DbError>

    /** Runs a query and returns every row it found. */
    public fun query(statement: Statement): Outcome<List<Row>, DbError>

    /**
     * Runs [work] in one transaction: committed if it returns [Outcome.Ok], rolled back if it
     * returns [Outcome.Err] or throws. Transactions don't nest.
     */
    public fun <T> transaction(work: (SqlSession) -> Outcome<T, DbError>): Outcome<T, DbError>
}

/** Runs each statement in order, stopping at the first error; returns the rows changed in all. */
public fun SqlSession.executeAll(statements: List<Statement>): Outcome<Int, DbError> {
    var changed = 0
    for (statement in statements) {
        when (val result = execute(statement)) {
            is Outcome.Ok -> changed += result.value
            is Outcome.Err -> return result
        }
    }
    return Outcome.Ok(changed)
}

/** The first row [statement] finds, read by [read], or `null` if it finds none. */
public fun <T> SqlSession.queryOne(statement: Statement, read: (Row) -> T): Outcome<T?, DbError> =
    when (val rows = query(statement)) {
        is Outcome.Ok -> Outcome.Ok(rows.value.firstOrNull()?.let(read))
        is Outcome.Err -> rows
    }

/**
 * A plugin's database, wherever it runs: on the JVM, JDBC on threads of its own
 * (`core-jvm`'s `JdbcDatabase`). Pumpkin has no SQL (M0), so a plugin that must run there keeps
 * its data in a [co.xenastudios.core.common.storage.RecordStore] instead, which is backed by
 * this on the JVM and by files on Pumpkin.
 *
 * **Writes go through one writer, in order.** [write] queues statements and returns at once;
 * the writer runs everything waiting as one transaction (a batch), so a burst of writes costs
 * one commit. The [Pending] settles once that transaction has committed, or with why it
 * couldn't: nothing is reported written that isn't. A batch that fails because the database went
 * away is kept and tried again until it works or the database is closed; one the database
 * refuses (a broken key) is split, and only the write at fault fails. Past a bound, new writes
 * are refused at once with [DbError.Kind.OVERLOADED] rather than run the server out of memory.
 *
 * **Reads run beside the writer**, on a connection of their own, and see what's committed. A
 * read that must see a write waits for that write's [Pending], or runs in the writer with
 * [session].
 *
 * Every [Pending] settles on the database's threads with an [Outcome], never a failure: a
 * caller that touches the world moves to the right thread first, and nothing here blocks one.
 */
public interface Database {
    /** The database's dialect, found when it opened. */
    public val dialect: Dialect

    /** Builds statements for this database and the plugin's prefix. */
    public val sql: Sql

    /** Where the data is, for the console: `the SQLite database homes.db`, `MySQL at host:3306/db`. */
    public val description: String

    /** Queues [statements] to run together, in order, in the writer's next batch. */
    public fun write(statements: List<Statement>): Pending<Outcome<Unit, DbError>>

    /**
     * Runs [work] on the writer, after every write queued before it, in a transaction of its
     * own: for a change that reads before it writes (`UPDATE ... WHERE balance >= ?`). Rolled
     * back if [work] returns an error; tried again, from the start, if the connection breaks
     * before it commits, so [work] must only touch the database.
     */
    public fun <T> transaction(work: (SqlSession) -> Outcome<T, DbError>): Pending<Outcome<T, DbError>>

    /**
     * Runs [work] on the writer, after every write queued before it, outside a transaction:
     * for migrations, which manage their own, and reads that must see every earlier write.
     * Tried again from the start if the connection breaks, so [work] must be safe to run twice,
     * as migrations are.
     */
    public fun <T> session(work: (SqlSession) -> Outcome<T, DbError>): Pending<Outcome<T, DbError>>

    /** Runs [work] on a reader, beside the writer. */
    public fun <T> read(work: (SqlSession) -> Outcome<T, DbError>): Pending<Outcome<T, DbError>>

    /**
     * Stops taking work, finishes what's queued (waiting a while for a database that's down),
     * and closes. The [Pending] settles when it's closed. Work still queued at the end fails
     * with [DbError.Kind.CLOSED].
     */
    public fun close(): Pending<Unit>
}

/** [Database.write] of one statement. */
public fun Database.write(vararg statements: Statement): Pending<Outcome<Unit, DbError>> = write(statements.toList())

/** Brings the plugin's tables up to date with [migrations], on the writer. See [Migrations]. */
public fun Database.migrate(migrations: List<Migration>): Pending<Outcome<Int, DbError>> =
    session { Migrations.run(it, sql, migrations) }

/**
 * Where a plugin keeps its data, as its `storage` config section says
 * ([co.xenastudios.core.common.family.StorageSection]). The family can share one database (the
 * same MySQL, or one SQLite file in `plugins/Xena/`): each plugin's [prefix] keeps its tables,
 * and its schema version, apart.
 */
public data class DatabaseSettings(
    public val type: Type = Type.SQLITE,
    /** Before every table's name, such as `xbans_`. */
    public val prefix: String,
    public val mysql: MySql = MySql(),
    /**
     * How many connections read beside the writer, or `null` for the database's own choice
     * (one on SQLite, two on MySQL): the `pool` section.
     */
    public val readers: Int? = null,
) {
    /** Which database. */
    public enum class Type {
        /** One file in the plugin's folder, with nothing to set up. */
        SQLITE,

        /** MySQL or MariaDB, for networks. */
        MYSQL,
    }

    /** The connection, for [Type.MYSQL]. */
    public data class MySql(
        public val host: String = "localhost",
        public val port: Int = 3306,
        public val database: String = "minecraft",
        public val username: String = "minecraft",
        public val password: String = "",
        /** Extra connection settings, as in a JDBC URL: `useSSL=true&serverTimezone=UTC`. */
        public val properties: String = "",
    ) {
        override fun toString(): String = "MySql($username@$host:$port/$database)"
    }
}
