package co.xenastudios.xmarket.store

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.db.Database
import co.xenastudios.core.common.db.DbError
import co.xenastudios.core.common.db.Dialect
import co.xenastudios.core.common.db.Migration
import co.xenastudios.core.common.db.Row
import co.xenastudios.core.common.db.Sql
import co.xenastudios.core.common.db.SqlValue

/**
 * xMarket's two tables: `trades`, one row per purchase or sale (for `/shop history` and
 * `/shop stats`), and `limits`, how much of each limited entry each player (or the whole server,
 * as `*`) has had in the current period. Every write goes to the database's one writer, in order,
 * off the server's threads; reads too. A [Pending] here never fails: an error is an [Outcome.Err].
 */
public class Store(private val db: Database) {
    private val sql: Sql = db.sql

    public val description: String get() = db.description
    public val database: Database get() = db

    /** One trade, as `trades` keeps it. */
    public class Trade(
        public val at: Long,
        public val player: PlayerRef,
        public val buy: Boolean,
        public val category: String,
        public val entry: String,
        /** The item's key, `diamond`. */
        public val item: String,
        public val amount: Int,
        public val currency: String,
        /** In 1/10,000ths. */
        public val total: Long,
    )

    /** What a period's trades came to, for one currency. */
    public class Totals(public val currency: String, public val bought: Long, public val sold: Long)

    /** An item's sales over a period. */
    public class TopItem(public val item: String, public val amount: Long, public val total: Long)

    /** A limit's count, as `limits` keeps it. */
    public class Used(public val holder: String, public val key: String, public val period: Long, public val amount: Int)

    public fun record(trade: Trade): Pending<Outcome<Unit, DbError>> = db.write(
        listOf(
            sql.raw(
                "INSERT INTO {p}trades (at, player, kind, category, entry, item, amount, currency, total) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                SqlValue.Integer(trade.at), text(trade.player.toString()), SqlValue.Integer(if (trade.buy) 0 else 1),
                text(trade.category), text(trade.entry), text(trade.item), SqlValue.Integer(trade.amount.toLong()),
                text(trade.currency), SqlValue.Integer(trade.total),
            ),
        ),
    )

    /** One page of [player]'s trades, newest first, and how many there are. */
    public fun history(player: PlayerRef, page: Int, perPage: Int): Pending<Outcome<Pair<List<Trade>, Long>, DbError>> = db.read { s ->
        val id = text(player.toString())
        val count = s.query(sql.raw("SELECT COUNT(*) FROM {p}trades WHERE player = ?", id))
        if (count is Outcome.Err) return@read count
        val rows = s.query(
            sql.raw(
                "SELECT at, kind, category, entry, item, amount, currency, total FROM {p}trades WHERE player = ? ORDER BY at DESC, id DESC LIMIT ? OFFSET ?",
                id, SqlValue.Integer(perPage.toLong()), SqlValue.Integer(((page - 1) * perPage).toLong()),
            ),
        )
        if (rows is Outcome.Err) return@read rows
        val trades = (rows as Outcome.Ok).value.map { r -> trade(player, r) }
        Outcome.Ok(trades to ((count as Outcome.Ok).value.firstOrNull()?.long(0) ?: 0L))
    }

    private fun trade(player: PlayerRef, r: Row): Trade = Trade(
        r.long("at"), player, r.long("kind") == 0L, r.text("category"), r.text("entry"), r.text("item"),
        r.long("amount").toInt(), r.text("currency"), r.long("total"),
    )

    /** Since [since] (epoch milliseconds): what was bought and sold in each currency, and the [top] most sold items. */
    public fun stats(since: Long, top: Int): Pending<Outcome<Pair<List<Totals>, List<TopItem>>, DbError>> = db.read { s ->
        val totals = s.query(
            sql.raw(
                "SELECT currency, SUM(CASE WHEN kind = 0 THEN total ELSE 0 END) AS bought, SUM(CASE WHEN kind = 1 THEN total ELSE 0 END) AS sold" +
                    " FROM {p}trades WHERE at >= ? GROUP BY currency ORDER BY currency",
                SqlValue.Integer(since),
            ),
        )
        if (totals is Outcome.Err) return@read totals
        val items = s.query(
            sql.raw(
                "SELECT item, SUM(amount) AS amount, SUM(total) AS total FROM {p}trades WHERE at >= ? AND kind = 1 GROUP BY item ORDER BY SUM(amount) DESC, item LIMIT ?",
                SqlValue.Integer(since), SqlValue.Integer(top.toLong()),
            ),
        )
        if (items is Outcome.Err) return@read items
        Outcome.Ok(
            (totals as Outcome.Ok).value.map { Totals(it.text("currency"), it.long("bought"), it.long("sold")) } to
                (items as Outcome.Ok).value.map { TopItem(it.text("item"), it.long("amount"), it.long("total")) },
        )
    }

