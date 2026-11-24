package co.xenastudios.core.bukkit

import co.xenastudios.core.bukkit.modern.Ticks
import co.xenastudios.core.common.scheduler.Scheduler
import co.xenastudios.core.common.scheduler.Task
import co.xenastudios.core.common.scheduler.Where
import org.bukkit.entity.Entity
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.uuid.Uuid

/**
 * The core's [Scheduler] on every Bukkit but Folia: global, place and entity work on the main
 * thread through Bukkit's scheduler, which Paper keeps running while an empty server is paused
 * (DECISIONS.md, "Global work survives an empty server's pause"), and async work on [async],
 * which keeps delays exact.
 *
 * Work for an entity checks it's still there before each run, through [entities]; one that's
 * gone is cancelled and its `retired` callback runs, as Folia's entity scheduler does.
 */
internal class MainThreadScheduler(
    private val plugin: Plugin,
    private val entities: Entities,
    private val async: AsyncExecutor,
) : Scheduler {

    @Volatile
    private var closed = false

    override fun run(where: Where, delay: Duration, period: Duration?, retired: (() -> Unit)?, action: (Task) -> Unit): Task {
        require(!delay.isNegative()) { "A delay can't be negative: $delay" }
        require(period == null || period.isPositive()) { "A period must be positive: $period" }
        if (where == Where.Async) return async.run(delay, period, action)
        val task = MainTask((where as? Where.On)?.entity, retired, action)
        if (closed) return task.also { it.cancelled = true }
        val scheduler = plugin.server.scheduler
        val handle = if (period == null) {
            scheduler.runTaskLater(plugin, task, Ticks.of(delay))
        } else {
            scheduler.runTaskTimer(plugin, task, Ticks.of(delay), Ticks.of(period))
        }
        task.attach(handle)
        return task
    }

    override fun owns(where: Where): Boolean = plugin.server.isPrimaryThread != (where == Where.Async)

    /** Stops taking work, and cancels what's waiting: the plugin is being disabled. */
    fun close() {
        closed = true
        plugin.server.scheduler.cancelTasks(plugin)
        async.close()
    }

    private inner class MainTask(
        private val entity: Uuid?,
        private val retired: (() -> Unit)?,
        private val action: (Task) -> Unit,
    ) : Task, Runnable {
        @Volatile
        var cancelled = false

        @Volatile
        private var handle: BukkitTask? = null

        override val isCancelled: Boolean get() = cancelled

        // Bukkit may run it before runTaskLater returns (from another thread), so a cancel
        // that came first is applied here.
        fun attach(bukkit: BukkitTask) {
            handle = bukkit
            if (cancelled) bukkit.cancel()
        }

        override fun run() {
            if (cancelled) {
                handle?.cancel()
                return
            }
            if (entity != null && entities.find(entity) == null) {
                cancel()
                retired?.invoke()
                return
            }
            action(this)
        }

        override fun cancel() {
            cancelled = true
            handle?.cancel()
        }
    }
}

/** Finds an entity by UUID, the way this server can: the platform picks one at startup. */
internal fun interface Entities {
    /** The entity, if it's still in a world: a player who's online, or a mob that hasn't died or been removed. */
    fun find(uuid: Uuid): Entity?
}
