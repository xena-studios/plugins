package co.xenastudios.xteleport.store

import co.xenastudios.core.api.Location
import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.WorldRef
import co.xenastudios.core.api.map
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.db.Database
import co.xenastudios.core.common.db.DbError
import co.xenastudios.core.common.db.Dialect
import co.xenastudios.core.common.db.Migration
import co.xenastudios.core.common.db.Row
import co.xenastudios.core.common.db.Sql
import co.xenastudios.core.common.db.SqlValue
import co.xenastudios.core.common.db.Statement
import co.xenastudios.xteleport.api.Home
import co.xenastudios.xteleport.api.Warp

/**
 * xTeleport's tables, exactly as 1.x made them, so the 1.x jar put back reads a 2.0 database as
 * its own: the same columns, types, keys and schema version (1). Names are kept as typed and
 * keyed in lower case, so `/home Base` and `/home base` are one home. Every place has a `server`
 * column, empty for now, so homes and warps across a network can come without changing a table.
 */
public object Tables {
    private const val PLACE = "server VARCHAR(64) NOT NULL DEFAULT '', world VARCHAR(255) NOT NULL," +
        " x DOUBLE NOT NULL, y DOUBLE NOT NULL, z DOUBLE NOT NULL, yaw FLOAT NOT NULL, pitch FLOAT NOT NULL, created BIGINT NOT NULL"

    /** The schema: 1.x's one step, word for word, so 1.x and 2.0 each see the other's database as current. */
    public val MIGRATIONS: List<Migration> = listOf(
        Migration(1, "homes, warps, the spawn and players' settings") { sql ->
            val table = if (sql.dialect is Dialect.Sqlite) "" else " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
            listOf(
                sql.raw("CREATE TABLE IF NOT EXISTS {p}homes (owner CHAR(36) NOT NULL, name_key VARCHAR(32) NOT NULL, name VARCHAR(32) NOT NULL, $PLACE, PRIMARY KEY (owner, name_key))$table"),
                sql.raw("CREATE TABLE IF NOT EXISTS {p}warps (name_key VARCHAR(32) NOT NULL, name VARCHAR(32) NOT NULL, $PLACE, PRIMARY KEY (server, name_key))$table"),
                sql.raw("CREATE TABLE IF NOT EXISTS {p}spawns (name_key VARCHAR(32) NOT NULL, name VARCHAR(32) NOT NULL, $PLACE, PRIMARY KEY (server, name_key))$table"),
                sql.raw("CREATE TABLE IF NOT EXISTS {p}players (uuid CHAR(36) NOT NULL PRIMARY KEY, tpa_off INT NOT NULL DEFAULT 0)$table"),
            )
        },
    )

    /** The spawn's name in `spawns`: 1.x's, and its only row. */
    internal const val SPAWN: String = "spawn"
}

/** What's kept about one player: their homes, sorted by name, and whether they turned TPA requests off. */
public class PlayerData(public val homes: List<Home>, public val tpaOff: Boolean)

/** What the whole server shares: the warps, and the spawn set with `/setspawn`. */
public class ServerData(public val warps: List<Warp>, public val spawn: Warp?)

/** How many rows an import added, and how many it skipped because they were already there. */
public class Imported(public val homes: Int, public val warps: Int, public val spawn: Boolean, public val skipped: Int)

/**
 * Homes, warps, the spawn and the TPA setting in the database, through the core's [Database]:
 * writes go to its one writer in order, each a transaction, and a player's homes are read on
 * the writer too ([Database.session]), so a read always sees every write asked for before it, as
 * 1.x's single thread did. Every [Pending] settles on the database's threads, never failing: an
 * error is an [Outcome.Err].
 */
public class Store(private val db: Database) {
    private val sql: Sql = db.sql
    private val insertIgnore = if (db.dialect is Dialect.Sqlite) "INSERT OR IGNORE" else "INSERT IGNORE"

    /** Where the data is, for the console: `the SQLite database xteleport.db`. */
    public val description: String get() = db.description

    /** Whether the database can be reached. */
    public val available: Boolean get() = db.available

    /** The database, for `/xteleport debug`'s health check. */
    public val database: Database get() = db

