package co.xenastudios.xrewind.log

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.db.DbError
import co.xenastudios.core.common.db.Sql
import co.xenastudios.core.common.db.SqlSession
import co.xenastudios.core.common.db.SqlValue
import co.xenastudios.core.common.db.Statement
import co.xenastudios.xrewind.api.Actor

/**
 * Players, sources, worlds, block states and items as the numbers rows store (1.x's
 * dictionaries). Owned by the writer alone, so plain maps. A new name gets the next id at once;
 * its row goes into the same write as the first rows that use it, and stays waiting until that
 * write commits, so a write that fails is tried again with the same ids.
 */
internal class Dictionaries(private val sql: Sql) {
    private class Known(val id: Int, val name: String)

    private val players = HashMap<PlayerRef, Known>()
    private val sources = HashMap<String, Int>()
    private val worlds = HashMap<String, Int>()
    private val states = HashMap<String, Int>()
    private val items = HashMap<Long, Int>()
    private var nextUser = 1
    private var nextWorld = 1
    private var nextState = 1
    private var nextItem = 1

    // Rows not yet committed, in the order they're written.
    private val newUsers = ArrayList<Statement>()
    private val renamed = ArrayList<Statement>()
    private val newWorlds = ArrayList<Statement>()
    private val newStates = ArrayList<Statement>()
    private val newItems = ArrayList<Statement>()

    /** Reads every name the database already has. On a reader or the writer. */
    fun load(session: SqlSession): Outcome<Unit, DbError> {
        when (val rows = session.query(sql.raw("SELECT id, uuid_most, uuid_least, name FROM {p}user"))) {
            is Outcome.Err -> return rows
            is Outcome.Ok -> for (row in rows.value) {
                val id = row.long(0)?.toInt() ?: continue
                val most = row.long(1)
                val least = row.long(2)
                val name = (row.values[3] as? SqlValue.Text)?.value ?: continue
                if (most != null && least != null) {
                    players[PlayerRef.of(kotlin.uuid.Uuid.fromLongs(most, least))] = Known(id, name)
                } else {
                    sources[name] = id
                }
                nextUser = maxOf(nextUser, id + 1)
            }
        }
        when (val rows = session.query(sql.raw("SELECT id, name FROM {p}world"))) {
            is Outcome.Err -> return rows
            is Outcome.Ok -> for (row in rows.value) {
                val id = row.long(0)?.toInt() ?: continue
                worlds[(row.values[1] as? SqlValue.Text)?.value ?: continue] = id
                nextWorld = maxOf(nextWorld, id + 1)
            }
        }
        when (val rows = session.query(sql.raw("SELECT id, hash FROM {p}item"))) {
            is Outcome.Err -> return rows
            is Outcome.Ok -> for (row in rows.value) {
                val id = row.long(0)?.toInt() ?: continue
                items[row.long(1) ?: continue] = id
                nextItem = maxOf(nextItem, id + 1)
            }
        }
        when (val rows = session.query(sql.raw("SELECT id, data FROM {p}state"))) {
            is Outcome.Err -> return rows
            is Outcome.Ok -> for (row in rows.value) {
                val id = row.long(0)?.toInt() ?: continue
                states[(row.values[1] as? SqlValue.Text)?.value ?: continue] = id
                nextState = maxOf(nextState, id + 1)
            }
        }
        return Outcome.Ok(Unit)
    }

    fun user(actor: Actor): Int {
        val player = actor.player
        if (player == null) {
            sources[actor.name]?.let { return it }
            val id = nextUser++
            sources[actor.name] = id
            newUsers += sql.raw("INSERT INTO {p}user (id, uuid_most, uuid_least, name) VALUES (?, ?, ?, ?)",
                int(id), SqlValue.Null, SqlValue.Null, SqlValue.Text(actor.name))
            return id
        }
        val known = players[player]
        if (known == null) {
            val id = nextUser++
            players[player] = Known(id, actor.name)
            player.uuid.toLongs { most, least ->
                newUsers += sql.raw("INSERT INTO {p}user (id, uuid_most, uuid_least, name) VALUES (?, ?, ?, ?)",
                    int(id), SqlValue.Integer(most), SqlValue.Integer(least), SqlValue.Text(actor.name))
            }
            return id
        }
        if (known.name != actor.name) {
            // Renamed since: lookups show the name they have now.
            players[player] = Known(known.id, actor.name)
            renamed += sql.raw("UPDATE {p}user SET name = ? WHERE id = ?", SqlValue.Text(actor.name), int(known.id))
        }
        return known.id
    }

    fun world(name: String): Int = worlds[name] ?: (nextWorld++).also {
        worlds[name] = it
        newWorlds += sql.raw("INSERT INTO {p}world (id, name) VALUES (?, ?)", int(it), SqlValue.Text(name))
    }

    /** A state id for any name: a block state, or an entity's or item's type, `minecraft:zombie`. */
    fun state(text: String): Int = states[text] ?: (nextState++).also {
        states[text] = it
        newStates += sql.raw("INSERT INTO {p}state (id, data) VALUES (?, ?)", int(it), SqlValue.Text(text))
    }

    /** Bytes stored once however often they come up: an item, or a block's contents. */
    fun bytes(data: ByteArray): Int {
        val hash = hash(data)
        return items[hash] ?: (nextItem++).also {
            items[hash] = it
            newItems += sql.raw("INSERT INTO {p}item (id, hash, data) VALUES (?, ?, ?)", int(it), SqlValue.Integer(hash), SqlValue.Bytes(data))
        }
    }

    /** The rows not yet committed, names first, so no row ever points at one that isn't there. */
    fun pending(): List<Statement> = newUsers + renamed + newWorlds + newStates + newItems

    /** The write with [pending]'s rows committed: they're in. */
    fun committed() {
        newUsers.clear()
        renamed.clear()
        newWorlds.clear()
        newStates.clear()
        newItems.clear()
    }

    /** The id of a player or source by name (lowercase), for `/xrewind forget`: `null` if none. */
    fun idOf(player: PlayerRef): Int? = players[player]?.id

    companion object {
        private fun int(value: Int) = SqlValue.Integer(value.toLong())

        /** 64-bit FNV-1a, as 1.x hashed: collisions between different items are vanishingly unlikely. */
        fun hash(data: ByteArray): Long {
            var hash = -0x340d631b7bdddcdbL
            for (b in data) {
                hash = hash xor (b.toLong() and 0xff)
                hash *= 0x100000001b3L
            }
            return hash
        }
    }
}
