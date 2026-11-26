package co.xenastudios.xmoney.ledger

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.db.Dialect
import co.xenastudios.core.common.db.Migration
import co.xenastudios.core.common.db.Sql
import co.xenastudios.core.common.db.SqlValue
import co.xenastudios.core.common.db.Statement

/**
 * xMoney's tables, exactly as 1.x made them, so the 1.x jar put back reads a 2.0 database as its
 * own: the same columns, types, indexes and schema version (1). Amounts are `BIGINT`s of units
 * and times epoch milliseconds; uuids are text, as 1.x stored them.
 *
 * - `accounts`: each account's last known name, and whether it's a player's.
 * - `balances`: one row per account and currency (only `default`).
 * - `transactions`: the ledger, append-only. Every change is a row here, written in the same
 *   database transaction as the balance it changed, so the two always agree. 1.x's `commit_id`
 *   (its own lost-commit lookup) stays, empty: the core's writer does that now.
 */
public object Tables {
    public const val NAME_LENGTH: Int = 64
    public const val SOURCE_LENGTH: Int = 64
    public const val REASON_LENGTH: Int = 100

    /** The schema: 1.x's one step, word for word, so 1.x and 2.0 each see the other's database as current. */
    public val MIGRATIONS: List<Migration> = listOf(
        Migration(1, "accounts, balances and the ledger") { sql ->
            fun raw(text: String) = sql.raw(text.replace("{rowid}", rowId(sql.dialect)).replace("{table}", tableOptions(sql.dialect)))
            listOf(
                raw("CREATE TABLE IF NOT EXISTS {p}accounts (uuid CHAR(36) PRIMARY KEY, name VARCHAR($NAME_LENGTH) NOT NULL, player SMALLINT NOT NULL, created BIGINT NOT NULL){table}"),
                raw("CREATE TABLE IF NOT EXISTS {p}balances (uuid CHAR(36) NOT NULL, currency VARCHAR(32) NOT NULL, balance BIGINT NOT NULL, PRIMARY KEY (uuid, currency)){table}"),
                raw("CREATE TABLE IF NOT EXISTS {p}transactions (id {rowid}, time BIGINT NOT NULL, uuid CHAR(36) NOT NULL, currency VARCHAR(32) NOT NULL, " +
                    "amount BIGINT NOT NULL, balance BIGINT NOT NULL, kind VARCHAR(16) NOT NULL, source VARCHAR($SOURCE_LENGTH) NOT NULL, " +
                    "reason VARCHAR($REASON_LENGTH) NOT NULL, other CHAR(36), commit_id BIGINT){table}"),
                raw("CREATE INDEX {p}transactions_uuid ON {p}transactions (uuid, id)"),
                raw("CREATE INDEX {p}transactions_time ON {p}transactions (time)"),
                raw("CREATE UNIQUE INDEX {p}transactions_commit ON {p}transactions (commit_id)"),
            )
        },
    )

    private fun rowId(dialect: Dialect) = if (dialect is Dialect.Sqlite) "INTEGER PRIMARY KEY" else "BIGINT PRIMARY KEY AUTO_INCREMENT"

    private fun tableOptions(dialect: Dialect) = if (dialect is Dialect.Sqlite) "" else " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
}

/** The statements xMoney's store runs, for one database and prefix. Pure: tests read them. */
internal class Statements(private val sql: Sql, private val currency: String) {
    private val dialect = sql.dialect
    private val native = dialect is Dialect.MySql || (dialect as Dialect.Sqlite).nativeUpsert

    private fun text(value: String) = SqlValue.Text(value)
    private fun int(value: Long) = SqlValue.Integer(value)

    /** Adds [delta] to [id]'s balance, making the row if it's missing: one upsert, or an insert of 0 then an update on old SQLite. */
    fun add(id: PlayerRef, delta: Long): List<Statement> = when {
        dialect is Dialect.MySql -> listOf(sql.raw(
            "INSERT INTO {p}balances (uuid, currency, balance) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE balance = balance + VALUES(balance)",
            text(id.toString()), text(currency), int(delta),
        ))
        native -> listOf(sql.raw(
            "INSERT INTO {p}balances (uuid, currency, balance) VALUES (?, ?, ?) ON CONFLICT (uuid, currency) DO UPDATE SET balance = balance + excluded.balance",
            text(id.toString()), text(currency), int(delta),
        ))
        else -> listOf(
            ensure(id),
            sql.raw("UPDATE {p}balances SET balance = balance + ? WHERE uuid = ? AND currency = ?", int(delta), text(id.toString()), text(currency)),
        )
    }

    /** Makes [id]'s balance row at 0 if it's missing. */
    fun ensure(id: PlayerRef): Statement = sql.raw(
        (if (dialect is Dialect.MySql) "INSERT IGNORE" else "INSERT OR IGNORE") + " INTO {p}balances (uuid, currency, balance) VALUES (?, ?, 0)",
        text(id.toString()), text(currency),
    )

