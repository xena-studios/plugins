package co.xenastudios.xstats.store

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.newPending
import co.xenastudios.core.api.onSettled
import co.xenastudios.core.api.pendingOf
import co.xenastudios.core.api.resolve
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.admin.StorageMigration
import co.xenastudios.core.common.admin.StorageSwitch
import co.xenastudios.core.common.db.ColumnType
import co.xenastudios.core.common.db.Database
import co.xenastudios.core.common.db.DatabaseSettings
import co.xenastudios.core.common.db.DbError
import co.xenastudios.core.common.db.Dialect
import co.xenastudios.core.common.db.Table
import co.xenastudios.core.common.db.migrate
import co.xenastudios.core.common.family.StorageType
import co.xenastudios.core.common.locked
import co.xenastudios.core.common.scheduler.async
import co.xenastudios.xstats.Files
import co.xenastudios.xstats.Opener
import co.xenastudios.xstats.XStatsPlugin
import co.xenastudios.xstats.config.XStatsConfig

/**
 * `/xstats migrate <sqlite|mysql>`: the core's [StorageMigration] over xStats' tables, made the first
 * time the command runs, so none of this loads as the server starts.
 *
 * - Every table is copied as it is, ids included, so the players, stats and servers the plugin has
 *   in memory keep their ids in the new database. `meta`'s own rows (whether the first start's
 *   import ran, per server) are copied after, beside the schema row the target's migrations wrote.
 * - Saves are paused from the moment the target opens until the move ends, however it ends: a
 *   save's updates to rows already copied would otherwise be lost without the row counts showing
 *   it. Counting goes on in memory and lands in the next save, in whichever database is in use.
 * - Once everything matches, `storage.type` in config.yml is set and the plugin moves to the new
 *   database at once, closing the old one, which is left as it was.
 * - Network mode is refused: every server and the proxy share one MySQL database, so moving one
 *   server's copy of it would split the network.
 */
internal object Moving {
    fun migration(plugin: XStatsPlugin, files: Files, opener: Opener): StorageMigration =
        StorageMigration(plugin.platform, Switch(plugin, files, opener), TABLES, Tables.MIGRATIONS)

    // xStats' tables as the migration reads and writes them: their columns, in key order. The
    // tables themselves are made by Tables.MIGRATIONS, with 1.x's own statements.
    object Servers : Table("servers") {
        init {
            rowId("id")
            column("name", ColumnType.text(64))
        }
    }

    object Stats : Table("stats") {
        init {
            rowId("id")
            column("name", ColumnType.text(64))
        }
    }

    object Players : Table("players") {
        init {
            rowId("id")
            column("uuid", ColumnType.text(36))
            column("name", ColumnType.text(16).nullable())
            column("name_lower", ColumnType.text(16).nullable())
            column("first_join", ColumnType.LONG)
            column("last_seen", ColumnType.LONG)
            column("hidden", ColumnType.INT)
            column("excluded", ColumnType.INT)
        }
    }

    object Totals : Table("totals") {
        init {
            val player = column("player", ColumnType.INT)
            val stat = column("stat", ColumnType.INT)
            val server = column("server", ColumnType.INT)
            column("value", ColumnType.LONG)
            column("raw", ColumnType.LONG)
            column("updated", ColumnType.LONG)
            primaryKey(player, stat, server)
        }
    }

    object Daily : Table("daily") {
        init {
            val player = column("player", ColumnType.INT)
            val stat = column("stat", ColumnType.INT)
            val server = column("server", ColumnType.INT)
            val day = column("day", ColumnType.INT)
            column("delta", ColumnType.LONG)
            primaryKey(player, stat, server, day)
        }
    }

    object Monthly : Table("monthly") {
        init {
            val player = column("player", ColumnType.INT)
            val stat = column("stat", ColumnType.INT)
            val server = column("server", ColumnType.INT)
            val month = column("month", ColumnType.INT)
            column("delta", ColumnType.LONG)
            primaryKey(player, stat, server, month)
        }
    }

    object Boards : Table("boards") {
        init {
            rowId("id")
            column("server", ColumnType.INT)
            column("world", ColumnType.text(64))
            column("x", ColumnType.DOUBLE)
            column("y", ColumnType.DOUBLE)
            column("z", ColumnType.DOUBLE)
            column("yaw", ColumnType.DOUBLE)
            column("stat", ColumnType.text(64))
            column("period", ColumnType.text(16))
        }
    }

    object Sources : Table("sources") {
        init {
            val stat = column("stat", ColumnType.INT)
            val server = column("server", ColumnType.INT)
            column("source", ColumnType.text(2048))
            primaryKey(stat, server)
        }
    }

    val TABLES: List<Table> = listOf(Servers, Stats, Players, Totals, Daily, Monthly, Boards, Sources)

