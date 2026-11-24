package co.xenastudios.core.testing

import co.xenastudios.core.api.Location
import co.xenastudios.core.api.WorldRef
import co.xenastudios.core.common.platform.Threading
import co.xenastudios.core.common.scheduler.Scheduler
import co.xenastudios.core.common.scheduler.TICK
import co.xenastudios.core.common.scheduler.Task
import co.xenastudios.core.common.scheduler.Where
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

/**
 * A [Scheduler] whose time moves only when a test says ([advance], [tick]), and which runs
 * everything on the test's own thread, in a fixed order: by when it's due, then by when it was
 * scheduled. So a test of timing (a cooldown, a warmup, a repeating save) is exact, and the same
 * on the JVM and on Wasm.
 *
 * It still knows which "thread" each task is on, and answers [owns] by the rules of its
 * [threading]: with [Threading.REGIONS], Folia's, so a task that touches a place or an entity its
 * region doesn't own fails a [co.xenastudios.core.common.scheduler.checkThread] in a test, as it
 * would crash on Folia. Regions here are squares of 16 by 16 chunks, as on a fresh Folia server.
 * Code outside any task (the test itself) is on [Where.Global], unless it says otherwise with
 * [runAs].
 *
 * Entities exist once [spawn]ed; work for one that doesn't, or that [despawn]s, is cancelled
 * and its `retired` callback runs, as on Folia.
 */
public class VirtualScheduler(public val threading: Threading = Threading.REGIONS) : Scheduler {

    private class Entry(
        var due: Duration,
        var order: Long,
        val where: Where,
        val period: Duration?,
        val retired: (() -> Unit)?,
        val action: (Task) -> Unit,
    ) : Task {
        var cancelledFlag = false
        lateinit var queue: MutableList<Entry>
        override val isCancelled: Boolean get() = cancelledFlag

        override fun cancel() {
            cancelledFlag = true
            queue.remove(this)
        }
    }

    private val queue = ArrayList<Entry>()
    private var nextOrder = 0L
    private val entities = LinkedHashMap<Uuid, Location>()
    private var outside: Where = Where.Global
    private var running: Where? = null

    /** How much virtual time has passed since the scheduler was made. */
    public var time: Duration = Duration.ZERO
        private set

    /** How many tasks are waiting to run, repeating ones included: zero once everything is cancelled or done. */
    public val pending: Int get() = queue.size

    /** Where the code running now is: the task's [Where] inside a task, or [runAs]'s outside one. */
    public val current: Where get() = running ?: outside

    override fun run(where: Where, delay: Duration, period: Duration?, retired: (() -> Unit)?, action: (Task) -> Unit): Task {
        require(!delay.isNegative()) { "A delay can't be negative: $delay" }
        require(period == null || period.isPositive()) { "A period must be positive: $period" }
        val entry = Entry(dueAfter(where, delay), nextOrder++, where, period, retired, action)
        entry.queue = queue
        if (where is Where.On && where.entity !in entities) {
            // Folia's entity scheduler refuses an entity that's gone, and the retired callback runs.
            entry.cancelledFlag = true
            retired?.invoke()
            return entry
        }
        queue += entry
        return entry
    }

    override fun owns(where: Where): Boolean {
        val here = current
        return when (threading) {
            Threading.SINGLE -> true
            Threading.MAIN_THREAD -> (where == Where.Async) == (here == Where.Async)
            Threading.REGIONS -> {
                val mine = region(here)
                mine != null && mine == region(where)
            }
        }
    }

    /** Moves time on by [duration], running every task that comes due on the way, in order. */
    public fun advance(duration: Duration) {
        require(!duration.isNegative()) { "Time only moves forward: $duration" }
        val target = time + duration
        while (true) {
            val next = queue.filter { it.due <= target }.minWithOrNull(compareBy<Entry>({ it.due }, { it.order })) ?: break
            queue.remove(next)
            time = next.due
            runEntry(next)
        }
        time = target
    }

    /** Moves time on by [ticks] server ticks of 50 ms. */
    public fun tick(ticks: Int = 1) {
        advance(TICK * ticks)
    }

    /** Runs what's due now without moving time: async work scheduled with no delay. */
    public fun runPending() {
        advance(Duration.ZERO)
    }

    /** Runs [block] as if on the thread that owns [where]: a player's, for a command a player typed. */
    public fun <T> runAs(where: Where, block: () -> T): T {
        val before = outside
        outside = where
        try {
            return block()
        } finally {
            outside = before
        }
    }

    /** An entity with this [uuid] is now at [location]: work [Where.On] it can run. */
    public fun spawn(uuid: Uuid, location: Location) {
        entities[uuid] = location
    }

    /** The entity walked to [location], and its work moves with it to that region's thread. */
    public fun move(uuid: Uuid, location: Location) {
        check(uuid in entities) { "No entity $uuid to move" }
        entities[uuid] = location
    }

    /** The entity is gone (a player logged out): its waiting work is cancelled and each `retired` callback runs. */
    public fun despawn(uuid: Uuid) {
        entities.remove(uuid) ?: return
        val gone = queue.filter { (it.where as? Where.On)?.entity == uuid }
        for (entry in gone) {
            entry.cancel()
            entry.retired?.invoke()
        }
    }

    private fun runEntry(entry: Entry) {
        val previous = running
        running = entry.where
        try {
            entry.action(entry)
        } finally {
            running = previous
            val period = entry.period
            if (period != null && !entry.cancelledFlag) {
                entry.due = dueAfter(entry.where, period)
                entry.order = nextOrder++
                queue += entry
            }
        }
    }

    // Work on a server thread runs on a tick, at least the next one; async work runs when due.
    private fun dueAfter(where: Where, delay: Duration): Duration {
        if (where == Where.Async) return time + delay
        val tick = TICK.inWholeMilliseconds
        val tickNow = time.inWholeMilliseconds / tick
        val ticks = maxOf(1L, (delay.inWholeMilliseconds + tick - 1) / tick)
        return ((tickNow + ticks) * tick).milliseconds
    }

    // Which thread owns what [where] names, by Folia's rules; null for an entity that isn't there.
    private fun region(where: Where): Any? = when (where) {
        Where.Global -> Where.Global
        Where.Async -> Where.Async
        is Where.At -> Region(where.location.world, where.location.chunkX shr 4, where.location.chunkZ shr 4)
        is Where.On -> entities[where.entity]?.let { Region(it.world, it.chunkX shr 4, it.chunkZ shr 4) }
    }

    private data class Region(val world: WorldRef, val x: Int, val z: Int)
}
