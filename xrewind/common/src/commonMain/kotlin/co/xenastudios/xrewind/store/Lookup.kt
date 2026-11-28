package co.xenastudios.xrewind.store

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.db.DbError
import co.xenastudios.core.common.db.Row
import co.xenastudios.core.common.db.Sql
import co.xenastudios.core.common.db.SqlSession
import co.xenastudios.core.common.db.SqlValue
import co.xenastudios.core.common.db.Statement
import co.xenastudios.xrewind.api.Action
import co.xenastudios.xrewind.log.Actions
import co.xenastudios.xrewind.log.StoredTime
import co.xenastudios.xrewind.query.Center
import co.xenastudios.xrewind.query.Search

/**
 * One logged change as a lookup reads it, names filled in. [action] is the table's action number
 * (for text, its kind); [thing] what it was about (a block state before, an item or entity type,
 * a message); [after] the block state after, for blocks; [extra] for rollbacks, a block's contents
 * or a container change's item.
 */
public class LoggedRow(
    public val table: Table, public val id: Long, public val time: Int, public val user: String, public val world: String,
    public val x: Int, public val y: Int, public val z: Int, public val action: Int, public val thing: String?, public val after: String?,
    public val rolledBack: Boolean, public val amount: Int, public val extra: ByteArray?,
) {
    public enum class Table { BLOCK, CONTAINER, KILL, TEXT, SESSION }

    /** What it is, as the API names it. */
    public val kind: Action get() = when (table) {
        Table.BLOCK -> when (action) {
            Actions.BREAK -> Action.BREAK
            Actions.CLICK -> Action.CLICK
            else -> Action.PLACE
        }
        Table.CONTAINER -> if (action == Actions.ADD) Action.ADD else Action.REMOVE
        Table.KILL -> Action.KILL
        Table.TEXT -> when (action) {
            Actions.SIGN -> Action.SIGN
            Actions.CHAT -> Action.CHAT
            else -> Action.COMMAND
        }
        Table.SESSION -> if (action == Actions.JOIN) Action.JOIN else Action.QUIT
    }
}

/** A page of rows, and how many match in all (0 when they weren't counted). */
public class Page(public val rows: List<LoggedRow>, public val total: Long)

/**
 * Runs a [Search] against the database, newest first, across every table it covers (1.x's
 * `Lookup`, its SQL unchanged). On the database's readers: every call waits for the database.
 */
public object Lookup {
    /** One page of the changes matching [search]; [page] from 1. */
    public fun page(session: SqlSession, sql: Sql, search: Search, center: Center?, now: Int, page: Int, perPage: Int, count: Boolean = true): Outcome<Page, DbError> {
        val skip = (page - 1) * perPage
        val tables = ArrayList<Select>()
        if (search.wantsBlocks) tables += blocks(sql, search, center, now, withContents = false)
        if (search.wantsContainers) tables += containers(sql, search, center, now, withItems = false)
        if (search.wantsKills) tables += kills(sql, search, center, now)
        if (search.wantsText) tables += texts(sql, search, center, now)
        if (search.wantsSessions) tables += sessions(sql, search, center, now)
        val rows = ArrayList<LoggedRow>()
        var total = 0L
        for (select in tables) {
            if (count) {
                when (val counted = session.query(Statement("SELECT COUNT(*)" + select.from + select.where, select.args))) {
                    is Outcome.Err -> return counted
                    is Outcome.Ok -> total += counted.value.firstOrNull()?.long(0) ?: 0
                }
            }
            when (val read = rows(session, select, " ORDER BY b.time DESC, b.id DESC LIMIT ${skip + perPage}")) {
                is Outcome.Err -> return read
                is Outcome.Ok -> rows += read.value
            }
        }
        rows.sortWith(compareByDescending<LoggedRow> { it.time }.thenByDescending { it.id })
        val shown = rows.subList(minOf(skip, rows.size), minOf(skip + perPage, rows.size))
        return Outcome.Ok(Page(shown.toList(), total))
    }

    /**
     * Every block change matching [search] whose rolled_back flag is [rolledBack], oldest first,
     * with contents, for a rollback or restore. By time, not id: history imported from CoreProtect
     * has new ids but old times.
     */
    public fun allBlocks(session: SqlSession, sql: Sql, search: Search, center: Center?, now: Int, rolledBack: Boolean): Outcome<List<LoggedRow>, DbError> =
        all(session, blocks(sql, search, center, now, withContents = true), rolledBack)

    /** Every container change matching [search], oldest first, with its item, for a rollback or restore. */
    public fun allContainers(session: SqlSession, sql: Sql, search: Search, center: Center?, now: Int, rolledBack: Boolean): Outcome<List<LoggedRow>, DbError> =
        all(session, containers(sql, search, center, now, withItems = true), rolledBack)

