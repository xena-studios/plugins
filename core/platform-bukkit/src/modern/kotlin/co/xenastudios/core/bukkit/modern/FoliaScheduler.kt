package co.xenastudios.core.bukkit.modern

import co.xenastudios.core.common.platform.Log
import co.xenastudios.core.common.scheduler.Scheduler
import co.xenastudios.core.common.scheduler.Task
import co.xenastudios.core.common.scheduler.Where
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.plugin.Plugin
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.uuid.toJavaUuid

/**
 * The core's [Scheduler] on Folia, where Bukkit's scheduler throws: global work on the global
 * region scheduler, a place's on its region's, an entity's on the entity's own scheduler,
 * which follows it between regions and runs `retired` when it's gone, and async work on
 * Folia's async scheduler, whose delays are exact.
 *
 * Region and entity work needs the world or the entity now, when it's scheduled: work for a
 * world that isn't loaded can't run anywhere, so it's cancelled and said in the console, and
 * work for an entity that's already gone is retired at once.
 */
internal class FoliaScheduler(private val plugin: Plugin, private val log: Log) : Scheduler, AutoCloseable {

    private val server = plugin.server

    @Volatile
    private var closed = false

    override fun run(where: Where, delay: Duration, period: Duration?, retired: (() -> Unit)?, action: (Task) -> Unit): Task {
        require(!delay.isNegative()) { "A delay can't be negative: $delay" }
        require(period == null || period.isPositive()) { "A period must be positive: $period" }
        val task = FoliaTask(retired, action)
        if (closed) return task.also { it.cancelled = true }
        val run = Consumer<ScheduledTask> { task.runWith(it) }
        // Folia counts region and entity delays in ticks, at least one: the next tick.
        val ticks = Ticks.of(delay)
        val handle: ScheduledTask? = when (where) {
            Where.Global -> {
                val global = server.globalRegionScheduler
                if (period == null) global.runDelayed(plugin, run, ticks) else global.runAtFixedRate(plugin, run, ticks, Ticks.of(period))
            }
            is Where.At -> {
                val location = where.location
                val world = server.getWorld(location.world.name)
                if (world == null) {
                    log.warn("Work at $location wasn't run: the world ${location.world} isn't loaded")
                    task.cancelled = true
                    return task
                }
                val regions = server.regionScheduler
                if (period == null) {
                    regions.runDelayed(plugin, world, location.chunkX, location.chunkZ, run, ticks)
                } else {
                    regions.runAtFixedRate(plugin, world, location.chunkX, location.chunkZ, run, ticks, Ticks.of(period))
                }
            }
            is Where.On -> {
                val entity = server.entityById(where.entity.toJavaUuid())
                val gone = Runnable { task.retire() }
                val handle = entity?.scheduler?.let { entities ->
                    if (period == null) entities.runDelayed(plugin, run, gone, ticks) else entities.runAtFixedRate(plugin, run, gone, ticks, Ticks.of(period))
                }
                // Gone already: not there to find, or Folia has retired its scheduler.
                if (handle == null) {
                    task.retire()
                    return task
                }
                handle
            }
            Where.Async -> {
                val async = server.asyncScheduler
                when {
                    period != null -> async.runAtFixedRate(plugin, run, delay.inWholeNanoseconds, period.inWholeNanoseconds, TimeUnit.NANOSECONDS)
                    delay.isPositive() -> async.runDelayed(plugin, run, delay.inWholeNanoseconds, TimeUnit.NANOSECONDS)
                    else -> async.runNow(plugin, run)
                }
            }
        }
        task.attach(handle)
        return task
    }

    override fun owns(where: Where): Boolean = when (where) {
        Where.Global -> server.isGlobalTickThread || shuttingDownHere()
        is Where.At -> server.getWorld(where.location.world.name)?.let { server.isOwnedByCurrentRegion(it, where.location.chunkX, where.location.chunkZ) } ?: false
        is Where.On -> server.entityById(where.entity.toJavaUuid())?.let { server.isOwnedByCurrentRegion(it) } ?: false
        // Folia's isPrimaryThread is true on every tick thread, global and regions alike.
        Where.Async -> !server.isPrimaryThread
    }

    // Folia disables plugins on its shutdown thread, once every region has stopped: it owns
    // the whole server then, the global thread's work included, and no other thread is left.
    private fun shuttingDownHere(): Boolean = server.isStopping && server.isPrimaryThread

    /** Stops taking work and cancels what's waiting: the plugin is being disabled. Entity work Folia drops by itself. */
    override fun close() {
        closed = true
        server.globalRegionScheduler.cancelTasks(plugin)
        server.asyncScheduler.cancelTasks(plugin)
    }

    private class FoliaTask(private val retired: (() -> Unit)?, private val action: (Task) -> Unit) : Task {
        @Volatile
        var cancelled = false

        @Volatile
        private var handle: ScheduledTask? = null

        override val isCancelled: Boolean get() = cancelled

        // Folia may run it before run() returns, so a cancel that came first is applied here.
        fun attach(scheduled: ScheduledTask?) {
            if (scheduled == null) return
            handle = scheduled
            if (cancelled) scheduled.cancel()
        }

        fun runWith(scheduled: ScheduledTask) {
            handle = scheduled
            if (cancelled) {
                scheduled.cancel()
                return
            }
            action(this)
        }

        fun retire() {
            if (cancelled) return
            cancelled = true
            retired?.invoke()
        }

        override fun cancel() {
            cancelled = true
            handle?.cancel()
        }
    }
}
