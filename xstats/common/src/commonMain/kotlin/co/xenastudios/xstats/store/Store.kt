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
import co.xenastudios.xstats.data.Calendar

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

    public companion object {
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
    private val statIds = co.xenastudios.core.common.concurrentMap<String, Int>()

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
        val id = dictionary(q, "stats", stat)
        added[stat] = id
        return id
    }

    public fun remember(added: Map<String, Int>) {
        statIds.putAll(added)
    }

    /** What each stat of Minecraft's counters was last read from on this server, by stat name. */
    public fun sources(q: Q): Map<String, String> {
        val found = HashMap<String, String>()
        for (row in q.rows("SELECT s.name, x.source FROM {p}sources x JOIN {p}stats s ON s.id = x.stat WHERE x.server = ?", server)) {
            found[row.str(0) ?: continue] = row.str(1) ?: ""
        }
        return found
    }

    /**
     * Records the source a stat is read from now. If it had another one, every player's last reading
     * of it on this server is marked `-2`: the next reading is the new counter's value, not a rise on
     * the old one's.
     */
    public fun source(q: Q, stat: String, source: String, changed: Boolean) {
        val added = HashMap<String, Int>()
        q.inTransaction { tx ->
            val id = statId(tx, stat, added)
            if (changed) tx.exec("UPDATE {p}totals SET raw = -2 WHERE stat = ? AND server = ? AND raw <> -1", id, server)
            tx.exec("DELETE FROM {p}sources WHERE stat = ? AND server = ?", id, server)
            tx.exec("INSERT INTO {p}sources (stat, server, source) VALUES (?, ?, ?)", id, server, source)
        }
        remember(added)
    }

    private fun statNames(q: Q): Map<Int, String> {
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

    /** One player's rows on this server, for working out what changed since. */
    public fun local(q: Q, player: Int): List<Local> {
        val stats = statNames(q)
        val rows = ArrayList<Local>()
        for (row in q.rows("SELECT stat, value, raw FROM {p}totals WHERE player = ? AND server = ?", player, server)) {
            val stat = stats[row.num(0).toInt()] ?: continue
            rows += Local(stat, row.num(1), row.num(2))
        }
        return rows
    }

    // ---- saving ----

    /**
     * Writes one save in one transaction, and tells the other servers of a network about it in the
     * same one. Returns the database id of each of the save's players, in order (new players get
     * theirs here).
     */
    public fun save(q: Q, save: Save, feed: ChangeFeed?): IntArray {
        val added = HashMap<String, Int>()
        val ids = q.inTransaction { tx ->
            val ids = players(tx, save.players)
            for (row in save.totals) {
                val args = arrayOf<Any?>(ids[row.player], statId(tx, row.stat, added), server, row.value, row.raw, if (row.readAt > 0) row.readAt else save.at)
                when {
                    tx.mysql -> tx.exec(
                        "INSERT INTO {p}totals (player, stat, server, value, raw, updated) VALUES (?, ?, ?, ?, ?, ?)" +
                            " ON DUPLICATE KEY UPDATE value = VALUES(value), raw = VALUES(raw), updated = VALUES(updated)", *args,
                    )
                    tx.upserts -> tx.exec(
                        "INSERT INTO {p}totals (player, stat, server, value, raw, updated) VALUES (?, ?, ?, ?, ?, ?)" +
                            " ON CONFLICT (player, stat, server) DO UPDATE SET value = excluded.value, raw = excluded.raw, updated = excluded.updated", *args,
                    )
                    else -> {
                        tx.exec("UPDATE {p}totals SET value = ?, raw = ?, updated = ? WHERE player = ? AND stat = ? AND server = ?", args[3], args[4], args[5], args[0], args[1], args[2])
                        tx.exec("INSERT OR IGNORE INTO {p}totals (player, stat, server, value, raw, updated) VALUES (?, ?, ?, ?, ?, ?)", *args)
                    }
                }
            }
            for (row in save.daily) addTo(tx, "daily", "day", ids[row.player], statId(tx, row.stat, added), save.day, row.delta)
            if (save.players.isNotEmpty()) tx.publish(feed, "saved", serverName)
            ids
        }
        remember(added)
        return ids
    }

    private fun players(q: Q, rows: List<PlayerRow>): IntArray {
        val ids = IntArray(rows.size)
        for ((i, row) in rows.withIndex()) {
            if (row.dbId == 0) {
                ids[i] = insertPlayer(q, row)
                continue
            }
            ids[i] = row.dbId
            update(q, row, row.dbId)
        }
        return ids
    }

    private fun update(q: Q, row: PlayerRow, id: Int) {
        q.exec(
            "UPDATE {p}players SET name = ?, name_lower = ?, first_join = ?, last_seen = ?, hidden = CASE WHEN ? = 1 THEN ? ELSE hidden END, excluded = ? WHERE id = ?",
            row.name, row.name?.lowercase(), row.firstJoin, row.lastSeen, if (row.writeHidden) 1 else 0, if (row.hidden) 1 else 0, if (row.excluded) 1 else 0, id,
        )
    }

    /** A player's id, adding them if no server has yet. */
    private fun insertPlayer(q: Q, row: PlayerRow): Int {
        val existing = playerId(q, row.uuid)
        if (existing > 0) {
            update(q, row, existing)
            return existing
        }
        q.exec(
            insertIgnore(q) + " INTO {p}players (uuid, name, name_lower, first_join, last_seen, hidden, excluded) VALUES (?, ?, ?, ?, ?, ?, ?)",
            row.uuid.toString(), row.name, row.name?.lowercase(), row.firstJoin, row.lastSeen, if (row.hidden) 1 else 0, if (row.excluded) 1 else 0,
        )
        val id = playerId(q, row.uuid)
        if (id == 0) throw DbFailed(DbError(DbError.Kind.CONSTRAINT, "couldn't add the player ${row.uuid}"))
        return id
    }

    public fun playerId(q: Q, uuid: PlayerRef): Int = q.rows("SELECT id FROM {p}players WHERE uuid = ?", uuid.toString()).firstOrNull()?.num(0)?.toInt() ?: 0

    /** Adds to a daily or monthly row, making it if it's new. */
    private fun addTo(q: Q, table: String, column: String, player: Int, stat: Int, key: Int, delta: Long) {
        val insert = "INSERT INTO {p}$table (player, stat, server, $column, delta) VALUES (?, ?, ?, ?, ?)"
        when {
            q.mysql -> q.exec("$insert ON DUPLICATE KEY UPDATE delta = delta + VALUES(delta)", player, stat, server, key, delta)
            q.upserts -> q.exec("$insert ON CONFLICT (player, stat, server, $column) DO UPDATE SET delta = delta + excluded.delta", player, stat, server, key, delta)
            else -> {
                val changed = q.exec("UPDATE {p}$table SET delta = delta + ? WHERE player = ? AND stat = ? AND server = ? AND $column = ?", delta, player, stat, server, key)
                if (changed == 0) q.exec(insert, player, stat, server, key, delta)
            }
        }
    }

    /** Sets whether a stored player is hidden from the website, right away, and tells the network. */
    public fun hide(q: Q, player: Int, uuid: PlayerRef, hidden: Boolean, feed: ChangeFeed?) {
        q.inTransaction { tx ->
            tx.exec("UPDATE {p}players SET hidden = ? WHERE id = ?", if (hidden) 1 else 0, player)
            tx.publish(feed, "player", uuid.toString())
        }
    }

    /** Whether a player is hidden, as stored: for a change another server made. */
    public fun hidden(q: Q, uuid: PlayerRef): Boolean? = q.rows("SELECT hidden FROM {p}players WHERE uuid = ?", uuid.toString()).firstOrNull()?.let { it.num(0) != 0L }

    // ---- folding old days into months ----

    /**
     * Adds this server's daily rows from before [beforeDay] into monthly rows and deletes them, a day
     * per transaction. Each server folds only its own rows, so two servers never add the same day
     * twice. Returns how many days were folded.
     */
    public fun fold(q: Q, beforeDay: Int, maxDays: Int): Int {
        val days = q.rows("SELECT DISTINCT day FROM {p}daily WHERE server = ? AND day < ? ORDER BY day", server, beforeDay).take(maxDays).map { it.num(0).toInt() }
        for (day in days) {
            q.inTransaction { tx ->
                val rows = tx.rows("SELECT player, stat, delta FROM {p}daily WHERE server = ? AND day = ?", server, day)
                val month = Calendar.month(day)
                for (row in rows) addTo(tx, "monthly", "month", row.num(0).toInt(), row.num(1).toInt(), month, row.num(2))
                tx.exec("DELETE FROM {p}daily WHERE server = ? AND day = ?", server, day)
            }
        }
        return days.size
    }

    // ---- for profile pages ----

    /** One day's rise of one stat, for a profile's history. */
    public class Day(public val day: Int, public val stat: String, public val delta: Long)

    /** One server's all-time value of one stat, for a profile in network mode. */
    public class ServerValue(public val server: String, public val stat: String, public val value: Long)

    /** A profile's history and, in network mode, each server's numbers. */
    public class History(public val days: List<Day>, public val servers: List<ServerValue>)

    /** A player's daily rows from [fromDay], added up across servers on a [network], then each server's all-time values. On a reader. */
    public fun history(q: Q, player: Int, fromDay: Int, network: Boolean): History {
        val stats = statNames(q)
        val days = ArrayList<Day>()
        val daySql = "SELECT day, stat, SUM(delta) FROM {p}daily WHERE player = ? AND day >= ?" + (if (network) "" else " AND server = $server") +
            " GROUP BY day, stat ORDER BY day"
        for (row in q.rows(daySql, player, fromDay)) {
            days += Day(row.num(0).toInt(), stats[row.num(1).toInt()] ?: continue, row.num(2))
        }
        val servers = ArrayList<ServerValue>()
        if (network) {
            for (row in q.rows("SELECT s.name, t.stat, t.value FROM {p}totals t JOIN {p}servers s ON s.id = t.server WHERE t.player = ?", player)) {
                servers += ServerValue(row.str(0) ?: continue, stats[row.num(1).toInt()] ?: continue, row.num(2))
            }
        }
        return History(days, servers)
    }

    /** Every server's name, for the proxy's status. */
    public fun servers(q: Q): List<String> = q.rows("SELECT name FROM {p}servers ORDER BY name").mapNotNull { it.str(0) }

    // ---- the import ----

    /** One player from a stats file: values by stat id; [counters] says which are Minecraft's own. */
    public class Imported(
        public val uuid: PlayerRef,
        public val name: String?,
        public val lastSeen: Long,
        public val values: Map<String, Long>,
        public val counters: Map<String, Boolean>,
    )

    /** Adds imported players and their values, never over a row already there: a player xStats already counts keeps their numbers. Returns how many values were added. */
    public fun importRows(q: Q, players: List<Imported>): Int {
        val before = totalRows(q)
        val added = HashMap<String, Int>()
        q.inTransaction { tx ->
            for (player in players) {
                val id = importPlayer(tx, player)
                for ((stat, value) in player.values) {
                    tx.exec(
                        insertIgnore(tx) + " INTO {p}totals (player, stat, server, value, raw, updated) VALUES (?, ?, ?, ?, ?, ?)",
                        id, statId(tx, stat, added), server, value, if (player.counters[stat] == true) value else -1L,
                        // Older than anything counted since: when they were last seen in the files.
                        player.lastSeen,
                    )
                }
                // What xStats counts itself (playtime, sessions) from a player who joined before the
                // import: their row holds only what was counted since install, and the file has all of
                // it. The higher wins, so importing again adds nothing.
                for ((stat, value) in player.values) {
                    if (player.counters[stat] == true) continue
                    tx.exec("UPDATE {p}totals SET value = ? WHERE player = ? AND stat = ? AND server = ? AND value < ?", value, id, statId(tx, stat, added), server, value)
                }
            }
        }
        remember(added)
        return (totalRows(q) - before).toInt()
    }

    private fun importPlayer(q: Q, player: Imported): Int {
        val existing = playerId(q, player.uuid)
        if (existing > 0) return existing
        return insertPlayer(q, PlayerRow(player.uuid, 0, player.name, 0, player.lastSeen, false, false, false))
    }

    private fun totalRows(q: Q): Long = q.rows("SELECT COUNT(*) FROM {p}totals WHERE server = ?", server).firstOrNull()?.num(0) ?: 0

    /** A flag kept in the meta table, such as whether the import has run on this server. */
    public fun flag(q: Q, name: String): Boolean = q.rows("SELECT value FROM {p}meta WHERE name = ?", "$name:$server").isNotEmpty()

    public fun setFlag(q: Q, name: String) {
        q.exec(insertIgnore(q) + " INTO {p}meta (name, value) VALUES (?, '1')", "$name:$server")
    }

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

    public fun addBoard(q: Q, board: BoardRow): Int {
        val id = q.inTransaction { tx ->
            tx.exec(
                "INSERT INTO {p}boards (server, world, x, y, z, yaw, stat, period) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                server, board.world, board.x, board.y, board.z, board.yaw, board.stat, board.period,
            )
            tx.rows("SELECT MAX(id) FROM {p}boards WHERE server = ?", server).firstOrNull()?.num(0)?.toInt() ?: 0
        }
        if (id == 0) throw DbFailed(DbError(DbError.Kind.CONSTRAINT, "the new board got no id"))
        return id
    }

    public fun removeBoard(q: Q, id: Int): Boolean = q.exec("DELETE FROM {p}boards WHERE id = ? AND server = ?", id, server) > 0

    /** Rows in the totals, daily and monthly tables, for `/xstats status`. */
    public fun rowCounts(q: Q): LongArray =
        longArrayOf(count(q, "totals"), count(q, "daily"), count(q, "monthly"))

    private fun count(q: Q, table: String): Long = q.rows("SELECT COUNT(*) FROM {p}$table").firstOrNull()?.num(0) ?: 0

    /** `/xstats forget`: every row about one player, on every server. Returns how many were deleted. */
    public fun forget(q: Q, uuid: PlayerRef, feed: ChangeFeed?): Long = q.inTransaction { tx ->
        val id = playerId(tx, uuid)
        if (id == 0) return@inTransaction 0L
        var deleted = 0L
        for (table in listOf("totals", "daily", "monthly")) deleted += tx.exec("DELETE FROM {p}$table WHERE player = ?", id)
        deleted += tx.exec("DELETE FROM {p}players WHERE id = ?", id)
        tx.publish(feed, "player", uuid.toString())
        deleted
    }

    public companion object {
        /** The SQLite file, in the plugin's folder: 1.x's. */
        public const val FILE: String = "stats.db"

        /** An id from a two-column dictionary table (servers, stats), adding the name if it's new. */
        public fun dictionary(q: Q, table: String, name: String): Int {
            val select = "SELECT id FROM {p}$table WHERE name = ?"
            q.rows(select, name).firstOrNull()?.let { return it.num(0).toInt() }
            // Another server may add it at the same moment: ignore that, then read whichever won.
            q.exec(insertIgnore(q) + " INTO {p}$table (name) VALUES (?)", name)
            return q.rows(select, name).firstOrNull()?.num(0)?.toInt()
                ?: throw DbFailed(DbError(DbError.Kind.CONSTRAINT, "couldn't add $name to $table"))
        }

        private fun insertIgnore(q: Q): String = if (q.mysql) "INSERT IGNORE" else "INSERT OR IGNORE"
    }
}
