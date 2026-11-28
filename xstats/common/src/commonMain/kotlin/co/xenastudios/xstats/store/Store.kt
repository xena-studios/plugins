package co.xenastudios.xstats.store

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.db.ChangeFeed
import co.xenastudios.core.common.db.DbError
import co.xenastudios.core.common.db.Dialect
import co.xenastudios.core.common.db.Migration
import co.xenastudios.core.common.db.Row
import co.xenastudios.core.common.db.Sql
import co.xenastudios.core.common.db.SqlSession
import co.xenastudios.core.common.db.SqlValue
import co.xenastudios.core.common.db.Statement

/**
 * xStats' tables: 1.x's, byte for byte, so either jar reads the other's database
 * (decisions/2026-11-28-xstats-2.md). Schema 2, made with 1.x's own statements; 1.x's `meta` table
 * is the core's ([co.xenastudios.core.common.db.Migrations.Meta]), the same columns. Documented for
 * owners who build their own site on them (docs: xstats/database). Rows hold numbers only: players,
 * stats and servers are stored once each and referred to by id.
 *
 * - `totals`: each player's all-time value of each stat on each server, the last reading of its
 *   counter (`raw`, -1 for none, -2 for "the next reading is the new all-time value"), and when the
 *   value was last read or set (`updated`), so a gauge on a network is the one read last.
 * - `daily`: how much a stat went up on a day, only for stats with periods and only on days it
 *   changed. `day` is days since 1970-01-01 in the config's time zone.
 * - `monthly`: daily rows older than `periods.keep-daily-days`, added up per month.
 * - `sources`: what each of Minecraft's counters a stat adds up was, on each server.
 */
public object Tables {
    @Suppress("UNCHECKED_CAST")
    public val MIGRATIONS: List<Migration> = listOf(
        Migration(1, "players, stats, totals, daily and monthly rows, boards", co.xenastudios.xstats.Startup(co.xenastudios.xstats.STARTUP_TABLES) as (Sql) -> List<Statement>),
        Migration(2, "the source each stat was read from", co.xenastudios.xstats.Startup(co.xenastudios.xstats.STARTUP_SOURCES) as (Sql) -> List<Statement>),
    )
}

/** The statements that make the tables, read only when a migration runs: a new database, or 1.0.0's. */
internal object Ddl {
    private val STEP_1 = listOf(
        "CREATE TABLE IF NOT EXISTS {p}servers (id {rowid}, name VARCHAR(64) NOT NULL UNIQUE){table}",
        "CREATE TABLE IF NOT EXISTS {p}stats (id {rowid}, name VARCHAR(64) NOT NULL UNIQUE){table}",
        "CREATE TABLE IF NOT EXISTS {p}players (id {rowid}, uuid CHAR(36) NOT NULL UNIQUE, name VARCHAR(16)," +
            " name_lower VARCHAR(16), first_join BIGINT NOT NULL, last_seen BIGINT NOT NULL," +
            " hidden SMALLINT NOT NULL, excluded SMALLINT NOT NULL){table}",
        "CREATE INDEX {p}players_name ON {p}players (name_lower)",
        "CREATE TABLE IF NOT EXISTS {p}totals (player INT NOT NULL, stat INT NOT NULL, server INT NOT NULL," +
            " value BIGINT NOT NULL, raw BIGINT NOT NULL, updated BIGINT NOT NULL, PRIMARY KEY (player, stat, server)){table}",
        "CREATE INDEX {p}totals_stat ON {p}totals (stat, server, value)",
        "CREATE TABLE IF NOT EXISTS {p}daily (player INT NOT NULL, stat INT NOT NULL, server INT NOT NULL," +
            " day INT NOT NULL, delta BIGINT NOT NULL, PRIMARY KEY (player, stat, server, day)){table}",
        "CREATE INDEX {p}daily_day ON {p}daily (day)",
        "CREATE TABLE IF NOT EXISTS {p}monthly (player INT NOT NULL, stat INT NOT NULL, server INT NOT NULL," +
            " month INT NOT NULL, delta BIGINT NOT NULL, PRIMARY KEY (player, stat, server, month)){table}",
        "CREATE TABLE IF NOT EXISTS {p}boards (id {rowid}, server INT NOT NULL, world VARCHAR(64) NOT NULL," +
            " x DOUBLE NOT NULL, y DOUBLE NOT NULL, z DOUBLE NOT NULL, yaw FLOAT NOT NULL," +
            " stat VARCHAR(64) NOT NULL, period VARCHAR(16) NOT NULL){table}",
    )

