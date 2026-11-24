package co.xenastudios.core.fabric

import co.xenastudios.core.common.platform.Log
import co.xenastudios.core.common.scheduler.Scheduler
import co.xenastudios.core.common.scheduler.TICK
import co.xenastudios.core.common.scheduler.Task
import co.xenastudios.core.common.scheduler.Where
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.uuid.Uuid

/**
 * The [Scheduler] for a server with one main thread, as Fabric's is ([co.xenastudios.core.common.platform.Threading.MAIN_THREAD]):
 * global, place and entity work all run on the server thread, on its ticks, and async work on
 * a small pool of our own.
 *
 * It knows no Minecraft: the adapter calls [tick] at the start of every server tick, and tells
 * it which thread is the server's ([isServerThread]) and whether an entity is still there
 * ([entityPresent], asked on the server thread). So its rules are tested on the JVM alone.
 *
 * Tasks wait on the server thread in a queue ordered by the tick they're due, then by when they
 * were scheduled, so work scheduled for the same tick runs in that order. Scheduling from any
 * thread is a lock-free add; only [tick] touches the queue. A tick with nothing due costs a
 * look at the queue's head (ROADMAP.md, Fast: under 0.001 ms a tick idle).
 *
 * Work [Where.On] an entity that's gone when it comes due (a player who logged out) is
 * cancelled and its `retired` callback runs instead, on the server thread. Folia refuses such
 * work when it's scheduled; here the entity is only checked on the server thread, where the
 * answer can't change under us.
 */