    public fun loadPlayer(player: PlayerRef): Pending<Outcome<PlayerData, DbError>> = db.session { session ->
        val id = text(player.toString())
        val homes = session.query(sql.raw("SELECT name, world, x, y, z, yaw, pitch FROM {p}homes WHERE owner = ? AND server = '' ORDER BY name_key", id))
        if (homes is Outcome.Err) return@session homes
        val off = session.query(sql.raw("SELECT tpa_off FROM {p}players WHERE uuid = ?", id))
        if (off is Outcome.Err) return@session off
        val list = (homes as Outcome.Ok).value.mapNotNull { row -> place(row)?.let { Home(player, row.text("name"), it) } }
        Outcome.Ok(PlayerData(list, (off as Outcome.Ok).value.firstOrNull()?.long(0)?.let { it != 0L } == true))
    }

    public fun loadServer(): Pending<Outcome<ServerData, DbError>> = db.session { session ->
        val warps = session.query(sql.raw("SELECT name, world, x, y, z, yaw, pitch FROM {p}warps WHERE server = '' ORDER BY name_key"))
        if (warps is Outcome.Err) return@session warps
        val spawns = session.query(sql.raw("SELECT name, world, x, y, z, yaw, pitch FROM {p}spawns WHERE server = '' AND name_key = ?", text(Tables.SPAWN)))
        if (spawns is Outcome.Err) return@session spawns
        val list = (warps as Outcome.Ok).value.mapNotNull { row -> place(row)?.let { Warp(row.text("name"), it) } }
        val spawn = (spawns as Outcome.Ok).value.firstOrNull()?.let { row -> place(row)?.let { Warp(Tables.SPAWN, it) } }
        Outcome.Ok(ServerData(list, spawn))
    }

    /** Sets a home, replacing one of the same name. */
    public fun saveHome(home: Home, now: Long): Pending<Outcome<Unit, DbError>> = db.write(
        listOf(
            sql.raw("DELETE FROM {p}homes WHERE owner = ? AND name_key = ?", text(home.owner.toString()), text(key(home.name))),
            insertHome(home, "INSERT", now),
        ),
    )

    /** Deletes a home; settles `true` if there was one in memory to delete (the caller knows). */
    public fun deleteHome(owner: PlayerRef, name: String): Pending<Outcome<Unit, DbError>> =
        db.write(listOf(sql.raw("DELETE FROM {p}homes WHERE owner = ? AND name_key = ?", text(owner.toString()), text(key(name)))))

    public fun setTpaOff(player: PlayerRef, off: Boolean): Pending<Outcome<Unit, DbError>> {
        val id = text(player.toString())
        val statements = ArrayList<Statement>(2)
        statements += sql.raw("DELETE FROM {p}players WHERE uuid = ?", id)
        if (off) statements += sql.raw("INSERT INTO {p}players (uuid, tpa_off) VALUES (?, 1)", id)
        return db.write(statements)
    }

    public fun saveWarp(warp: Warp, now: Long): Pending<Outcome<Unit, DbError>> = savePlace("warps", warp.name, warp.location, now)

    public fun deleteWarp(name: String): Pending<Outcome<Unit, DbError>> =
        db.write(listOf(sql.raw("DELETE FROM {p}warps WHERE name_key = ? AND server = ''", text(key(name)))))

    /** Sets the spawn. Its name is always `spawn`. */
    public fun saveSpawn(at: Location, now: Long): Pending<Outcome<Unit, DbError>> = savePlace("spawns", Tables.SPAWN, at, now)

    private fun savePlace(table: String, name: String, at: Location, now: Long): Pending<Outcome<Unit, DbError>> = db.write(
        listOf(
            sql.raw("DELETE FROM {p}$table WHERE name_key = ? AND server = ''", text(key(name))),
            insertPlace(table, name, at, "INSERT", now),
        ),
    )

    /** Everything kept about [player]: their homes and their TPA setting. Settles with how many homes were deleted. */
    public fun forget(player: PlayerRef): Pending<Outcome<Int, DbError>> = db.transaction { session ->
        val id = text(player.toString())
        val homes = session.execute(sql.raw("DELETE FROM {p}homes WHERE owner = ?", id))
        if (homes is Outcome.Err) return@transaction homes
        val settings = session.execute(sql.raw("DELETE FROM {p}players WHERE uuid = ?", id))
        if (settings is Outcome.Err) return@transaction settings
        homes
    }

