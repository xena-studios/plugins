package co.xenastudios.core.jvm.scheduler

import co.xenastudios.core.common.platform.Log
import co.xenastudios.core.common.platform.Threading
import co.xenastudios.core.common.scheduler.Scheduler
import co.xenastudios.core.common.scheduler.Task
import co.xenastudios.core.common.scheduler.Where
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.uuid.Uuid

/**
 * The core's [Scheduler] on a proxy ([Threading.PROXY]), where there are no worlds and no main
 * thread: Velocity runs events on a pool, BungeeCord on its network threads, and both
 * schedulers run tasks on pools. So each plugin gets one thread of its own, the plugin's
 * "global" thread, for global work and work for a player: the plugin's state stays on one
 * thread, as shared code written for a main thread expects, and none of its work waits behind
 * another plugin's. Async work goes to an [AsyncExecutor].
 *
 * - **No places:** [Where.At] is refused; a proxy has no world for it to be in.
 * - **Players:** work [Where.On] a player runs on the plugin's thread while [online] says
 *   they're on the proxy, and is retired, its `retired` callback run, once they're gone.
 * - **No ticks:** delays are exact, and a delay of zero runs as soon as the thread is free.
 *
 * The thread is made when there's work and ends after a minute without any, so a plugin with
 * nothing scheduled has no thread. A task that throws is logged; a repeating one keeps going.
 */
public class ProxyScheduler(
    name: String,
    private val log: Log,
    /** Whether the player with this UUID is on the proxy now. */
    private val online: (Uuid) -> Boolean,
) : Scheduler {

    @Volatile
    private var thread: Thread? = null

    private val global = ScheduledThreadPoolExecutor(1, ThreadFactory { runnable ->
        Thread({
            thread = Thread.currentThread()
            runnable.run()
        }, "$name global").apply { isDaemon = true }
    }).apply {
        removeOnCancelPolicy = true
        setKeepAliveTime(60, TimeUnit.SECONDS)
        allowCoreThreadTimeOut(true)
    }

    private val async = AsyncExecutor(name, log)

    override fun run(where: Where, delay: Duration, period: Duration?, retired: (() -> Unit)?, action: (Task) -> Unit): Task {
        require(!delay.isNegative()) { "A delay can't be negative: $delay" }
        require(period == null || period.isPositive()) { "A period must be positive: $period" }
        require(where !is Where.At) { "A proxy has no worlds, so no work runs at a place: $where" }
        if (where == Where.Async) return async.run(delay, period, action)
        val task = GlobalTask((where as? Where.On)?.entity, period, retired, action)
        if (where is Where.On && !online(where.entity)) {
            task.cancelled = true
            retired?.invoke()
            return task
        }
        task.schedule(delay)
        return task
    }

    override fun owns(where: Where): Boolean {
        val here = Thread.currentThread() === thread
        return when (where) {
            Where.Async -> !here
            else -> here
        }
    }

    /**
     * Stops taking work and cancels what's waiting, then gives running tasks up to five seconds
     * to finish, as the plugin is disabled.
     */
    public fun close() {
        global.shutdownNow()
        if (!global.awaitTermination(5, TimeUnit.SECONDS)) log.warn("The plugin's thread was still running five seconds after it stopped")
        async.close()
    }

    private inner class GlobalTask(
        private val entity: Uuid?,
        private val period: Duration?,
        private val retired: (() -> Unit)?,
        private val action: (Task) -> Unit,
    ) : Task, Runnable {
        @Volatile
        var cancelled = false

        @Volatile
        private var waiting: Future<*>? = null

        override val isCancelled: Boolean get() = cancelled

        fun schedule(delay: Duration) {
            try {
                waiting = global.schedule(this, delay.inWholeNanoseconds, TimeUnit.NANOSECONDS)
                if (cancelled) waiting?.cancel(false)
            } catch (e: RejectedExecutionException) {
                // The plugin is stopping: nothing new runs.
                cancelled = true
            }
        }

        override fun run() {
            if (cancelled) return
            if (entity != null && !online(entity)) {
                cancelled = true
                retired?.invoke()
                return
            }
            try {
                action(this)
            } catch (e: Throwable) {
                if (e is VirtualMachineError) throw e
                log.error("A task on the plugin's thread failed", e)
            }
            // Counted from the end of this run, so a slow one never runs twice at once.
            if (period != null && !cancelled) schedule(period)
        }

        override fun cancel() {
            cancelled = true
            waiting?.cancel(false)
        }
    }
}