    private const val STEP_2 = "CREATE TABLE IF NOT EXISTS {p}sources (stat INT NOT NULL, server INT NOT NULL, source VARCHAR(2048) NOT NULL," +
        " PRIMARY KEY (stat, server)){table}"

    internal fun first(sql: Sql): List<Statement> = STEP_1.map { sql.raw(fill(it, sql.dialect)) }

    internal fun second(sql: Sql): List<Statement> = listOf(sql.raw(fill(STEP_2, sql.dialect)))

    /** 1.x's placeholders: `{rowid}` a key the database numbers, `{table}` what follows a `CREATE TABLE`. */
    internal fun fill(text: String, dialect: Dialect): String = when (dialect) {
        is Dialect.Sqlite -> text.replace("{rowid}", "INTEGER PRIMARY KEY").replace("{table}", "")
        Dialect.MySql -> text.replace("{rowid}", "BIGINT PRIMARY KEY AUTO_INCREMENT").replace("{table}", " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4")
    }
}

/** A database step that went wrong, thrown inside a [Q] and turned back into its [DbError] at its edge. */
public class DbFailed(public val error: DbError) : RuntimeException(error.detail, null)

/**
 * Statements on one session, written as 1.x wrote its JDBC: each call runs at once and throws
 * [DbFailed] on an error, caught where the session's work ends ([Q.run]).
 */
public class Q(public val session: SqlSession, public val sql: Sql) {
    public val mysql: Boolean get() = session.dialect is Dialect.MySql

    /** Whether this SQLite has `ON CONFLICT ... DO UPDATE` (3.24+); MySQL has its own. */
    public val upserts: Boolean get() = (session.dialect as? Dialect.Sqlite)?.nativeUpsert ?: true

    public fun exec(text: String, vararg args: Any?): Int = ok(session.execute(statement(text, args)))

    public fun rows(text: String, vararg args: Any?): List<Row> = ok(session.query(statement(text, args)))

    public fun statement(text: String, args: Array<out Any?>): Statement {
        val values = ArrayList<SqlValue>(args.size)
        for (arg in args) values += value(arg)
        return sql.raw(text, *values.toTypedArray())
    }

    /** Tells the other servers about this change, in the same transaction: nothing when [feed] is off. */
    public fun publish(feed: ChangeFeed?, topic: String, payload: String) {
        if (feed == null || !feed.isOn) return
        ok(feed.publish(session, topic, payload))
    }

    /** [work] in one transaction. */
    public fun <T> inTransaction(work: (Q) -> T): T = ok(session.transaction { inner ->
        try {
            Outcome.Ok(work(Q(inner, sql)))
        } catch (e: DbFailed) {
            Outcome.Err(e.error)
        }
    })

    private fun <T> ok(outcome: Outcome<T, DbError>): T = when (outcome) {
        is Outcome.Ok -> outcome.value
        is Outcome.Err -> throw DbFailed(outcome.error)
    }

    /** An id from a two-column dictionary table (servers, stats), adding the name if it's new. */
    public fun dictionary(table: String, name: String): Int {
        val select = "SELECT id FROM {p}$table WHERE name = ?"
        rows(select, name).firstOrNull()?.let { return it.num(0).toInt() }
        // Another server may add it at the same moment: ignore that, then read whichever won.
        exec(insertIgnore + " INTO {p}$table (name) VALUES (?)", name)
        return rows(select, name).firstOrNull()?.num(0)?.toInt()
            ?: throw DbFailed(DbError(DbError.Kind.CONSTRAINT, "couldn't add $name to $table"))
    }

    internal val insertIgnore: String get() = if (mysql) "INSERT IGNORE" else "INSERT OR IGNORE"

    public companion object {
        /** The SQLite file, in the plugin's folder: 1.x's. */
        public const val FILE: String = "stats.db"

        public fun value(arg: Any?): SqlValue = when (arg) {
            null -> SqlValue.Null
            is String -> SqlValue.Text(arg)
            is Int -> SqlValue.Integer(arg.toLong())
            is Long -> SqlValue.Integer(arg)
            is Boolean -> SqlValue.Integer(if (arg) 1 else 0)
            is Double -> SqlValue.Real(arg)
            is Float -> SqlValue.Real(arg.toDouble())
            is SqlValue -> arg
            else -> SqlValue.Text(arg.toString())
        }

        /** [work] on [session], its error as an [Outcome]. */
        public fun <T> run(session: SqlSession, sql: Sql, work: (Q) -> T): Outcome<T, DbError> = try {
            Outcome.Ok(work(Q(session, sql)))
        } catch (e: DbFailed) {
            Outcome.Err(e.error)
        }
    }
}

