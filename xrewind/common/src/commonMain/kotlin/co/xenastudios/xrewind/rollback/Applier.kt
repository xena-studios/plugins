package co.xenastudios.xrewind.rollback

import co.xenastudios.core.api.Location
import co.xenastudios.core.api.WorldRef
import co.xenastudios.core.api.onSettled
import co.xenastudios.core.common.locked
import co.xenastudios.core.common.platform.Log
import co.xenastudios.core.common.platform.Platform
import co.xenastudios.core.common.platform.Threading
import co.xenastudios.core.common.scheduler.TICK
import co.xenastudios.core.common.scheduler.Task
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.xrewind.RewindServer
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Sets a plan's blocks without freezing the server: chunk by chunk, each loaded first without
 * waiting, at most [budgetMillis] of each tick (1.x's `Applier`). Blocks are set without physics,
 * so nothing cascades, and what each was before is kept, for undo.
 *
 * On a server with one main thread, one task a tick works through the chunks in order, the next
 * few loading ahead, within one budget. On Folia each chunk is worked on its own region's thread,
 * a few at a time, each region with its own budget. As each chunk is done its rows are handed to
 * [chunkDone], to be marked at once: a restart partway leaves the chunks done marked, and the rest
 * to carry on with (decisions/2026-11-27-xrewind-2.md).
 */
public class Applier(
    private val platform: Platform,
    private val server: RewindServer,
    private val log: Log,
    plan: RewindPlan,
    budgetMillis: Int,
    private val chunkDone: (blocks: LongArray, containers: LongArray) -> Unit,
    private val finished: (Result) -> Unit,
) {
    /** How it went: [previous] puts back what it changed. */
    public class Result(public val applied: Int, public val missingWorld: Int, public val previous: List<Change>, public val took: Duration, public val aborted: Boolean)

    private class Group(val world: String, val cx: Int, val cz: Int, val changes: MutableList<Change> = ArrayList()) {
        val at: Location get() = Location(WorldRef.of(world), cx * 16.0 + 8, 64.0, cz * 16.0 + 8)
    }

    private val budget = budgetMillis.milliseconds
    private val groups = ArrayList<Group>()
    private val lock = Any()
    private val previous = ArrayList<Change>()
    private val started = platform.clock.elapsed()
    private var missingWorld = 0
    private var finishedOnce = false

    @Volatile
    private var aborted = false

    @Volatile
    public var applied: Int = 0
        private set

    public val total: Int = plan.changes.size

    init {
        val byChunk = LinkedHashMap<String, Group>()
        for (change in plan.changes) {
            if (!server.hasWorld(change.spot.world)) {
                missingWorld++
                continue
            }
            val key = "${change.spot.world}|${change.spot.chunkX}|${change.spot.chunkZ}"
            byChunk.getOrPut(key) { Group(change.spot.world, change.spot.chunkX, change.spot.chunkZ) }.changes += change
        }
        groups += byChunk.values
    }

    public fun start() {
        when {
            groups.isEmpty() -> finish()
            platform.threading == Threading.REGIONS -> Regions().start()
            else -> MainThread().start()
        }
    }

    /** Stops between blocks, as the plugin stops: what's done is marked, the rest left for a restart. */
    public fun abort() {
        aborted = true
    }

    private fun failed(e: Throwable) {
        if (!aborted) {
            aborted = true
            log.error("A rollback stopped partway after $applied blocks", e)
        }
    }

    private fun finish() {
        val result = locked(lock) {
            if (finishedOnce) return
            finishedOnce = true
            Result(applied, missingWorld, previous.toList(), platform.clock.elapsed() - started, aborted)
        }
        finished(result)
    }

    // Sets blocks from [from] on until [deadline], on the chunk's thread. Returns where it stopped.
    private fun apply(group: Group, from: Int, deadline: Duration, blocks: MutableList<Long>, containers: MutableList<Long>): Int {
        var index = from
        val changes = group.changes
        val undo = ArrayList<Change>()
        var made = 0
        try {
            while (index < changes.size && !aborted) {
                val change = changes[index++]
                if (change.isItems) {
                    val item = change.item
                    if (item != null && change.delta != 0) {
                        val moved = server.moveItems(change.spot, item, change.delta)
                        if (moved != 0) undo += Change.items(change.spot, item, change.itemType, -moved, change.ids)
                    }
                    change.ids.forEach { containers += it }
                } else {
                    val was = server.set(change.spot, change.target!!, change.contents)
                    if (was != null) {
                        undo += Change.block(change.spot, was.state, was.contents, change.ids)
                        change.ids.forEach { blocks += it }
                    }
                }
                made++
                if (index and 63 == 0 && platform.clock.elapsed() > deadline) break
            }
        } catch (e: Exception) {
            failed(e)
        } finally {
            locked(lock) {
                previous += undo
                applied += made
            }
        }
        return index
    }

    private fun chunkFinished(blocks: List<Long>, containers: List<Long>) {
        if (blocks.isNotEmpty() || containers.isNotEmpty()) chunkDone(blocks.toLongArray(), containers.toLongArray())
    }

    /** One thread for everything: one task a tick works through the chunks, a few loading ahead. */
    private inner class MainThread : (Task) -> Unit {
        private val next = groups.iterator()
        private val loading = ArrayDeque<Pair<Group, IntArray>>()
        private var index = 0
        private val blocks = ArrayList<Long>()
        private val containers = ArrayList<Long>()

        fun start() {
            fill()
            platform.scheduler.run(Where.Global, action = this)
        }

        // Each chunk's state: 0 loading, 1 loaded, 2 couldn't be.
        private fun fill() {
            while (loading.size < AHEAD && next.hasNext()) {
                val group = next.next()
                val state = IntArray(1)
                loading.addLast(group to state)
                server.load(group.world, group.cx, group.cz).onSettled { ok, _ -> state[0] = if (ok == true) 1 else 2 }
            }
        }

        override fun invoke(task: Task) {
            try {
                step()
            } catch (e: Exception) {
                failed(e)
                for ((group, state) in loading) if (state[0] == 1) server.release(group.world, group.cx, group.cz)
                loading.clear()
                finish()
            }
        }

        private fun step() {
            val deadline = platform.clock.elapsed() + budget
            while (loading.isNotEmpty() && platform.clock.elapsed() <= deadline && !aborted) {
                val (group, state) = loading.first()
                if (state[0] == 0) break // Still loading: next tick.
                if (state[0] == 2) {
                    // Couldn't be loaded: its blocks are left as they are.
                    loading.removeFirst()
                    index = 0
                    fill()
                    continue
                }
                index = apply(group, index, deadline, blocks, containers)
                if (index >= group.changes.size || aborted) {
                    server.release(group.world, group.cx, group.cz)
                    chunkFinished(blocks, containers)
                    blocks.clear()
                    containers.clear()
                    loading.removeFirst()
                    index = 0
                    fill()
                }
            }
            if (aborted) {
                for ((group, s) in loading) if (s[0] == 1) server.release(group.world, group.cx, group.cz)
                loading.clear()
            }
            if (loading.isEmpty()) finish() else platform.scheduler.run(Where.Global, TICK, action = this)
        }
    }

    /** Folia: each chunk on its own region's thread, a few at a time. */
    private inner class Regions {
        private val next = groups.iterator()
        private var running = 0

        fun start() {
            repeat(AHEAD) { launch() }
        }

        private fun launch() {
            val group = locked(lock) {
                if (aborted || !next.hasNext()) {
                    if (running == 0) null.also { finishLater() } else null
                } else {
                    running++
                    next.next()
                }
            } ?: return
            server.load(group.world, group.cx, group.cz).onSettled { ok, _ ->
                if (ok != true) return@onSettled done()
                platform.scheduler.run(Where.At(group.at), retired = { done() }) { work(group, 0, ArrayList(), ArrayList()) }
            }
        }

        private var finishing = false

        private fun finishLater() {
            if (finishing) return
            finishing = true
            platform.scheduler.run(Where.Async) { finish() }
        }

        private fun work(group: Group, from: Int, blocks: ArrayList<Long>, containers: ArrayList<Long>) {
            val index = apply(group, from, platform.clock.elapsed() + budget, blocks, containers)
            if (index < group.changes.size && !aborted) {
                platform.scheduler.run(Where.At(group.at), TICK, retired = { done() }) { work(group, index, blocks, containers) }
                return
            }
            server.release(group.world, group.cx, group.cz)
            chunkFinished(blocks, containers)
            done()
        }

        private fun done() {
            locked(lock) { running-- }
            launch()
        }
    }

    public companion object {
        /** Chunks loading ahead (one main thread), or worked at once (Folia). */
        public const val AHEAD: Int = 8
    }
}