public class TickScheduler(
    private val log: Log,
    /** Whether the current thread is the server's, which owns every place and entity. */
    private val isServerThread: () -> Boolean,
    /** Whether the entity with this UUID is in a world. Only called on the server thread. */
    private val entityPresent: (Uuid) -> Boolean,
    /** What async threads are called in thread dumps, such as `xSample async`. */
    asyncName: String,
    asyncThreads: Int = defaultAsyncThreads(),
) : Scheduler {

    private inner class MainTask(
        var due: Long,
        var order: Long,
        val where: Where,
        val periodTicks: Long,
        val retired: (() -> Unit)?,
        val action: (Task) -> Unit,
    ) : Task, Comparable<MainTask> {
        @Volatile
        var cancelled: Boolean = false
        override val isCancelled: Boolean get() = cancelled

        override fun cancel() {
            cancelled = true
        }

        override fun compareTo(other: MainTask): Int =
            if (due != other.due) due.compareTo(other.due) else order.compareTo(other.order)
    }

    // Made cancelled when the scheduler is already closed, so it never runs.
    private inner class AsyncTask(refused: Boolean) : Task {
        @Volatile
        var cancelled: Boolean = refused

        @Volatile
        var future: ScheduledFuture<*>? = null
        override val isCancelled: Boolean get() = cancelled

        override fun cancel() {
            cancelled = true
            // Not interrupted: a task halfway through a file write finishes it.
            future?.cancel(false)
        }
    }

    // Async threads are this class, so owns(Async) is a type check, not a lookup.
    private class AsyncThread(runnable: Runnable, name: String) : Thread(runnable, name)

    private val incoming = ConcurrentLinkedQueue<MainTask>()
    private val queue = PriorityQueue<MainTask>()
    private val orders = AtomicLong()

    // The tick the server is on, read by any thread to work out when new work is due.
    @Volatile
    private var currentTick: Long = 0

    @Volatile
    private var closed = false

    private val async: ScheduledThreadPoolExecutor = run {
        val count = AtomicInteger()
        val factory = ThreadFactory { runnable ->
            AsyncThread(runnable, "$asyncName #${count.incrementAndGet()}").apply { isDaemon = true }
        }
        ScheduledThreadPoolExecutor(asyncThreads, factory).apply {
            // Idle threads end, so a plugin that does nothing async keeps no thread waiting.
            setKeepAliveTime(30, TimeUnit.SECONDS)
            allowCoreThreadTimeOut(true)
            removeOnCancelPolicy = true
            executeExistingDelayedTasksAfterShutdownPolicy = false
        }
    }

    /** How many tasks wait for the server thread, repeating ones included. On the server thread only. */
    public val pending: Int get() = queue.size + incoming.size

    override fun run(where: Where, delay: Duration, period: Duration?, retired: (() -> Unit)?, action: (Task) -> Unit): Task {
        require(!delay.isNegative()) { "A delay can't be negative: $delay" }
        require(period == null || period.isPositive()) { "A period must be positive: $period" }
        if (where == Where.Async) return runAsync(delay, period, action)
        val task = MainTask(currentTick + ticks(delay), orders.getAndIncrement(), where, period?.let(::ticks) ?: 0, retired, action)
        if (closed) {
            task.cancelled = true
        } else {
            incoming.add(task)
        }
        return task
    }

    override fun owns(where: Where): Boolean = when (where) {
        Where.Async -> Thread.currentThread() is AsyncThread
        else -> isServerThread()
    }

    /**
     * Runs every task due by this tick, in order: called by the adapter at the start of each
     * server tick, on the server thread. A task that throws is logged and the rest still run;
     * work through a module's context has already been contained by then.
     */
    public fun tick() {
        val now = ++currentTick
        while (true) queue.add(incoming.poll() ?: break)
        while (true) {
            val task = queue.peek() ?: break
            if (task.due > now) break
            queue.poll()
            if (task.cancelled) continue
            val where = task.where
            if (where is Where.On && !entityPresent(where.entity)) {
                task.cancelled = true
                runGuarded("a retired callback") { task.retired?.invoke() }
                continue
            }
            runGuarded("a task") { task.action(task) }
            if (task.periodTicks > 0 && !task.cancelled) {
                task.due = now + task.periodTicks
                task.order = orders.getAndIncrement()
                queue.add(task)
            }
        }
    }

    /**
     * Stops everything, as the plugin is disabled with the server: waiting work is dropped, and
     * async work already running gets [wait] to finish.
     */
    public fun close(wait: Duration) {
        closed = true
        for (task in queue) task.cancelled = true
        for (task in incoming) task.cancelled = true
        queue.clear()
        incoming.clear()
        async.shutdown()
        if (!async.awaitTermination(wait.inWholeMilliseconds, TimeUnit.MILLISECONDS)) {
            log.warn("Async work was still running ${wait.inWholeSeconds}s after the server stopped; it was interrupted")
            async.shutdownNow()
        }
    }

    private fun runAsync(delay: Duration, period: Duration?, action: (Task) -> Unit): Task {
        val task = AsyncTask(refused = closed)
        if (task.cancelled) return task
        val body = Runnable {
            if (!task.cancelled) runGuarded("an async task") { action(task) }
        }
        val delayMs = delay.inWholeMilliseconds
        try {
            task.future = if (period == null) {
                async.schedule(body, delayMs, TimeUnit.MILLISECONDS)
            } else {
                async.scheduleWithFixedDelay(body, delayMs, period.inWholeMilliseconds.coerceAtLeast(1), TimeUnit.MILLISECONDS)
            }
        } catch (_: RejectedExecutionException) {
            // Closed between the check above and here: as if it came after.
            task.cancelled = true
        }
        return task
    }

    private inline fun runGuarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            if (e is VirtualMachineError) throw e
            log.error("$what threw ${e::class.java.simpleName}: ${e.message}", e)
        }
    }

    public companion object {
        /**
         * Whole ticks for [delay], rounded up, and at least one: a delay of zero is the next
         * tick, never now ([Scheduler]).
         */
        public fun ticks(delay: Duration): Long {
            val tick = TICK.inWholeMilliseconds
            return maxOf(1L, (delay.inWholeMilliseconds + tick - 1) / tick)
        }

        private fun defaultAsyncThreads(): Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
    }
}
