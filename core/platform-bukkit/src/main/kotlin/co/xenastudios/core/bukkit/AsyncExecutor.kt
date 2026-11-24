package co.xenastudios.core.bukkit

import co.xenastudios.core.common.platform.Log
import co.xenastudios.core.common.scheduler.Task
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.Volatile
import kotlin.time.Duration

/**
 * The plugin's async work off Folia: Bukkit's own async scheduler starts tasks only on a tick,
 * so its delays are whole ticks, and the core promises async delays exact. One timer thread
 * waits out the delays and hands each task to a pool that grows with the work and shrinks to
 * nothing after a minute idle, so a plugin with no async work has no threads.
 *
 * A repeating task's next run is counted from the end of the last, so a slow one never runs
 * twice at once.
 */
internal class AsyncExecutor(name: String, private val log: Log) {

    private val timer = ScheduledThreadPoolExecutor(1, threads("$name timer")).apply {
        // A cancelled delay is dropped at once rather than kept until it would have run.
        removeOnCancelPolicy = true
        setKeepAliveTime(60, TimeUnit.SECONDS)
        allowCoreThreadTimeOut(true)
    }
    private val pool = ThreadPoolExecutor(0, Int.MAX_VALUE, 60, TimeUnit.SECONDS, SynchronousQueue(), threads("$name async"))

    fun run(delay: Duration, period: Duration?, action: (Task) -> Unit): Task {
        val task = AsyncTask(period, action)
        task.schedule(delay)
        return task
    }

    /**
     * Stops taking work and cancels what's waiting, then gives running tasks up to five seconds
     * to finish, as the plugin is disabled.
     */
    fun close() {
        timer.shutdownNow()
        pool.shutdown()
        if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
            log.warn("Async tasks were still running five seconds after the plugin stopped; they're interrupted now")
            pool.shutdownNow()
        }
    }

    private inner class AsyncTask(private val period: Duration?, private val action: (Task) -> Unit) : Task, Runnable {
        @Volatile
        private var cancelled = false

        @Volatile
        private var waiting: Future<*>? = null

        override val isCancelled: Boolean get() = cancelled

        fun schedule(delay: Duration) {
            try {
                waiting = if (delay.isPositive()) {
                    timer.schedule(Runnable { start() }, delay.inWholeNanoseconds, TimeUnit.NANOSECONDS)
                } else {
                    pool.submit(this)
                }
            } catch (e: RejectedExecutionException) {
                // The plugin is stopping: nothing new runs.
                cancelled = true
            }
        }

        private fun start() {
            try {
                if (!cancelled) waiting = pool.submit(this)
            } catch (e: RejectedExecutionException) {
                cancelled = true
            }
        }

        override fun run() {
            if (cancelled) return
            try {
                action(this)
            } catch (e: Throwable) {
                if (e is VirtualMachineError) throw e
                // Bukkit's scheduler logs a task that throws; a pool would drop it silently.
                log.error("An async task failed", e)
            }
            if (period != null && !cancelled) schedule(period)
        }

        override fun cancel() {
            cancelled = true
            waiting?.cancel(false)
        }
    }

    private fun threads(name: String): ThreadFactory {
        val count = AtomicInteger()
        return ThreadFactory { runnable ->
            Thread(runnable, "$name #${count.incrementAndGet()}").apply { isDaemon = true }
        }
    }
}