    private fun all(session: SqlSession, select: Select, rolledBack: Boolean): Outcome<List<LoggedRow>, DbError> {
        select.where.append(" AND b.rolled_back = ?")
        select.args += int(if (rolledBack) 1 else 0)
        return rows(session, select, " ORDER BY b.time, b.id")
    }

    // A SELECT's columns, its FROM and WHERE, and the values for the WHERE's ?s. Every SELECT has
    // the same columns, by these names.
    private class Select(val table: LoggedRow.Table, val select: String, val from: String) {
        val where = StringBuilder(" WHERE 1 = 1")
        val args = ArrayList<SqlValue>()
    }

    private fun columns(thing: String, after: String, rolledBack: String, amount: String, extra: String, action: String = "b.action") =
        "SELECT b.id AS id, b.time AS time, u.name AS uname, w.name AS wname, b.x AS x, b.y AS y, b.z AS z, $action AS act," +
            " $thing AS thing, $after AS after, $rolledBack AS rb, $amount AS amount, $extra AS extra"

    private fun blocks(sql: Sql, search: Search, center: Center?, now: Int, withContents: Boolean): Select {
        val from = sql.raw(" FROM {p}block b JOIN {p}user u ON u.id = b.user JOIN {p}world w ON w.id = b.world" +
            " JOIN {p}state os ON os.id = b.old_state JOIN {p}state ns ON ns.id = b.new_state" +
            if (withContents) " LEFT JOIN {p}item it ON it.id = b.extra" else "").sql
        val select = Select(LoggedRow.Table.BLOCK, columns("os.data", "ns.data", "b.rolled_back", "0", if (withContents) "it.data" else "NULL"), from)
        common(select, search, center, now)
        val actions = ArrayList<Int>()
        if (search.wants(Action.BREAK)) actions += Actions.BREAK
        if (search.wants(Action.PLACE)) actions += Actions.PLACE
        if (search.wants(Action.CLICK)) actions += Actions.CLICK
        within(select, "b.action", actions.map(::int), false)
        if (search.blocks.isNotEmpty()) {
            select.where.append(" AND ")
            involved(select, search.blocks)
        }
        if (search.excludedBlocks.isNotEmpty()) {
            select.where.append(" AND NOT ")
            involved(select, search.excludedBlocks)
        }
        return select
    }

    private fun containers(sql: Sql, search: Search, center: Center?, now: Int, withItems: Boolean): Select {
        val from = sql.raw(" FROM {p}container b JOIN {p}user u ON u.id = b.user JOIN {p}world w ON w.id = b.world" +
            " JOIN {p}state m ON m.id = b.material" + if (withItems) " JOIN {p}item it ON it.id = b.item" else "").sql
        val select = Select(LoggedRow.Table.CONTAINER, columns("m.data", "NULL", "b.rolled_back", "b.amount", if (withItems) "it.data" else "NULL"), from)
        common(select, search, center, now)
        val actions = ArrayList<Int>()
        if (search.wants(Action.ADD)) actions += Actions.ADD
        if (search.wants(Action.REMOVE)) actions += Actions.REMOVE
        within(select, "b.action", actions.map(::int), false)
        names(select, "m.data", search)
        return select
    }

    private fun kills(sql: Sql, search: Search, center: Center?, now: Int): Select {
        val from = sql.raw(" FROM {p}entity b JOIN {p}user u ON u.id = b.user JOIN {p}world w ON w.id = b.world JOIN {p}state t ON t.id = b.type").sql
        val select = Select(LoggedRow.Table.KILL, columns("t.data", "NULL", "b.rolled_back", "0", "NULL"), from)
        common(select, search, center, now)
        // i: and e: name blocks and items, never mobs: a lookup for oak logs shows no kills.
        names(select, "t.data", search)
        return select
    }

    private fun texts(sql: Sql, search: Search, center: Center?, now: Int): Select {
        val from = sql.raw(" FROM {p}text b JOIN {p}user u ON u.id = b.user JOIN {p}world w ON w.id = b.world").sql
        val select = Select(LoggedRow.Table.TEXT, columns("b.message", "NULL", "0", "0", "NULL", action = "b.kind"), from)
        common(select, search, center, now)
        val kinds = ArrayList<Int>()
        if (search.wants(Action.SIGN)) kinds += Actions.SIGN
        if (search.wants(Action.CHAT)) kinds += Actions.CHAT
        if (search.wants(Action.COMMAND)) kinds += Actions.COMMAND
        within(select, "b.kind", kinds.map(::int), false)
        return select
    }

    private fun sessions(sql: Sql, search: Search, center: Center?, now: Int): Select {
        val from = sql.raw(" FROM {p}session b JOIN {p}user u ON u.id = b.user JOIN {p}world w ON w.id = b.world").sql
        val select = Select(LoggedRow.Table.SESSION, columns("NULL", "NULL", "0", "0", "NULL"), from)
        common(select, search, center, now)
        val actions = ArrayList<Int>()
        if (search.wants(Action.JOIN)) actions += Actions.JOIN
        if (search.wants(Action.QUIT)) actions += Actions.QUIT
        within(select, "b.action", actions.map(::int), false)
        return select
    }

