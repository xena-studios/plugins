package co.xenastudios.core.common.admin

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.onSettled
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.db.Column
import co.xenastudios.core.common.db.Database
import co.xenastudios.core.common.db.DatabaseSettings
import co.xenastudios.core.common.db.DbError
import co.xenastudios.core.common.db.Migration
import co.xenastudios.core.common.db.Row
import co.xenastudios.core.common.db.Statement
import co.xenastudios.core.common.db.Table
import co.xenastudios.core.common.db.ascending
import co.xenastudios.core.common.db.migrate
import co.xenastudios.core.common.isFatal
import co.xenastudios.core.common.locked
import co.xenastudios.core.common.platform.Platform
import co.xenastudios.core.common.scheduler.async

/**
 * What `/<plugin> migrate` needs from the plugin: the database it uses now, a way to open the
 * other kind from the config's settings for it, and a way to switch the config over.
 */
public interface StorageSwitch {
    /** The storage type in use. */
    public val currentType: DatabaseSettings.Type

    /** The database in use. */
    public val current: Database

    /** Opens a database of [type] from the config's settings for it, off the server's threads. */
    public fun open(type: DatabaseSettings.Type): Pending<Outcome<Database, DbError>>

    /**
     * Makes [type] the storage type in config.yml and applies it, as a reload does: the plugin
     * opens the new database and closes the old one. Or says why it couldn't, in English.
     */
    public fun switchTo(type: DatabaseSettings.Type): Pending<Outcome<Unit, String>>
}

/** Told how a migration is going, from whichever thread it's on. */
public interface MigrationListener {
    /** The target already has data: [rows] by table, those that have any. Nothing was changed. */
    public fun notEmpty(rows: Map<String, Long>)

    /** Copying starts. */
    public fun started()

    /** [table] is copied: [rows] rows. */
    public fun copied(table: String, rows: Long)

    /** Everything is copied, counted, and the config switched: [rows] in all. */
    public fun finished(rows: Long)

    /** It failed, for [reason]; the config wasn't switched. */
    public fun failed(reason: String)
}

/**
 * `/<plugin> migrate <sqlite|mysql>`: copies every table the plugin declared ([tables]) from
 * the database it uses to one of the other type, checks every table has as many rows on both,
 * then switches the config over.
 *
 * - The target is brought to the plugin's schema first ([migrations]), so its tables are the
 *   ones the plugin makes, with its keys and indexes, and its schema version is the same.
 *   `{prefix}meta` isn't copied: the migrations write its schema row, and its other rows are
 *   each writer's own.
 * - A target that already has rows in any of the tables is refused, so a second server's data
 *   isn't mixed in or lost, unless it's [run] with `force`: then those tables are emptied
 *   first.
 * - Rows are read a page at a time ([batch], in key order) and written through the target's
 *   one writer as one transaction a page, so memory stays flat and nothing on the server
 *   waits.
 * - The source isn't changed or closed, so a failure at any point leaves the plugin as it was.
 *   Writes the plugin makes while it copies (a player joining) can land after their table is
 *   copied; the count check then fails it, and running it again on a quiet server works.
 */
