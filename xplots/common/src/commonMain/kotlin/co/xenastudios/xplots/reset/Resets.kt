package co.xenastudios.xplots.reset

import co.xenastudios.core.api.Location
import co.xenastudios.core.api.onSettled
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.concurrentMap
import co.xenastudios.core.common.locked
import co.xenastudios.core.common.scheduler.Task
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.xplots.XPlotsPlugin
import co.xenastudios.xplots.config.XPlotsConfig
import co.xenastudios.xplots.front
import co.xenastudios.xplots.model.Box
import co.xenastudios.xplots.model.Plot
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Plot snapshots: saved when a plot is made, and put back when its rent ends. Both are jobs run
 * one after another, a few milliseconds of each tick (`reset-ms-per-tick`) on the thread that owns
 * the plot (its region's on Folia), each after loading the plot's chunks in the background. A plot
 * being put back can't be built in or rented until it's done; one still going as the server stops
 * is finished rather than left half put back, where this thread may.
 */
public class Resets(private val plugin: XPlotsPlugin) {
    private companion object {
        val RELEASE_AFTER = 5.seconds
    }

    /** Work done in steps, at one place. */
    internal abstract class Job(val plot: Plot) {
        /** Where it runs: the plot's middle. */
        val where: Where = Where.At(Location(plot.field.worldRef, (plot.box.minX + plot.box.maxX) / 2.0, plot.box.minY.toDouble(), (plot.box.minZ + plot.box.maxZ) / 2.0))

        /** Does some work, stopping once [deadline] (the clock's nanoseconds) passes; `true` once it's finished. */
        abstract fun step(deadline: Long): Boolean

        /** Called if the plugin stops before it finishes. */
        open fun abandon() {}
    }

    private val busy = concurrentMap<Plot, Boolean>()
    private val jobs = ArrayDeque<Job>()

    // Chunks kept loaded for the jobs, let go a few seconds after the last job ends, once the
    // signs placed after them are written.
    private val held = concurrentMap<Pair<String, Long>, Boolean>()

    @Volatile
    private var running: Task? = null

    @Volatile
    private var current: Job? = null

    @Volatile
    private var stopped = false

    /** Whether the plot is being put back, so nobody may build in or rent it yet. */
    public fun isBusy(plot: Plot): Boolean = busy.containsKey(plot)

    /** Saves each plot's blocks as they are now, as what it's put back to; then runs [then]. */
    public fun capture(plots: List<Plot>, then: () -> Unit) {
        locked(jobs) {
            for (plot in plots) jobs.addLast(Capture(plot))
            jobs.addLast(Then(plots.lastOrNull() ?: return@locked, then))
        }
        pump()
    }

    /** Puts the plot back as it was saved, then runs [then]. A plot with no snapshot is left as it is. */
    public fun reset(plot: Plot, then: () -> Unit) {
        if (busy.put(plot, true) != null) return
        val store = plugin.store ?: return done(plot, then)
        store.loadSnapshot(plot).onSettled { result, error ->
            val data = (result as? Outcome.Ok)?.value
            if (result !is Outcome.Ok) plugin.log.error("Couldn't read ${plot.id}'s saved blocks (${(result as? Outcome.Err)?.error ?: error}), so it was left as it is.")
            val snapshot = data?.let {
                try {
                    Snapshot.decode(it, plugin.deflate)
                } catch (e: IllegalArgumentException) {
                    plugin.log.error("Couldn't read ${plot.id}'s saved blocks (${e.message}), so it was left as it is.")
                    null
                }
            }
            if (snapshot == null || !matches(snapshot, plot.box)) {
                if (snapshot != null) plugin.log.warn("${plot.id}'s saved blocks are a different size from the plot, so it was left as it is.")
                return@onSettled done(plot, then)
            }
            locked(jobs) { jobs.addLast(Restore(plot, snapshot, then)) }
            pump()
        }
    }

    private fun done(plot: Plot, then: () -> Unit) {
        busy.remove(plot)
        then()
    }

    private fun matches(snapshot: Snapshot, box: Box): Boolean = snapshot.width == box.width && snapshot.height == box.height && snapshot.depth == box.depth

    // Starts the next job at its place, unless one is running.
    private fun pump() {
        if (stopped) return
        val job = locked(jobs) {
            if (current != null) return
            jobs.removeFirstOrNull()?.also { current = it }
        } ?: return
        load(job.plot) {
            running = plugin.platform.scheduler.run(job.where, period = 50.milliseconds, action = Tick(this, job))
        }
    }

    private class Tick(private val resets: Resets, private val job: Job) : (Task) -> Unit {
        override fun invoke(task: Task) = resets.tick(task, job)
    }

    private fun tick(task: Task, job: Job) {
        if (stopped) return task.cancel()
        val budget = plugin.config[XPlotsConfig.resetMsPerTick] * 1_000_000L
        val deadline = plugin.platform.clock.elapsed().inWholeNanoseconds + budget
        val finished = try {
            if (!loaded(job.plot)) false else job.step(deadline)
        } catch (e: Exception) {
            plugin.log.error("A plot job for ${job.plot.id} failed, and was stopped", e)
            true
        }
        if (!finished) return
        task.cancel()
        val idle = locked(jobs) {
            current = null
            running = null
            jobs.isEmpty()
        }
        if (idle && held.isNotEmpty()) plugin.platform.scheduler.run(Where.Global, delay = RELEASE_AFTER, action = Release(this))
        pump()
    }

    private class Release(private val resets: Resets) : (Task) -> Unit {
        override fun invoke(task: Task) = resets.release()
    }

    private fun release() {
        if (locked(jobs) { current != null || jobs.isNotEmpty() }) return
        for (key in held.keys.toList()) {
            held.remove(key)
            plugin.server.release(key.first, (key.second shr 32).toInt(), key.second.toInt())
        }
    }

    /** Asks for each of the plot's chunks to load, in the background; runs [then] once they're all asked for. */
    private fun load(plot: Plot, then: () -> Unit) {
        val box = plot.box
        val world = plot.field.world
        if (!plugin.server.hasWorld(world)) return then()
        for (cx in (box.minX shr 4)..(box.maxX shr 4)) {
            for (cz in (box.minZ shr 4)..(box.maxZ shr 4)) {
                if (held.put(world to co.xenastudios.xplots.model.Plots.chunkKey(cx, cz), true) == null) plugin.server.load(world, cx, cz)
            }
        }
        then()
    }

    private fun loaded(plot: Plot): Boolean {
        val box = plot.box
        val world = plot.field.world
        if (!plugin.server.hasWorld(world)) return true
        var all = true
        for (cx in (box.minX shr 4)..(box.maxX shr 4)) {
            for (cz in (box.minZ shr 4)..(box.maxZ shr 4)) {
                if (!plugin.server.isLoaded(world, cx, cz)) {
                    if (held.put(world to co.xenastudios.xplots.model.Plots.chunkKey(cx, cz), true) == null) plugin.server.load(world, cx, cz)
                    all = false
                }
            }
        }
        return all
    }

    /** Stops, finishing a plot being put back where this thread may, abandoning the rest. */
    public fun stop() {
        stopped = true
        running?.cancel()
        val job = current
        if (job != null) {
            if (plugin.platform.scheduler.owns(job.where) && loaded(job.plot)) {
                try {
                    job.step(Long.MAX_VALUE)
                } catch (e: Exception) {
                    plugin.log.warn("Couldn't finish putting ${job.plot.id} back as the server stopped: $e")
                }
            }
            job.abandon()
        }
        locked(jobs) {
            jobs.forEach { it.abandon() }
            jobs.clear()
            current = null
        }
        busy.clear()
        release()
    }

    private inner class Capture(plot: Plot) : Job(plot) {
        override fun step(deadline: Long): Boolean {
            val world = plot.field.world
            if (!plugin.server.hasWorld(world) || plugin.plots.plot(plot.id) !== plot) return true
            val box = plot.box
            val indices = IntArray(box.volume.toInt())
            val ids = HashMap<String, Int>()
            val palette = ArrayList<String>()
            var i = 0
            for (y in box.minY..box.maxY) {
                for (z in box.minZ..box.maxZ) {
                    for (x in box.minX..box.maxX) {
                        val state = plugin.server.stateAt(world, x, y, z)
                        indices[i++] = ids.getOrPut(state) { palette.size.also { palette += state } }
                    }
                }
            }
            plugin.store?.saveSnapshot(plot, Snapshot(box.width, box.height, box.depth, palette, indices).encode(plugin.deflate))
            return true
        }
    }

    private inner class Then(plot: Plot, private val then: () -> Unit) : Job(plot) {
        override fun step(deadline: Long): Boolean {
            then()
            return true
        }
    }

    private inner class Restore(plot: Plot, private val snapshot: Snapshot, private val then: () -> Unit) : Job(plot) {
        private val states: Array<Any?> = Array(snapshot.palette.size) { plugin.server.compile(snapshot.palette[it]) }
        private val tiles: BooleanArray = BooleanArray(states.size) { states[it]?.let(plugin.server::holds) == true }
        private var cleared = false
        private var next = 0

        override fun step(deadline: Long): Boolean {
            val world = plot.field.world
            if (!plugin.server.hasWorld(world)) return finish()
            val box = plot.box
            if (!cleared) {
                plugin.server.clearOut(world, box, front(plot))
                cleared = true
            }
            val indices = snapshot.indices
            val width = box.width
            val area = width * box.depth
            val sign = plot.sign
            while (next < indices.size) {
                val i = next++
                val target = states[indices[i]] ?: continue
                val x = box.minX + i % width
                val z = box.minZ + (i % area) / width
                val y = box.minY + i / area
                if (x == sign.x && y == sign.y && z == sign.z) continue // The plot's own sign stays.
                if (!plugin.server.matches(world, x, y, z, target)) {
                    plugin.server.set(world, x, y, z, target, false)
                } else if (tiles[indices[i]]) {
                    // The same chest or sign, but what's in it isn't: made anew, empty.
                    plugin.server.set(world, x, y, z, target, true)
                }
                if (i and 63 == 0 && plugin.platform.clock.elapsed().inWholeNanoseconds > deadline) return false
            }
            return finish()
        }

        private fun finish(): Boolean {
            done(plot, then)
            return true
        }

        override fun abandon() {
            busy.remove(plot)
        }
    }
}
