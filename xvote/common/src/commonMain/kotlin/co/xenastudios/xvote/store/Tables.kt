package co.xenastudios.xvote.store

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.db.DbError
import co.xenastudios.core.common.db.Dialect
import co.xenastudios.core.common.db.Migration
import co.xenastudios.core.common.db.Row
import co.xenastudios.core.common.db.Sql
import co.xenastudios.core.common.db.SqlSession
import co.xenastudios.core.common.db.SqlValue
import co.xenastudios.core.common.db.Statement

/**
 * xVote's tables: 1.x's, byte for byte, so either jar reads the other's database
 * (decisions/2026-11-27-xvote-2.md). Schema 2, made with 1.x's own statements.
 *
 * `votes` is the ledger, and the queue: one row per vote, never deleted except unmatched names past
 * `counting.unknown-name-days` and `/xvote forget`. `uuid` is empty until the name is matched to a
 * player; `number`, `streak` and `first_today` are worked out when it is (the vote's place in the
 * player's count), so whichever server pays it gives the same rewards. `claim` is empty until a
 * server takes the vote to pay it: taking it is one `UPDATE ... WHERE claim IS NULL`, so two servers
 * can never both pay it. A vote for a player who isn't online anywhere waits here, unclaimed, for
 * the first server they join.
 *
 * `voters` holds each player's running totals and streak, kept in step with the ledger in the same
 * transaction. `held` keeps reward items that didn't fit, for `/vote claim`. `state` keeps the vote
 * party's count and when imports ran. Times are epoch milliseconds; `last_day` is a day number in
 * the configured time zone. `address` is always empty: xVote never keeps the voter's IP. 1.x's
 * `meta` table is the core's ([co.xenastudios.core.common.db.Migrations.Meta]), the same columns.
 */
public object Tables {
    /** 1.x's two steps, as its `Schema.STEPS`. */
    public val MIGRATIONS: List<Migration> = listOf(
        Migration(1, "the first tables", co.xenastudios.xvote.Startup.tables),
        Migration(2, "voters' addresses aren't kept", co.xenastudios.xvote.Startup.addresses),
    )

    private val STEP_1 = listOf(
        "CREATE TABLE {p}votes (id {rowid}, uuid VARCHAR(36), name VARCHAR(64) NOT NULL, name_key VARCHAR(64) NOT NULL," +
            " site VARCHAR(64) NOT NULL, address VARCHAR(64) NOT NULL, list_time VARCHAR(64) NOT NULL, source VARCHAR(16) NOT NULL," +
            " at BIGINT NOT NULL, number INT, streak INT, first_today INT, claim VARCHAR(40), paid_at BIGINT){table}",
        "CREATE INDEX {p}votes_player ON {p}votes (uuid, at)",
        "CREATE INDEX {p}votes_name ON {p}votes (name_key, site)",
        "CREATE INDEX {p}votes_at ON {p}votes (at)",
        "CREATE TABLE {p}voters (uuid VARCHAR(36) PRIMARY KEY, name VARCHAR(64) NOT NULL, total INT NOT NULL," +
            " streak INT NOT NULL, best_streak INT NOT NULL, last_day INT NOT NULL, last_vote BIGINT NOT NULL){table}",
        "CREATE TABLE {p}held (id {rowid}, uuid VARCHAR(36) NOT NULL, item {blob} NOT NULL, created BIGINT NOT NULL){table}",
        "CREATE INDEX {p}held_player ON {p}held (uuid)",
        "CREATE TABLE {p}state (name VARCHAR(32) PRIMARY KEY, value BIGINT NOT NULL){table}",
        "INSERT INTO {p}state (name, value) VALUES ('party_votes', 0)",
        "INSERT INTO {p}state (name, value) VALUES ('party_since', 0)",
    )

    internal fun first(sql: Sql): List<Statement> = STEP_1.map { sql.raw(fill(it, sql.dialect)) }

    internal fun second(sql: Sql): List<Statement> = listOf(sql.raw("UPDATE {p}votes SET address = '' WHERE address <> ''"))

    /** 1.x's placeholders: `{rowid}` a key the database numbers, `{blob}` bytes, `{table}` what follows a `CREATE TABLE`. */
    internal fun fill(text: String, dialect: Dialect): String = when (dialect) {
        is Dialect.Sqlite -> text.replace("{rowid}", "INTEGER PRIMARY KEY").replace("{blob}", "BLOB").replace("{table}", "")
        Dialect.MySql -> text.replace("{rowid}", "BIGINT PRIMARY KEY AUTO_INCREMENT").replace("{blob}", "MEDIUMBLOB")
            .replace("{table}", " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4")
    }
}

