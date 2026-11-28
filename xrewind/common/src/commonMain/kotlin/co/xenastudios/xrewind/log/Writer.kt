package co.xenastudios.xrewind.log

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.newPending
import co.xenastudios.core.api.onSettled
import co.xenastudios.core.api.pendingOf
import co.xenastudios.core.api.resolve
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.db.Database
import co.xenastudios.core.common.db.DbError
import co.xenastudios.core.common.db.SqlSession
import co.xenastudios.core.common.db.SqlValue
import co.xenastudios.core.common.db.Statement
import co.xenastudios.core.common.locked
import co.xenastudios.core.common.platform.Log
import co.xenastudios.core.common.platform.Platform
import co.xenastudios.core.common.scheduler.Task
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.world.ItemData
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Writes the log: takes records off the [LogQueue] and hands them to the core's database writer
 * in batches, each one transaction, every half second or as soon as a batch is waiting (1.x's
 * writer, on the core's database layer: decisions/2026-11-27-xrewind-2.md). One batch is in flight
 * at a time, so the dictionaries are only ever touched by whoever holds it. A batch the database
 * refuses (a row it won't take) is tried again record by record, and only the record at fault is
 * dropped, with a line in the console; a database that's down is the core's to wait for.
 */
public class Writer(
    internal val platform: Platform,
    internal val queue: LogQueue,
    internal val log: Log,
    /** A platform's stack of one as bytes, its own format: on the writer, off the server's threads. */
    internal val itemBytes: (ItemData) -> ByteArray?,
) {
    internal val lock = Any()

    @Volatile
    internal var database: Database? = null
    internal var dictionaries: Dictionaries? = null
    internal var loading = false
    internal var busy = false
    private var ticker: Task? = null

    @Volatile
    public var written: Long = 0
        internal set

    @Volatile
    public var dropped: Long = 0
        internal set

    @Volatile
    public var lastBatchSize: Int = 0
        internal set

    @Volatile
    public var lastBatchMillis: Double = 0.0
        internal set

    /** What's stopping writes now, or `null` when they're going through. */
    @Volatile
    public var problem: String? = null
        internal set

    // Flushes waiting on a count of records written or dropped.
    private val waiters = ArrayList<Pair<Long, Pending<Boolean>>>()

    /** Starts writing to [db] (a new database on a reload): its names are read first. Any thread. */
    public fun use(db: Database?) {
        locked(lock) {
            database = db
            dictionaries = null
        }
        if (db != null) kick()
    }

    /** The database being written to, or `null` before one is open. */
    public val current: Database? get() = database

    public fun start() {
        queue.full = Kick(this, 0)
        if (ticker == null) ticker = platform.scheduler.run(Where.Async, FLUSH, FLUSH, action = Kick(this, 1))
    }

    /** Stops the timer; what's waiting is written by [drainAll], as the plugin stops. */
    public fun stop() {
        ticker?.cancel()
        ticker = null
        queue.full = null
    }

    /** Writes everything waiting, batch after batch, settling once it's all in (or has failed). */
    public fun drainAll(): Pending<Unit> {
        val done = newPending<Unit>()
        val target = target()
        flushed(target).onSettled { _, _ -> done.resolve(Unit) }
        kick()
        return done
    }

    /** A timer's tick, or the queue saying a batch is waiting: a class, not a lambda each. */
    private class Kick(private val writer: Writer, private val kind: Int) : () -> Unit, (Task) -> Unit {
        override fun invoke() {
            writer.platform.scheduler.run(Where.Async, action = this)
        }

        override fun invoke(task: Task) {
            writer.kick()
        }
    }

    /** Writes the next batch if none is in flight. Any thread. */
    public fun kick() {
        val claimed = locked(lock) {
            val d = database
            if (busy || d == null || queue.waiting == 0 && waiters.isEmpty()) {
                null
            } else {
                busy = true
                d to dictionaries
            }
        } ?: return
        val db = claimed.first
        val names = claimed.second
        if (names == null) {
            Batches.loadNames(this, db)
            return
        }
        val batch = ArrayList<Record>(minOf(queue.waiting, MAX_BATCH))
        queue.drain(batch, MAX_BATCH)
        if (batch.isEmpty()) {
            locked(lock) { busy = false }
            checkWaiters()
            return
        }
        Batches.write(this, db, names, batch, isolating = false)
    }

    internal fun fail(message: String) {
        if (message != problem) log.warn(message)
        problem = message
    }

    /**
     * Everything queued so far, as a count [flushed] can wait for: what the writer has taken and
     * what's still waiting. Counting what's waiting first means one taken meanwhile is counted
     * twice at worst, never not at all.
     */
    public fun target(): Long {
        val waiting = queue.waiting
        return queue.taken + waiting
    }

    /** Settles `true` once [target] records are written or dropped and writes are going through; `false` after [timeoutMillis]. */
    public fun flushed(target: Long, timeoutMillis: Long = 10_000): Pending<Boolean> {
        if (written + dropped >= target) return pendingOf(problem == null)
        val pending = newPending<Boolean>()
        locked(lock) { waiters += target to pending }
        platform.scheduler.run(Where.Async, timeoutMillis.milliseconds) {
            val removed = locked(lock) { waiters.removeAll { it.second === pending } }
            if (removed) pending.resolve(false)
        }
        kick()
        return pending
    }

    /** [flushed] of everything queued before the call. */
    public fun flush(timeoutMillis: Long = 10_000): Pending<Boolean> = flushed(target(), timeoutMillis)

    internal fun checkWaiters() {
        val ready = locked(lock) {
            if (waiters.isEmpty()) return
            val done = waiters.filter { written + dropped >= it.first }
            waiters.removeAll(done)
            done
        }
        for ((_, pending) in ready) pending.resolve(problem == null)
    }

    public companion object {
        /** How often waiting records are written when there are too few to wake the writer. */
        public val FLUSH: kotlin.time.Duration = 500.milliseconds

        /** The most records one transaction writes. */
        public const val MAX_BATCH: Int = 10_000

    }
}