    // Time, place and who: the same for every table.
    private fun common(select: Select, search: Search, center: Center?, now: Int) {
        if (search.seconds > 0) {
            select.where.append(" AND b.time >= ?")
            select.args += int(StoredTime.ago(now, search.seconds))
        }
        search.until?.let {
            select.where.append(" AND b.time <= ?")
            select.args += int(it)
        }
        val box = search.box
        val world = box?.world ?: search.world ?: center?.world
        if (search.radius != Search.GLOBAL && world != null) {
            select.where.append(" AND w.name = ?")
            select.args += SqlValue.Text(world)
        }
        if (search.radius >= 0 && center != null) {
            val r = search.radius
            select.where.append(" AND b.x BETWEEN ? AND ? AND b.z BETWEEN ? AND ? AND b.y BETWEEN ? AND ?")
            select.args += listOf(int(center.x - r), int(center.x + r), int(center.z - r), int(center.z + r), int(center.y - r), int(center.y + r))
        }
        if (box != null) {
            select.where.append(" AND b.x BETWEEN ? AND ? AND b.z BETWEEN ? AND ? AND b.y BETWEEN ? AND ?")
            select.args += listOf(int(box.minX), int(box.maxX), int(box.minZ), int(box.maxZ), int(box.minY), int(box.maxY))
        }
        within(select, "LOWER(u.name)", search.users.map { SqlValue.Text(it) }, false)
        within(select, "LOWER(u.name)", search.excludedUsers.map { SqlValue.Text(it) }, true)
    }

    private fun within(select: Select, column: String, values: List<SqlValue>, not: Boolean) {
        if (values.isEmpty()) return
        select.where.append(" AND ").append(column).append(if (not) " NOT IN (" else " IN (")
        values.indices.forEach { select.where.append(if (it == 0) "?" else ", ?") }
        select.where.append(")")
        select.args += values
    }

    // i: and e: on a table whose rows are about one named thing (an item).
    private fun names(select: Select, column: String, search: Search) {
        if (search.blocks.isNotEmpty()) {
            select.where.append(" AND ")
            anyOf(select, column, search.blocks)
        }
        if (search.excludedBlocks.isNotEmpty()) {
            select.where.append(" AND NOT ")
            anyOf(select, column, search.excludedBlocks)
        }
    }

    // The block a change is about: what was broken, or what was placed.
    private fun involved(select: Select, blocks: List<String>) {
        select.where.append("((b.action = ").append(Actions.BREAK).append(" AND ")
        anyOf(select, "os.data", blocks)
        select.where.append(") OR (b.action <> ").append(Actions.BREAK).append(" AND ")
        anyOf(select, "ns.data", blocks)
        select.where.append("))")
    }

    // minecraft:oak_log, with or without [properties]. LIKE's _ is escaped.
    private fun anyOf(select: Select, column: String, ids: List<String>) {
        select.where.append("(")
        for ((i, id) in ids.withIndex()) {
            if (i > 0) select.where.append(" OR ")
            select.where.append(column).append(" = ? OR ").append(column).append(" LIKE ? ESCAPE '!'")
            select.args += SqlValue.Text(id)
            select.args += SqlValue.Text(id.replace("!", "!!").replace("_", "!_").replace("%", "!%") + "[%")
        }
        select.where.append(")")
    }

    private fun rows(session: SqlSession, select: Select, tail: String): Outcome<List<LoggedRow>, DbError> =
        when (val read = session.query(Statement(select.select + select.from + select.where + tail, select.args))) {
            is Outcome.Err -> read
            is Outcome.Ok -> Outcome.Ok(read.value.map { row(select.table, it) })
        }

    private fun row(table: LoggedRow.Table, row: Row): LoggedRow = LoggedRow(
        table, row.value("id").long(), row.value("time").long().toInt(), text(row.value("uname")) ?: "?", text(row.value("wname")) ?: "?",
        row.value("x").long().toInt(), row.value("y").long().toInt(), row.value("z").long().toInt(), row.value("act").long().toInt(),
        text(row.value("thing")), text(row.value("after")), row.value("rb").long() != 0L, row.value("amount").long().toInt(),
        (row.value("extra") as? SqlValue.Bytes)?.value,
    )

    private fun SqlValue.long(): Long = when (this) {
        is SqlValue.Integer -> value
        is SqlValue.Real -> value.toLong()
        is SqlValue.Text -> value.toLongOrNull() ?: 0
        else -> 0
    }

    private fun text(value: SqlValue): String? = when (value) {
        is SqlValue.Text -> value.value
        is SqlValue.Bytes -> value.value.decodeToString()
        is SqlValue.Integer -> value.value.toString()
        else -> null
    }

    private fun int(value: Int) = SqlValue.Integer(value.toLong())
}