/** A row's column [i] as a number. */
internal fun Row.num(i: Int): Long = when (val v = values[i]) {
    is SqlValue.Integer -> v.value
    is SqlValue.Real -> v.value.toLong()
    is SqlValue.Text -> v.value.trim().toLongOrNull() ?: 0
    else -> 0
}

/** A row's column [i] as text, or `null`. */
internal fun Row.str(i: Int): String? = when (val v = values[i]) {
    is SqlValue.Text -> v.value
    is SqlValue.Integer -> v.value.toString()
    is SqlValue.Real -> v.value.toString()
    is SqlValue.Bytes -> v.value.decodeToString()
    SqlValue.Null -> null
}

internal fun Row.dbl(i: Int): Double = when (val v = values[i]) {
    is SqlValue.Real -> v.value
    is SqlValue.Integer -> v.value.toDouble()
    is SqlValue.Text -> v.value.toDoubleOrNull() ?: 0.0
    else -> 0.0
}

/**
 * Every read and write of xStats' tables, as 1.x's `Store` did them, statement for statement. Every
 * function runs on a [Q]: on the database's writer, or a reader for the reads that say so.
 */
public class Store(
    /** This server's id in `servers`. */
    public val server: Int,
    public val serverName: String,
) {
    /** Stat ids by name, as stored; filled as they're read or added. Any thread reads, the writer adds. */
    internal val statIds = co.xenastudios.core.common.concurrentMap<String, Int>()

    // ---- what a save writes ----

    /**
     * A player's row. [writeHidden] is whether to write `hidden`: only when `/stats private` couldn't
     * be saved at once. Otherwise it's left as stored, so a save can't undo a change another server
     * made that hasn't reached this one yet.
     */
    public class PlayerRow(
        public val uuid: PlayerRef,
        public val dbId: Int,
        public val name: String?,
        public val firstJoin: Long,
        public val lastSeen: Long,
        public val hidden: Boolean,
        public val excluded: Boolean,
        public val writeHidden: Boolean,
    )

    /** This server's value of a stat for the save's [player] (an index into its players). [readAt] decides the latest value on a network; 0 for the save's time. */
    public class TotalRow(public val player: Int, public val stat: String, public val value: Long, public val raw: Long, public val readAt: Long)

    /** How much a stat went up today. */
    public class DailyRow(public val player: Int, public val stat: String, public val delta: Long)

    public class Save(
        public val players: List<PlayerRow>,
        public val totals: List<TotalRow>,
        public val daily: List<DailyRow>,
        public val day: Int,
        /** When it's saved: written to each total row as when it was updated. */
        public val at: Long,
    )

    // ---- what loading reads ----

    public class LoadedPlayer(
        public val dbId: Int,
        public val uuid: PlayerRef,
        public val name: String?,
        public val firstJoin: Long,
        public val lastSeen: Long,
        public val hidden: Boolean,
        public val excluded: Boolean,
    )

    /** A value for a player and stat: all time ([value], added up across servers on a network; [latest] the value of the server that wrote it last), or one day's rise. */
    public class Value(public val player: Int, public val stat: String, public val day: Int, public val value: Long, public val latest: Long)

    public class Loaded(public val players: List<LoadedPlayer>, public val totals: List<Value>, public val days: List<Value>)

    /** This server's row for one stat of one player. */
    public class Local(public val stat: String, public val value: Long, public val raw: Long)

    // ---- ids ----

    /** The database id of a stat, adding it the first time. New ids are remembered once their transaction commits ([remember]). */
    public fun statId(q: Q, stat: String, added: MutableMap<String, Int>): Int {
        statIds[stat]?.let { return it }
        added[stat]?.let { return it }
        val id = q.dictionary("stats", stat)
        added[stat] = id
        return id
    }

    public fun remember(added: Map<String, Int>) {
        statIds.putAll(added)
    }



    internal fun statNames(q: Q): Map<Int, String> {
        val names = HashMap<Int, String>()
        for (row in q.rows("SELECT id, name FROM {p}stats")) {
            val name = row.str(1) ?: continue
            names[row.num(0).toInt()] = name
            statIds[name] = row.num(0).toInt()
        }
        return names
    }

    // ---- loading ----

    /** Every player, their all-time values and their daily rows from [fromDay] on: every server's added up on a [network], else only this server's. */
    public fun load(q: Q, network: Boolean, fromDay: Int): Loaded {
        val stats = statNames(q)
        val players = ArrayList<LoadedPlayer>()
        for (row in q.rows("SELECT id, uuid, name, first_join, last_seen, hidden, excluded FROM {p}players")) {
            val uuid = PlayerRef.parseOrNull(row.str(1) ?: continue) ?: continue
            players += LoadedPlayer(row.num(0).toInt(), uuid, row.str(2), row.num(3), row.num(4), row.num(5) != 0L, row.num(6) != 0L)
        }
        // On a network: every server's rows added up, and the one with the latest updated (by the
        // primary key's first two columns, so a lookup per player and stat, not a scan).
        val totalsSql = if (network) {
            "SELECT t.player, t.stat, SUM(t.value), (SELECT l.value FROM {p}totals l" +
                " WHERE l.player = t.player AND l.stat = t.stat ORDER BY l.updated DESC, l.server DESC LIMIT 1)" +
                " FROM {p}totals t GROUP BY t.player, t.stat"
        } else {
            "SELECT player, stat, value, value FROM {p}totals WHERE server = $server"
        }
        val totals = ArrayList<Value>()
        for (row in q.rows(totalsSql)) {
            val stat = stats[row.num(1).toInt()] ?: continue
            totals += Value(row.num(0).toInt(), stat, 0, row.num(2), row.num(3))
        }
        val days = ArrayList<Value>()
        val daySql = "SELECT player, stat, day, SUM(delta) FROM {p}daily WHERE day >= ?" + (if (network) "" else " AND server = $server") +
            " GROUP BY player, stat, day"
        for (row in q.rows(daySql, fromDay)) {
            val stat = stats[row.num(1).toInt()] ?: continue
            val delta = row.num(3)
            days += Value(row.num(0).toInt(), stat, row.num(2).toInt(), delta, delta)
        }
        return Loaded(players, totals, days)
    }


    // ---- saving ----





    public fun playerId(q: Q, uuid: PlayerRef): Int = q.rows("SELECT id FROM {p}players WHERE uuid = ?", uuid.toString()).firstOrNull()?.num(0)?.toInt() ?: 0




    // ---- folding old days into months ----


    // ---- for profile pages ----

    /** One day's rise of one stat, for a profile's history. */
    public class Day(public val day: Int, public val stat: String, public val delta: Long)

    /** One server's all-time value of one stat, for a profile in network mode. */
    public class ServerValue(public val server: String, public val stat: String, public val value: Long)

    /** A profile's history and, in network mode, each server's numbers. */
    public class History(public val days: List<Day>, public val servers: List<ServerValue>)



    // ---- the import ----

    /** One player from a stats file: values by stat id; [counters] says which are Minecraft's own. */
    public class Imported(
        public val uuid: PlayerRef,
        public val name: String?,
        public val lastSeen: Long,
        public val values: Map<String, Long>,
        public val counters: Map<String, Boolean>,
    )




    /** A flag kept in the meta table, such as whether the import has run on this server. */
    public fun flag(q: Q, name: String): Boolean = q.rows("SELECT value FROM {p}meta WHERE name = ?", "$name:$server").isNotEmpty()


    // ---- in-world boards ----

    /** A board placed in a world. */
    public class BoardRow(
        public val id: Int,
        public val world: String,
        public val x: Double,
        public val y: Double,
        public val z: Double,
        public val yaw: Float,
        public val stat: String,
        public val period: String,
    )

    public fun boards(q: Q): List<BoardRow> =
        q.rows("SELECT id, world, x, y, z, yaw, stat, period FROM {p}boards WHERE server = ? ORDER BY id", server).map {
            BoardRow(it.num(0).toInt(), it.str(1) ?: "", it.dbl(2), it.dbl(3), it.dbl(4), it.dbl(5).toFloat(), it.str(6) ?: "", it.str(7) ?: "all")
        }






}
