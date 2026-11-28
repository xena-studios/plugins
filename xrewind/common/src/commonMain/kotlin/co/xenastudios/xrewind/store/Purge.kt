package co.xenastudios.xrewind.store

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.newPending
import co.xenastudios.core.api.onSettled
import co.xenastudios.core.api.resolve
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.db.Database
import co.xenastudios.core.common.db.Dialect
import co.xenastudios.core.common.db.SqlValue

/**
 * Deletes history from before a time, a few thousand rows per transaction, each queued behind
 * the writer's batches, so the database is never locked for long and logging carries on
 * alongside (1.x's purge). On SQLite the freed pages are then handed back to the disk a couple of
 * thousand at a time. [running] is asked between batches: a purge stops when the plugin does.
 */
public class Purge(private val db: Database, private val before: Int, private val running: () -> Boolean) {
    private val done = newPending<Outcome<Long, String>>()
    private var deleted = 0L
    private var table = 0

    /** Settles with how many rows were deleted, or why it stopped. [interrupted] says whether it stopped partway. */
    public fun run(): Pending<Outcome<Long, String>> {
        next()
        return done
    }

    /** Whether it stopped before it finished: the plugin stopped. */
    public var interrupted: Boolean = false
        private set

    private fun next() {
        if (!running()) {
            interrupted = true
            done.resolve(Outcome.Ok(deleted))
            return
        }
        if (table >= Tables.HISTORY.size) return vacuum(Long.MAX_VALUE)
        val name = Tables.HISTORY[table]
        val statement = if (db.dialect == Dialect.MySql) {
            db.sql.raw("DELETE FROM {p}$name WHERE time < ? LIMIT $BATCH", SqlValue.Integer(before.toLong()))
        } else {
            // SQLite has no DELETE ... LIMIT unless built with it.
            db.sql.raw("DELETE FROM {p}$name WHERE id IN (SELECT id FROM {p}$name WHERE time < ? LIMIT $BATCH)", SqlValue.Integer(before.toLong()))
        }
        db.transaction { it.execute(statement) }.onSettled { result, error ->
            when (result) {
                is Outcome.Ok -> {
                    deleted += result.value
                    if (result.value < BATCH) table++
                    next()
                }
                is Outcome.Err -> done.resolve(Outcome.Err(result.error.detail))
                null -> done.resolve(Outcome.Err(error.toString()))
            }
        }
    }

    private fun vacuum(free: Long) {
        if (db.dialect !is Dialect.Sqlite || deleted == 0L || !running()) {
            done.resolve(Outcome.Ok(deleted))
            return
        }
        db.session { session ->
            session.execute(db.sql.raw("PRAGMA incremental_vacuum($VACUUM_PAGES)"))
            session.query(db.sql.raw("PRAGMA freelist_count")).let { rows ->
                if (rows is Outcome.Ok) Outcome.Ok(rows.value.firstOrNull()?.long(0) ?: 0L) else Outcome.Ok(0L)
            }
        }.onSettled { result, _ ->
            val left = (result as? Outcome.Ok)?.value ?: 0L
            // Not shrinking (a database without auto_vacuum): nothing more to do.
            if (left in 1 until free) vacuum(left) else done.resolve(Outcome.Ok(deleted))
        }
    }

    public companion object {
        /** The daily purge of what's older than `purge.keep-days`. */
        public fun daily(plugin: co.xenastudios.xrewind.XRewindPlugin) {
            val days = plugin.config[co.xenastudios.xrewind.config.XRewindConfig.purge.keepDays]
            val db = plugin.database ?: return
            if (days <= 0) return
            val purge = Purge(db, co.xenastudios.xrewind.log.StoredTime.ago(plugin.now(), days * 86_400L)) { plugin.running }
            purge.run().onSettled { result, _ ->
                when (result) {
                    is Outcome.Ok -> if (result.value > 0) plugin.log.info("Purged ${result.value} changes older than $days days.")
                    is Outcome.Err -> plugin.log.warn("The daily purge failed; it tries again tomorrow: ${result.error}")
                    null -> Unit
                }
            }
        }

        public const val BATCH: Int = 5_000

        /** Free pages handed back per turn: about 8 MB at SQLite's 4 KB pages. */
        public const val VACUUM_PAGES: Int = 2_000
    }
}
