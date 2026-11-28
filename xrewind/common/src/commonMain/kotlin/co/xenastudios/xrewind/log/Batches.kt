package co.xenastudios.xrewind.log

import co.xenastudios.core.api.onSettled
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.db.Database
import co.xenastudios.core.common.db.DbError
import co.xenastudios.core.common.db.SqlSession
import co.xenastudios.core.common.db.write
import co.xenastudios.core.common.locked
import co.xenastudios.core.common.scheduler.Where
import kotlin.time.Duration.Companion.seconds

/**
 * The [Writer]'s batches: its names read, each batch written as one transaction, and one the
 * database refuses tried record by record. An object of its own, loaded with the first batch, not
 * as the server starts (decisions/2026-11-25-a-lighter-core.md).
 */
internal object Batches {
    fun loadNames(w: Writer, db: Database) {
        if (w.loading) return
        w.loading = true
        val names = Dictionaries(db.sql)
        db.read(LoadNames(names)).onSettled { result, error ->
            w.loading = false
            val ok = result is Outcome.Ok
            locked(w.lock) {
                w.busy = false
                if (ok && w.database === db) w.dictionaries = names
            }
            if (ok) {
                w.kick()
            } else {
                w.fail("couldn't read ${db.description}'s names: ${(result as? Outcome.Err)?.error ?: error}. Trying again.")
                w.platform.scheduler.run(Where.Async, 1.seconds) { w.kick() }
            }
        }
    }

    private class LoadNames(private val names: Dictionaries) : (SqlSession) -> Outcome<Unit, DbError> {
        override fun invoke(session: SqlSession): Outcome<Unit, DbError> = names.load(session)
    }

    // One batch as one write: names first, then each table's rows together, so each table's
    // inserts go to JDBC as one batch, then the marks (which can be for rows in this batch).
    fun write(w: Writer, db: Database, names: Dictionaries, batch: List<Record>, isolating: Boolean) {
        val started = w.platform.clock.elapsed()
        val statements = try {
            Rows.statements(db, names, batch, w.itemBytes)
        } catch (e: Exception) {
            w.log.warn("Couldn't prepare ${batch.size} logged changes: $e", e)
            w.dropped += batch.size
            done(w, batch.size, 0)
            return
        }
        val pendingNames = names.pending()
        db.write(pendingNames + statements).onSettled { result, error ->
            when {
                result is Outcome.Ok -> {
                    names.committed()
                    w.lastBatchSize = batch.size
                    w.lastBatchMillis = (w.platform.clock.elapsed() - started).inWholeMicroseconds / 1000.0
                    if (w.problem != null) {
                        w.log.info("Saving logged changes again.")
                        w.problem = null
                    }
                    done(w, 0, batch.size)
                }
                result is Outcome.Err && result.error.kind == DbError.Kind.CLOSED -> {
                    // Closed under it (a reload switched databases, or the server stops): back to the
                    // w.queue's front isn't possible, so these are w.written to the new one in order of arrival.
                    w.problem = "the w.database was closed"
                    done(w, batch.size, 0)
                }
                !isolating && batch.size > 1 -> {
                    w.fail("couldn't save ${batch.size} logged changes (${(result as? Outcome.Err)?.error ?: error}): finding the one the w.database won't take.")
                    isolate(w, db, names, batch, 0)
                }
                else -> {
                    w.log.warn("Dropped one logged change the w.database wouldn't take (${batch.firstOrNull()?.let { it::class.simpleName }}): ${(result as? Outcome.Err)?.error ?: error}")
                    done(w, batch.size, 0)
                }
            }
        }
    }

    // The batch one record at a time, each its own write, dropping only those the w.database refuses.
    private fun isolate(w: Writer, db: Database, names: Dictionaries, batch: List<Record>, from: Int) {
        if (from >= batch.size) {
            w.problem = null
            locked(w.lock) { w.busy = false }
            w.checkWaiters()
            w.kick()
            return
        }
        val one = listOf(batch[from])
        val statements = try {
            Rows.statements(db, names, one, w.itemBytes)
        } catch (e: Exception) {
            w.dropped++
            return isolate(w, db, names, batch, from + 1)
        }
        db.write(names.pending() + statements).onSettled { result, _ ->
            if (result is Outcome.Ok) {
                names.committed()
                w.written++
            } else {
                w.dropped++
                w.log.warn("Dropped one logged change the w.database wouldn't take (${one[0]::class.simpleName}): ${(result as? Outcome.Err)?.error}")
            }
            isolate(w, db, names, batch, from + 1)
        }
    }

    private fun done(w: Writer, lost: Int, saved: Int) {
        w.dropped += lost
        w.written += saved
        locked(w.lock) { w.busy = false }
        w.checkWaiters()
        // A full batch means more is waiting: go again at once.
        if (w.queue.waiting > 0) w.kick()
    }
}