    /**
     * Adds imported homes, warps and a spawn in one transaction, keeping whatever is already here:
     * a home or warp with the same name, or a spawn already set, is skipped (insert-or-ignore), so
     * running it twice is safe.
     */
    public fun importAll(homes: List<Home>, warps: List<Warp>, spawn: Location?, now: Long): Pending<Outcome<Imported, DbError>> = db.transaction { session ->
        var addedHomes = 0
        var addedWarps = 0
        for (home in homes) {
            when (val done = session.execute(insertHome(home, insertIgnore, now))) {
                is Outcome.Ok -> addedHomes += done.value
                is Outcome.Err -> return@transaction done
            }
        }
        for (warp in warps) {
            when (val done = session.execute(insertPlace("warps", warp.name, warp.location, insertIgnore, now))) {
                is Outcome.Ok -> addedWarps += done.value
                is Outcome.Err -> return@transaction done
            }
        }
        var addedSpawn = false
        if (spawn != null) {
            when (val done = session.execute(insertPlace("spawns", Tables.SPAWN, spawn, insertIgnore, now))) {
                is Outcome.Ok -> addedSpawn = done.value > 0
                is Outcome.Err -> return@transaction done
            }
        }
        Outcome.Ok(Imported(addedHomes, addedWarps, addedSpawn, homes.size - addedHomes + warps.size - addedWarps))
    }

    /** Closes the database once what's queued is written. */
    public fun close(): Pending<Unit> = db.close()

    private fun insertHome(home: Home, insert: String, now: Long): Statement {
        val at = home.location
        return sql.raw(
            "$insert INTO {p}homes (owner, name_key, name, server, world, x, y, z, yaw, pitch, created) VALUES (?, ?, ?, '', ?, ?, ?, ?, ?, ?, ?)",
            text(home.owner.toString()), text(key(home.name)), text(home.name), text(at.world.name),
            real(at.x), real(at.y), real(at.z), real(at.yaw.toDouble()), real(at.pitch.toDouble()), SqlValue.Integer(now),
        )
    }

    private fun insertPlace(table: String, name: String, at: Location, insert: String, now: Long): Statement = sql.raw(
        "$insert INTO {p}$table (name_key, name, server, world, x, y, z, yaw, pitch, created) VALUES (?, ?, '', ?, ?, ?, ?, ?, ?, ?)",
        text(key(name)), text(name), text(at.world.name),
        real(at.x), real(at.y), real(at.z), real(at.yaw.toDouble()), real(at.pitch.toDouble()), SqlValue.Integer(now),
    )

    public companion object {
        /** A name as it's keyed: in lower case. */
        public fun key(name: String): String = name.lowercase()

        private fun text(value: String) = SqlValue.Text(value)

        private fun real(value: Double) = SqlValue.Real(value)

        private fun Row.text(name: String): String = when (val value = value(name)) {
            is SqlValue.Text -> value.value
            is SqlValue.Integer -> value.value.toString()
            is SqlValue.Real -> value.value.toString()
            else -> ""
        }

        private fun Row.number(name: String): Double? = when (val value = value(name)) {
            is SqlValue.Real -> value.value
            is SqlValue.Integer -> value.value.toDouble()
            is SqlValue.Text -> value.value.toDoubleOrNull()
            else -> null
        }

        // A row's place, or null if it's broken (a blank world, a missing number): skipped rather than failing the read.
        private fun place(row: Row): Location? {
            val world = row.text("world")
            if (world.isBlank()) return null
            val x = row.number("x") ?: return null
            val y = row.number("y") ?: return null
            val z = row.number("z") ?: return null
            return Location(WorldRef.of(world), x, y, z, (row.number("yaw") ?: 0.0).toFloat(), (row.number("pitch") ?: 0.0).toFloat())
        }

        /** [pending]'s outcome as a plain success flag, for callers that only want to know it worked. */
        internal fun ok(pending: Pending<Outcome<Unit, DbError>>): Pending<Boolean> = pending.map { it is Outcome.Ok }
    }
}