/** A database step that went wrong, thrown inside a [Tx] and turned back into its [DbError] at its edge. */
internal class TxFailed(val error: DbError) : RuntimeException(error.detail, null)

/**
 * One transaction's statements, written as 1.x wrote its JDBC: each call runs at once and throws
 * [TxFailed] on an error, which [run] turns back into the [Outcome] the core's database wants. Only
 * inside the ledger's own work, never past it.
 */
internal class Tx(private val session: SqlSession, private val sql: Sql) {
    val mysql: Boolean get() = session.dialect is Dialect.MySql

    /** Tells the other servers about this transaction's change, in it: nothing when [feed] is off. */
    fun publish(feed: co.xenastudios.core.common.db.ChangeFeed?, topic: String, payload: String) {
        if (feed == null) return
        ok(feed.publish(session, topic, payload))
    }

    /** Row locks on MySQL, so two servers counting one player's votes take turns. SQLite has one writer anyway. */
    val forUpdate: String get() = if (mysql) " FOR UPDATE" else ""

    fun exec(text: String, vararg args: Any?): Int = ok(session.execute(statement(text, args)))

    fun insert(text: String, vararg args: Any?): Long = ok(session.insertForId(statement(text, args)))

    fun rows(text: String, vararg args: Any?): List<Row> = ok(session.query(statement(text, args)))

    fun count(text: String, vararg args: Any?): Long = rows(text, *args).firstOrNull()?.let { it.long(0) } ?: 0L

    private fun statement(text: String, args: Array<out Any?>): Statement {
        val values = ArrayList<SqlValue>(args.size)
        for (arg in args) values += value(arg)
        return sql.raw(text, *values.toTypedArray())
    }

    private fun <T> ok(outcome: Outcome<T, DbError>): T = when (outcome) {
        is Outcome.Ok -> outcome.value
        is Outcome.Err -> throw TxFailed(outcome.error)
    }

    companion object {
        fun value(arg: Any?): SqlValue = when (arg) {
            null -> SqlValue.Null
            is String -> SqlValue.Text(arg)
            is Int -> SqlValue.Integer(arg.toLong())
            is Long -> SqlValue.Integer(arg)
            is Boolean -> SqlValue.Integer(if (arg) 1 else 0)
            is ByteArray -> SqlValue.Bytes(arg)
            is SqlValue -> arg
            else -> SqlValue.Text(arg.toString())
        }

        /** [work] in [session]'s own transaction, as an [Outcome]. */
        fun <T> run(session: SqlSession, sql: Sql, work: (Tx) -> T): Outcome<T, DbError> = session.transaction { inner ->
            try {
                Outcome.Ok(work(Tx(inner, sql)))
            } catch (e: TxFailed) {
                Outcome.Err(e.error)
            }
        }

        /** [work] outside a transaction (a read beside the writer), as an [Outcome]. */
        fun <T> read(session: SqlSession, sql: Sql, work: (Tx) -> T): Outcome<T, DbError> = try {
            Outcome.Ok(work(Tx(session, sql)))
        } catch (e: TxFailed) {
            Outcome.Err(e.error)
        }
    }
}

/** A row's column [i] as text, or `null`. */
internal fun Row.text(i: Int): String? = when (val v = values[i]) {
    is SqlValue.Text -> v.value
    SqlValue.Null -> null
    is SqlValue.Integer -> v.value.toString()
    is SqlValue.Real -> v.value.toString()
    is SqlValue.Bytes -> v.value.decodeToString()
}

/** A row's column [i] as a number, or `null`. */
internal fun Row.number(i: Int): Long? = when (val v = values[i]) {
    is SqlValue.Integer -> v.value
    is SqlValue.Real -> v.value.toLong()
    is SqlValue.Text -> v.value.trim().toLongOrNull()
    else -> null
}

internal fun Row.int(i: Int): Int = number(i)?.toInt() ?: 0

internal fun Row.longAt(i: Int, default: Long = 0): Long = number(i) ?: default

internal fun Row.bytes(i: Int): ByteArray? = when (val v = values[i]) {
    is SqlValue.Bytes -> v.value
    is SqlValue.Text -> v.value.encodeToByteArray()
    else -> null
}
