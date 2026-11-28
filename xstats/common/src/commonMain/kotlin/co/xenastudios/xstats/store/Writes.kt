package co.xenastudios.xstats.store

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.db.ChangeFeed
import co.xenastudios.core.common.db.DbError
import co.xenastudios.xstats.data.Calendar

// What writes xStats' tables (and the reads only a save, a profile or a command needs), as
// extension functions of the Store in a file of their own: loaded with the first save or command,
// not as the server starts (decisions/2026-11-25-a-lighter-core.md). Store.kt has what a start reads.

/** What each stat of Minecraft's counters was last read from on this server, by stat name. */
internal fun Store.sources(q: Q): Map<String, String> {
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
internal fun Store.source(q: Q, stat: String, source: String, changed: Boolean) {
    val added = HashMap<String, Int>()
    q.inTransaction { tx ->
        val id = statId(tx, stat, added)
        if (changed) tx.exec("UPDATE {p}totals SET raw = -2 WHERE stat = ? AND server = ? AND raw <> -1", id, server)
        tx.exec("DELETE FROM {p}sources WHERE stat = ? AND server = ?", id, server)
        tx.exec("INSERT INTO {p}sources (stat, server, source) VALUES (?, ?, ?)", id, server, source)
    }
    remember(added)
}

/** One player's rows on this server, for working out what changed since. */
internal fun Store.local(q: Q, player: Int): List<Store.Local> {
    val stats = statNames(q)
    val rows = ArrayList<Store.Local>()
    for (row in q.rows("SELECT stat, value, raw FROM {p}totals WHERE player = ? AND server = ?", player, server)) {
        val stat = stats[row.num(0).toInt()] ?: continue
        rows += Store.Local(stat, row.num(1), row.num(2))
    }
    return rows
}

/**
 * Writes one save in one transaction, and tells the other servers of a network about it in the
 * same one. Returns the database id of each of the save's players, in order (new players get
 * theirs here).
 */
internal fun Store.save(q: Q, save: Store.Save, feed: ChangeFeed?): IntArray {
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

private fun Store.players(q: Q, rows: List<Store.PlayerRow>): IntArray {
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

private fun Store.update(q: Q, row: Store.PlayerRow, id: Int) {
    q.exec(
        "UPDATE {p}players SET name = ?, name_lower = ?, first_join = ?, last_seen = ?, hidden = CASE WHEN ? = 1 THEN ? ELSE hidden END, excluded = ? WHERE id = ?",
        row.name, row.name?.lowercase(), row.firstJoin, row.lastSeen, if (row.writeHidden) 1 else 0, if (row.hidden) 1 else 0, if (row.excluded) 1 else 0, id,
    )
}

/** A player's id, adding them if no server has yet. */
private fun Store.insertPlayer(q: Q, row: Store.PlayerRow): Int {
    val existing = playerId(q, row.uuid)
    if (existing > 0) {
        update(q, row, existing)
        return existing
    }
    q.exec(
        q.insertIgnore + " INTO {p}players (uuid, name, name_lower, first_join, last_seen, hidden, excluded) VALUES (?, ?, ?, ?, ?, ?, ?)",
        row.uuid.toString(), row.name, row.name?.lowercase(), row.firstJoin, row.lastSeen, if (row.hidden) 1 else 0, if (row.excluded) 1 else 0,
    )
    val id = playerId(q, row.uuid)
    if (id == 0) throw DbFailed(DbError(DbError.Kind.CONSTRAINT, "couldn't add the player ${row.uuid}"))
    return id
}

/** Adds to a daily or monthly row, making it if it's new. */
private fun Store.addTo(q: Q, table: String, column: String, player: Int, stat: Int, key: Int, delta: Long) {
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
internal fun Store.hide(q: Q, player: Int, uuid: PlayerRef, hidden: Boolean, feed: ChangeFeed?) {
    q.inTransaction { tx ->
        tx.exec("UPDATE {p}players SET hidden = ? WHERE id = ?", if (hidden) 1 else 0, player)
        tx.publish(feed, "player", uuid.toString())
    }
}

/** Whether a player is hidden, as stored: for a change another server made. */
internal fun Store.hidden(q: Q, uuid: PlayerRef): Boolean? = q.rows("SELECT hidden FROM {p}players WHERE uuid = ?", uuid.toString()).firstOrNull()?.let { it.num(0) != 0L }

/**
 * Adds this server's daily rows from before [beforeDay] into monthly rows and deletes them, a day
 * per transaction. Each server folds only its own rows, so two servers never add the same day
 * twice. Returns how many days were folded.
 */
internal fun Store.fold(q: Q, beforeDay: Int, maxDays: Int): Int {
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

/** A player's daily rows from [fromDay], added up across servers on a [network], then each server's all-time values. On a reader. */
internal fun Store.history(q: Q, player: Int, fromDay: Int, network: Boolean): Store.History {
    val stats = statNames(q)
    val days = ArrayList<Store.Day>()
    val daySql = "SELECT day, stat, SUM(delta) FROM {p}daily WHERE player = ? AND day >= ?" + (if (network) "" else " AND server = $server") +
        " GROUP BY day, stat ORDER BY day"
    for (row in q.rows(daySql, player, fromDay)) {
        days += Store.Day(row.num(0).toInt(), stats[row.num(1).toInt()] ?: continue, row.num(2))
    }
    val servers = ArrayList<Store.ServerValue>()
    if (network) {
        for (row in q.rows("SELECT s.name, t.stat, t.value FROM {p}totals t JOIN {p}servers s ON s.id = t.server WHERE t.player = ?", player)) {
            servers += Store.ServerValue(row.str(0) ?: continue, stats[row.num(1).toInt()] ?: continue, row.num(2))
        }
    }
    return Store.History(days, servers)
}

/** Every server's name, for the proxy's status. */
internal fun Store.servers(q: Q): List<String> = q.rows("SELECT name FROM {p}servers ORDER BY name").mapNotNull { it.str(0) }

/** Adds imported players and their values, never over a row already there: a player xStats already counts keeps their numbers. Returns how many values were added. */
internal fun Store.importRows(q: Q, players: List<Store.Imported>): Int {
    val before = totalRows(q)
    val added = HashMap<String, Int>()
    q.inTransaction { tx ->
        for (player in players) {
            val id = importPlayer(tx, player)
            for ((stat, value) in player.values) {
                tx.exec(
                    tx.insertIgnore + " INTO {p}totals (player, stat, server, value, raw, updated) VALUES (?, ?, ?, ?, ?, ?)",
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

private fun Store.importPlayer(q: Q, player: Store.Imported): Int {
    val existing = playerId(q, player.uuid)
    if (existing > 0) return existing
    return insertPlayer(q, Store.PlayerRow(player.uuid, 0, player.name, 0, player.lastSeen, false, false, false))
}

private fun Store.totalRows(q: Q): Long = q.rows("SELECT COUNT(*) FROM {p}totals WHERE server = ?", server).firstOrNull()?.num(0) ?: 0

internal fun Store.setFlag(q: Q, name: String) {
    q.exec(q.insertIgnore + " INTO {p}meta (name, value) VALUES (?, '1')", "$name:$server")
}

internal fun Store.addBoard(q: Q, board: Store.BoardRow): Int {
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

internal fun Store.removeBoard(q: Q, id: Int): Boolean = q.exec("DELETE FROM {p}boards WHERE id = ? AND server = ?", id, server) > 0

/** Rows in the totals, daily and monthly tables, for `/xstats status`. */
internal fun Store.rowCounts(q: Q): LongArray =
    longArrayOf(count(q, "totals"), count(q, "daily"), count(q, "monthly"))

private fun Store.count(q: Q, table: String): Long = q.rows("SELECT COUNT(*) FROM {p}$table").firstOrNull()?.num(0) ?: 0

/** `/xstats forget`: every row about one player, on every server. Returns how many were deleted. */
internal fun Store.forget(q: Q, uuid: PlayerRef, feed: ChangeFeed?): Long = q.inTransaction { tx ->
    val id = playerId(tx, uuid)
    if (id == 0) return@inTransaction 0L
    var deleted = 0L
    for (table in listOf("totals", "daily", "monthly")) deleted += tx.exec("DELETE FROM {p}$table WHERE player = ?", id)
    deleted += tx.exec("DELETE FROM {p}players WHERE id = ?", id)
    tx.publish(feed, "player", uuid.toString())
    deleted
}