    private class Switch(private val plugin: XStatsPlugin, private val files: Files, private val opener: Opener) : StorageSwitch {
        @kotlin.concurrent.Volatile
        private var paused = false

        override val currentType: DatabaseSettings.Type
            get() {
                val db = plugin.database
                if (db != null) return if (db.sql.dialect is Dialect.MySql) DatabaseSettings.Type.MYSQL else DatabaseSettings.Type.SQLITE
                return if (plugin.config[XStatsConfig.storage.type] == StorageType.MYSQL) DatabaseSettings.Type.MYSQL else DatabaseSettings.Type.SQLITE
            }

        override val current: Database
            get() = plugin.database ?: throw IllegalStateException("xStats' database isn't open: ${plugin.failure ?: "it's still opening"}")

        override fun open(type: DatabaseSettings.Type): Pending<Outcome<Database, DbError>> {
            if (plugin.config[XStatsConfig.network.enabled]) {
                return refused("network mode is on, and every server shares one database; turn network.enabled off on this server first")
            }
            val claimed = locked(plugin.saveLock) {
                if (plugin.saving) false else true.also { plugin.saving = true }
            }
            if (!claimed) return refused("a save is running; try again in a few seconds")
            paused = true
            val done = newPending<Outcome<Database, DbError>>()
            plugin.platform.scheduler.async {
                done.resolve(
                    when (val opened = opener.openAs(plugin.config, type)) {
                        is Outcome.Ok -> opened
                        is Outcome.Err -> Outcome.Err(DbError(DbError.Kind.UNAVAILABLE, opened.error))
                    },
                )
            }
            return done
        }

        override fun switchTo(type: DatabaseSettings.Type): Pending<Outcome<Unit, String>> {
            val done = newPending<Outcome<Unit, String>>()
            plugin.platform.scheduler.async {
                val old = plugin.database
                when {
                    old == null -> done.resolve(Outcome.Err("the database closed during the move"))
                    else -> when (val opened = opener.openAs(plugin.config, type)) {
                        is Outcome.Err -> done.resolve(Outcome.Err(opened.error))
                        is Outcome.Ok -> moveTo(old, opened.value, type, done)
                    }
                }
            }
            return done
        }

        // The copied database opened as the plugin's own: its schema checked, meta's flags
        // carried over, config.yml set, then used in the old one's place.
        private fun moveTo(old: Database, target: Database, type: DatabaseSettings.Type, done: Pending<Outcome<Unit, String>>) {
            target.migrate(Tables.MIGRATIONS).onSettled { migrated, error ->
                if (migrated !is Outcome.Ok<*>) {
                    target.close()
                    done.resolve(Outcome.Err("couldn't open ${target.description} again: ${(migrated as? Outcome.Err<*>)?.error ?: error}"))
                    return@onSettled
                }
                old.read { session -> Q.run(session, old.sql) { q -> q.rows("SELECT name, value FROM {p}meta WHERE name LIKE 'imported:%'").map { (it.str(0) ?: "") to (it.str(1) ?: "") } } }
                    .onSettled { flags, readError ->
                        val rows = (flags as? Outcome.Ok)?.value
                        if (rows == null) {
                            target.close()
                            done.resolve(Outcome.Err("couldn't read ${old.description}'s flags: ${(flags as? Outcome.Err)?.error?.detail ?: readError}"))
                            return@onSettled
                        }
                        target.session { session ->
                            Q.run(session, target.sql) { q ->
                                for ((name, value) in rows) q.exec(q.insertIgnore + " INTO {p}meta (name, value) VALUES (?, ?)", name, value)
                            }
                        }.onSettled { wrote, writeError ->
                            if (wrote !is Outcome.Ok<*>) {
                                target.close()
                                done.resolve(Outcome.Err("couldn't write ${target.description}'s flags: ${(wrote as? Outcome.Err<*>)?.error ?: writeError}"))
                                return@onSettled
                            }
                            val stored = if (type == DatabaseSettings.Type.MYSQL) StorageType.MYSQL else StorageType.SQLITE
                            val notWritten = files.useStorage(stored)
                            if (notWritten != null) {
                                target.close()
                                done.resolve(Outcome.Err(notWritten))
                                return@onSettled
                            }
                            plugin.database = target
                            plugin.log.info("xStats now keeps its stats in ${target.description}; ${old.description} is left as it was.")
                            old.close()
                            done.resolve(Outcome.Ok(Unit))
                        }
                    }
            }
        }

        override fun ended(switched: Boolean) {
            if (!paused) return
            paused = false
            locked(plugin.saveLock) { plugin.saving = false }
        }

        private fun refused(why: String): Pending<Outcome<Database, DbError>> = pendingOf(Outcome.Err(DbError(DbError.Kind.UNAVAILABLE, why)))
    }
}
