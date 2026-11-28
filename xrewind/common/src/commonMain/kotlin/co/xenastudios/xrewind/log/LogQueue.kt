package co.xenastudios.xrewind.log

import co.xenastudios.core.common.locked
import co.xenastudios.core.common.platform.Log
import kotlin.concurrent.Volatile

/**
 * Where listeners leave records for the writer: safe from any thread, Folia's region threads
 * included, and a few dozen nanoseconds a record (one uncontended lock, an array slot).
 *
 * If the writer falls behind (the database is slow or down), records wait here. Past [WARN_AT]
 * the console is told; past [LIMIT], new records are counted and dropped rather than run the
 * server out of memory, and `/xrewind status` says how many (1.x's limits).
 */
public class LogQueue(private val log: Log) {
    private val lock = Any()
    private var items = arrayOfNulls<Record>(1024)
    private var head = 0
    private var count = 0

    @Volatile
    private var size = 0

    @Volatile
    private var dropped = 0L

    // Records the writer has taken, ever: see taken().
    @Volatile
    private var takenCount = 0L

    /** Worlds nothing is logged in. Swapped whole on reload. */
    @Volatile
    public var excluded: Set<String> = emptySet()

    /** Called once [BATCH] records are waiting, so the writer goes early. */
    @Volatile
    public var full: (() -> Unit)? = null

    /** Hands a record to the writer. Never blocks for long. Returns whether it was queued. */
    public fun add(record: Record): Boolean {
        val skip = excluded
        val world = record.world
        if (world != null && skip.isNotEmpty() && world in skip) return false
        var wake = false
        var warn = false
        var refused = false
        locked(lock) {
            if (count >= LIMIT) {
                refused = true
            } else {
                if (count == items.size) grow()
                items[(head + count) % items.size] = record
                count++
                size = count
                wake = count == BATCH
                warn = count == WARN_AT
            }
        }
        if (refused) {
            if (dropped++ == 0L) {
                log.error("$LIMIT changes are waiting to be saved, so new ones aren't being logged. Check the database; /xrewind status shows how many were missed.")
            }
            return false
        }
        if (warn) log.warn("$WARN_AT changes are waiting to be saved: the database is slow or can't be reached.")
        if (wake) full?.invoke()
        return true
    }

    /** Up to [max] records, oldest first, into [into]. The writer only. */
    public fun drain(into: MutableList<Record>, max: Int): Int = locked(lock) {
        val n = minOf(max, count)
        for (i in 0 until n) {
            val at = (head + i) % items.size
            into += items[at]!!
            items[at] = null
        }
        head = (head + n) % items.size
        count -= n
        // Counted as taken before it stops counting as waiting, so a record being taken is never
        // in neither count (Writer.flush).
        takenCount += n
        size = count
        if (count == 0 && items.size > SHRINK_AT) {
            items = arrayOfNulls(1024)
            head = 0
        }
        n
    }

    private fun grow() {
        val bigger = arrayOfNulls<Record>(items.size * 2)
        for (i in 0 until count) bigger[i] = items[(head + i) % items.size]
        items = bigger
        head = 0
    }

    /** Records waiting. */
    public val waiting: Int get() = size

    /** Records the writer has taken since the queue was made. */
    public val taken: Long get() = takenCount

    /** Records dropped because too many were waiting. */
    public val missed: Long get() = dropped

    public companion object {
        /** The writer is woken early once this many are waiting. */
        public const val BATCH: Int = 2_000
        public const val WARN_AT: Int = 100_000

        /** About 100 MB of waiting records. */
        public const val LIMIT: Int = 1_000_000

        private const val SHRINK_AT = 65_536
    }
}