    /** Every count [holder] (a player's UUID, or `*` for the server) has. */
    public fun used(holder: String): Pending<Outcome<List<Used>, DbError>> = db.read { s ->
        val rows = s.query(sql.raw("SELECT entry, period, amount FROM {p}limits WHERE holder = ?", text(holder)))
        if (rows is Outcome.Err) return@read rows
        Outcome.Ok((rows as Outcome.Ok).value.map { Used(holder, it.text("entry"), it.long("period"), it.long("amount").toInt()) })
    }

    /** Sets a count: the period it's for and how much of it is used. */
    public fun setUsed(used: Used): Pending<Outcome<Unit, DbError>> = db.write(
        listOf(
            sql.raw("DELETE FROM {p}limits WHERE holder = ? AND entry = ?", text(used.holder), text(used.key)),
            sql.raw(
                "INSERT INTO {p}limits (holder, entry, period, amount) VALUES (?, ?, ?, ?)",
                text(used.holder), text(used.key), SqlValue.Integer(used.period), SqlValue.Integer(used.amount.toLong()),
            ),
        ),
    )

    /** Everything kept about [player]; settles with how many rows went. */
    public fun forget(player: PlayerRef): Pending<Outcome<Int, DbError>> = db.transaction { s ->
        val id = text(player.toString())
        val trades = s.execute(sql.raw("DELETE FROM {p}trades WHERE player = ?", id))
        if (trades is Outcome.Err) return@transaction trades
        val limits = s.execute(sql.raw("DELETE FROM {p}limits WHERE holder = ?", id))
        if (limits is Outcome.Err) return@transaction limits
        Outcome.Ok((trades as Outcome.Ok).value + (limits as Outcome.Ok).value)
    }

    public fun close(): Pending<Unit> = db.close()

    private fun text(value: String): SqlValue = SqlValue.Text(value)

    public companion object {
        public val MIGRATIONS: List<Migration> = listOf(
            Migration(1, "trades and limits") { sql ->
                val engine = if (sql.dialect is Dialect.Sqlite) "" else " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
                val id = if (sql.dialect is Dialect.Sqlite) "id INTEGER PRIMARY KEY AUTOINCREMENT" else "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY"
                listOf(
                    sql.raw(
                        "CREATE TABLE IF NOT EXISTS {p}trades ($id, at BIGINT NOT NULL, player CHAR(36) NOT NULL, kind INT NOT NULL," +
                            " category VARCHAR(64) NOT NULL, entry VARCHAR(128) NOT NULL, item VARCHAR(255) NOT NULL, amount INT NOT NULL," +
                            " currency VARCHAR(128) NOT NULL, total BIGINT NOT NULL, server VARCHAR(64) NOT NULL DEFAULT '')$engine",
                    ),
                    sql.raw("CREATE INDEX {p}trades_player ON {p}trades (player, at)"),
                    sql.raw("CREATE INDEX {p}trades_at ON {p}trades (at)"),
                    sql.raw(
                        "CREATE TABLE IF NOT EXISTS {p}limits (holder CHAR(36) NOT NULL, entry VARCHAR(200) NOT NULL, period BIGINT NOT NULL," +
                            " amount INT NOT NULL, PRIMARY KEY (holder, entry))$engine",
                    ),
                )
            },
        )
    }
}

// A column by name, as the query selected it: SUM() of no rows is NULL, so 0.
private fun Row.long(name: String): Long = when (val v = value(name)) {
    is SqlValue.Integer -> v.value
    is SqlValue.Real -> v.value.toLong()
    is SqlValue.Text -> v.value.toLongOrNull() ?: 0
    else -> 0
}

private fun Row.text(name: String): String = when (val v = value(name)) {
    is SqlValue.Text -> v.value
    is SqlValue.Integer -> v.value.toString()
    else -> ""
}