public class StorageMigration(
    private val platform: Platform,
    private val storage: StorageSwitch,
    /** Every table the plugin keeps, as declared; the change feed's table isn't data, so it's left out. */
    private val tables: List<Table>,
    private val migrations: List<Migration>,
    private val batch: Int = 500,
) {
    private val lock = Any()
    private var running = false

    init {
        require(tables.isNotEmpty()) { "a migration copies some tables" }
        require(batch >= 1) { "a batch has at least one row" }
    }

    /** The storage type the data is in now. */
    public val currentType: DatabaseSettings.Type get() = storage.currentType

    /** Whether a migration is running. */
    public val busy: Boolean get() = locked(lock) { running }

    /**
     * Starts moving the data to [to] on an async task, emptying the target's tables first if
     * [force], and tells [listener] how it goes. Returns `false` without starting if one is
     * running. [to] must be another type than the one in use.
     */
    public fun run(to: DatabaseSettings.Type, force: Boolean, listener: MigrationListener): Boolean {
        require(to != storage.currentType) { "the data is already in $to" }
        val claimed = locked(lock) {
            if (running) false else {
                running = true
                true
            }
        }
        if (!claimed) return false
        platform.scheduler.async { Copy(storage.current, to, force, listener).begin() }
        return true
    }

    private inner class Copy(
        val source: Database,
        val to: DatabaseSettings.Type,
        val force: Boolean,
        val listener: MigrationListener,
    ) {
        var target: Database? = null
        var total = 0L

        fun begin() = step {
            storage.open(to).then("couldn't open the $to database") { opened ->
                target = opened
                opened.migrate(migrations).then("couldn't make the tables in ${opened.description}") { emptyEnough(opened) }
            }
        }

        fun emptyEnough(target: Database) {
            counts(target).then("couldn't count the rows in ${target.description}") { rows ->
                val full = rows.filterValues { it > 0 }
                when {
                    full.isEmpty() -> copyAll(target)
                    !force -> end { listener.notEmpty(full) }
                    else -> target.write(full.keys.map { name -> target.sql.raw("DELETE FROM {p}$name") })
                        .then("couldn't empty the tables in ${target.description}") { copyAll(target) }
                }
            }
        }

        fun copyAll(target: Database) {
            listener.started()
            table(target, 0)
        }

        fun table(target: Database, index: Int) {
            if (index == tables.size) return verify(target)
            page(target, index, 0L)
        }

        fun page(target: Database, index: Int, offset: Long) {
            val table = tables[index]
            val order = (table.primaryKey.ifEmpty { table.columns }).map(Column<*>::ascending)
            val select = source.sql.select(table, order = order, limit = batch, offset = offset)
            source.read { it.query(select) }.then("couldn't read ${source.sql.name(table)}") { rows ->
                if (rows.isEmpty()) {
                    listener.copied(table.tableName, offset)
                    table(target, index + 1)
                } else {
                    target.write(rows.map { insert(target, table, it) }).then("couldn't write to ${target.sql.name(table)}") {
                        total += rows.size
                        if (rows.size < batch) {
                            listener.copied(table.tableName, offset + rows.size)
                            table(target, index + 1)
                        } else {
                            page(target, index, offset + rows.size)
                        }
                    }
                }
            }
        }

        fun verify(target: Database) {
            counts(source).then("couldn't count the rows in ${source.description}") { before ->
                counts(target).then("couldn't count the rows in ${target.description}") { after ->
                    val wrong = tables.map { it.tableName }.filter { before[it] != after[it] }
                    if (wrong.isNotEmpty()) {
                        val detail = wrong.joinToString(", ") { "$it has ${before[it]} rows here and ${after[it]} there" }
                        return@then fail("the copy doesn't match ($detail); something wrote during the copy, so run it again when the server is quiet")
                    }
                    // Closed before switching: the plugin opens its own on the switch.
                    target.close().onSettled { _, _ ->
                        this.target = null
                        storage.switchTo(to).onSettled { switched, error ->
                            when {
                                error != null -> fail("couldn't switch the config: $error")
                                switched is Outcome.Err -> fail("couldn't switch the config: ${switched.error}")
                                else -> {
                                    platform.log.info("Moved $total rows from ${source.description} to $to.")
                                    end { listener.finished(total) }
                                }
                            }
                        }
                    }
                }
            }
        }

        fun counts(database: Database): Pending<Outcome<Map<String, Long>, DbError>> = database.read { session ->
            val rows = LinkedHashMap<String, Long>()
            for (table in tables) {
                when (val counted = session.query(database.sql.count(table))) {
                    is Outcome.Err -> return@read counted
                    is Outcome.Ok -> rows[table.tableName] = counted.value.firstOrNull()?.long(0) ?: 0
                }
            }
            Outcome.Ok(rows)
        }

        fun insert(target: Database, table: Table, row: Row): Statement {
            val columns = table.columns
            return Statement(
                "INSERT INTO ${target.sql.name(table)} (${columns.joinToString(", ") { it.name }}) VALUES (${columns.joinToString(", ") { "?" }})",
                columns.map { row.value(it.name) },
            )
        }

        /** Carries on with [next] on an async task once this settles with a value, or fails the migration saying [what]. */
        fun <T> Pending<Outcome<T, DbError>>.then(what: String, next: (T) -> Unit) {
            onSettled { result, error ->
                platform.scheduler.async {
                    step {
                        when {
                            error != null -> fail("$what: $error")
                            result is Outcome.Err -> fail("$what: ${result.error.detail}")
                            else -> next((result as Outcome.Ok).value)
                        }
                    }
                }
            }
        }

        fun fail(reason: String) {
            platform.log.warn("Moving the data to $to failed: $reason")
            val opened = target
            target = null
            val tell = { end { listener.failed(reason) } }
            if (opened == null) tell() else opened.close().onSettled { _, _ -> tell() }
        }

        fun end(tell: () -> Unit) {
            locked(lock) { running = false }
            tell()
        }

        fun step(block: () -> Unit) {
            try {
                block()
            } catch (e: Throwable) {
                if (isFatal(e)) throw e
                platform.log.error("Moving the data to $to failed", e)
                fail("it threw ${e::class.simpleName}: ${e.message}")
            }
        }
    }
}
