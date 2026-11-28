package co.xenastudios.xrewind.log

import co.xenastudios.core.common.db.Database
import co.xenastudios.core.common.db.SqlValue
import co.xenastudios.core.common.db.Statement
import co.xenastudios.core.common.world.ItemData

/**
 * Records as the statements that write them, 1.x's inserts: in a class of its own, loaded with the
 * first batch, not as the server starts (decisions/2026-11-25-a-lighter-core.md).
 */
internal object Rows {
    fun statements(db: Database, names: Dictionaries, batch: List<Record>, itemBytes: (ItemData) -> ByteArray?): List<Statement> {
        val sql = db.sql
        val blocks = ArrayList<Statement>()
        val containers = ArrayList<Statement>()
        val kills = ArrayList<Statement>()
        val texts = ArrayList<Statement>()
        val sessions = ArrayList<Statement>()
        val marks = ArrayList<Statement>()
        for (r in batch) {
            when (r) {
                is Record.Block -> blocks += sql.raw(
                    INSERT_BLOCK, int(r.time), int(names.user(r.actor)), int(names.world(r.world)), int(r.x), int(r.y), int(r.z), int(r.action),
                    int(names.state(r.before.data)), int(names.state(r.after.data)),
                    r.contents?.let { int(names.bytes(it)) } ?: SqlValue.Null, int(if (r.rolledBack) 1 else 0),
                )
                is Record.Container -> {
                    val bytes = r.itemBytes ?: r.item?.let(itemBytes) ?: continue
                    containers += sql.raw(
                        INSERT_CONTAINER, int(r.time), int(names.user(r.actor)), int(names.world(r.world)), int(r.x), int(r.y), int(r.z), int(r.action),
                        int(names.state(r.material)), int(names.bytes(bytes)), int(r.amount), int(if (r.rolledBack) 1 else 0),
                    )
                }
                is Record.Kill -> kills += sql.raw(
                    INSERT_KILL, int(r.time), int(names.user(r.actor)), int(names.world(r.world)), int(r.x), int(r.y), int(r.z), int(Actions.KILL), int(names.state(r.type)),
                )
                is Record.Text -> texts += sql.raw(
                    INSERT_TEXT, int(r.time), int(names.user(r.actor)), int(names.world(r.world)), int(r.x), int(r.y), int(r.z), int(r.kind), SqlValue.Text(r.message),
                )
                is Record.Session -> sessions += sql.raw(
                    INSERT_SESSION, int(r.time), int(names.user(r.actor)), int(names.world(r.world)), int(r.x), int(r.y), int(r.z), int(r.action),
                )
                is Record.Mark -> marks += markStatements(db, r)
            }
        }
        return blocks + sessions + containers + kills + texts + marks
    }

    // UPDATE ... WHERE id IN (...), 500 ids at a time.
    private fun markStatements(db: Database, mark: Record.Mark): List<Statement> {
        val out = ArrayList<Statement>()
        var from = 0
        while (from < mark.ids.size) {
            val to = minOf(mark.ids.size, from + 500)
            val args = ArrayList<SqlValue>(to - from + 1)
            args += int(if (mark.rolledBack) 1 else 0)
            for (i in from until to) args += SqlValue.Integer(mark.ids[i])
            out += Statement("UPDATE ${db.sql.prefix}${mark.table} SET rolled_back = ? WHERE id IN (" + (from until to).joinToString(", ") { "?" } + ")", args)
            from = to
        }
        return out
    }

    private fun int(value: Int) = SqlValue.Integer(value.toLong())

    const val INSERT_BLOCK = "INSERT INTO {p}block (time, user, world, x, y, z, action, old_state, new_state, extra, rolled_back) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
    const val INSERT_CONTAINER = "INSERT INTO {p}container (time, user, world, x, y, z, action, material, item, amount, rolled_back) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
    const val INSERT_KILL = "INSERT INTO {p}entity (time, user, world, x, y, z, action, type) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
    const val INSERT_TEXT = "INSERT INTO {p}text (time, user, world, x, y, z, kind, message) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
    const val INSERT_SESSION = "INSERT INTO {p}session (time, user, world, x, y, z, action) VALUES (?, ?, ?, ?, ?, ?, ?)"
}