    /** Makes [id]'s balance row at [start] if it's missing: a new player's starting balance, given once across servers. */
    fun start(id: PlayerRef, start: Long): Statement = sql.raw(
        (if (dialect is Dialect.MySql) "INSERT IGNORE" else "INSERT OR IGNORE") + " INTO {p}balances (uuid, currency, balance) VALUES (?, ?, ?)",
        text(id.toString()), text(currency), int(start),
    )

    /** Takes [amount] from [id] only if the balance covers it: changes one row, or none. */
    fun take(id: PlayerRef, amount: Long): Statement = sql.raw(
        "UPDATE {p}balances SET balance = balance - ? WHERE uuid = ? AND currency = ? AND balance >= ?",
        int(amount), text(id.toString()), text(currency), int(amount),
    )

    /** Sets [id]'s balance to [to] if it's still [was]: changes one row, or none. */
    fun swap(id: PlayerRef, was: Long, to: Long): Statement = sql.raw(
        "UPDATE {p}balances SET balance = ? WHERE uuid = ? AND currency = ? AND balance = ?",
        int(to), text(id.toString()), text(currency), int(was),
    )

    /** [id]'s balance; on MySQL locked for the rest of the transaction. */
    fun balance(id: PlayerRef, lock: Boolean): Statement = sql.raw(
        "SELECT balance FROM {p}balances WHERE uuid = ? AND currency = ?" + if (lock && dialect is Dialect.MySql) " FOR UPDATE" else "",
        text(id.toString()), text(currency),
    )

    /** The account's row: its name and kind, the name updated if it's there. */
    fun account(account: Account, created: Long): List<Statement> {
        val args = arrayOf(text(account.id.toString()), text(account.storedName), int(if (account.player) 1 else 0), int(created))
        return when {
            dialect is Dialect.MySql -> listOf(sql.raw(
                "INSERT INTO {p}accounts (uuid, name, player, created) VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE name = VALUES(name)", *args,
            ))
            native -> listOf(sql.raw(
                "INSERT INTO {p}accounts (uuid, name, player, created) VALUES (?, ?, ?, ?) ON CONFLICT (uuid) DO UPDATE SET name = excluded.name", *args,
            ))
            else -> listOf(
                sql.raw("UPDATE {p}accounts SET name = ? WHERE uuid = ?", text(account.storedName), text(account.id.toString())),
                sql.raw("INSERT OR IGNORE INTO {p}accounts (uuid, name, player, created) VALUES (?, ?, ?, ?)", *args),
            )
        }
    }

    /** One ledger row. */
    fun ledger(entry: Entry): Statement = sql.raw(
        "INSERT INTO {p}transactions (time, uuid, currency, amount, balance, kind, source, reason, other) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        int(entry.time), text(entry.account.id.toString()), text(currency), int(entry.delta), int(entry.after),
        text(entry.cause.kind.name), text(entry.cause.source), text(entry.cause.reason),
        entry.other?.let { text(it.toString()) } ?: SqlValue.Null,
    )

    /** Every balance of the currency, and every account. */
    fun accountRow(id: PlayerRef): Statement = sql.raw("SELECT name, player FROM {p}accounts WHERE uuid = ?", text(id.toString()))

    /** A page of [id]'s history, newest first. */
    fun history(id: PlayerRef, offset: Long, limit: Int): Statement = sql.raw(
        "SELECT id, time, amount, balance, kind, source, reason, other FROM {p}transactions WHERE uuid = ? ORDER BY id DESC LIMIT $limit OFFSET $offset",
        text(id.toString()),
    )

    fun count(id: PlayerRef): Statement = sql.raw("SELECT COUNT(*) FROM {p}transactions WHERE uuid = ?", text(id.toString()))

    /** Accounts that changed other than by a starting balance or an import. */
    fun changed(): Statement = sql.raw("SELECT DISTINCT uuid FROM {p}transactions WHERE kind <> ? AND kind <> ?", text("START"), text("IMPORT"))

    /** Everything kept about [id]. */
    fun forget(id: PlayerRef): List<Statement> = listOf(
        sql.raw("DELETE FROM {p}transactions WHERE uuid = ?", text(id.toString())),
        sql.raw("DELETE FROM {p}balances WHERE uuid = ?", text(id.toString())),
        sql.raw("DELETE FROM {p}accounts WHERE uuid = ?", text(id.toString())),
    )

    /** The first ledger row at or after [from]: where a prune's slice starts. */
    fun firstFrom(from: Long): Statement = sql.raw("SELECT id, time FROM {p}transactions WHERE id >= ? ORDER BY id LIMIT 1", int(from))

    /** Deletes the slice's rows older than [before]. */
    fun pruneSlice(from: Long, until: Long, before: Long): Statement =
        sql.raw("DELETE FROM {p}transactions WHERE id >= ? AND id < ? AND time < ?", int(from), int(until), int(before))

    /** Whether the slice has a row at or after [before], where pruning stops. */
    fun newerIn(from: Long, until: Long, before: Long): Statement =
        sql.raw("SELECT id FROM {p}transactions WHERE id >= ? AND id < ? AND time >= ? LIMIT 1", int(from), int(until), int(before))
}
