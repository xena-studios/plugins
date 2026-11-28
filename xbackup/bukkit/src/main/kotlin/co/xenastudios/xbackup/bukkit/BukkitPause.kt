package co.xenastudios.xbackup.bukkit

import co.xenastudios.core.api.Location
import co.xenastudios.core.bukkit.BukkitPlatform
import co.xenastudios.core.bukkit.ref
import co.xenastudios.core.common.scheduler.Task
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.xbackup.AutosaveGate
import co.xenastudios.xbackup.Pauses
import co.xenastudios.xbackup.backup.Backup
import org.bukkit.World
import java.lang.reflect.Method
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds

/**
 * The server's part of a backup on Bukkit, settled by xBackup 1.x's M0 spike and kept (decisions/2026-11-18-xbackup.md):
 * - **Pause:** `World#setAutoSave(false)` stops every write to a world's files, chunk unloads
 *   included (they're held, not lost).
 * - **Flush, Paper:** `World#save(true)` on the main thread, which writes every changed chunk
 *   before it returns.
 * - **Flush, Folia:** `World#save(true)` only saves the calling region's chunks and must run on
 *   that region's thread, so it runs once per region, in parallel, each through the core's
 *   scheduler at a chunk of that region.
 * - **Flush, Spigot and older Paper** (no `save(true)`): `World#save()`, which hands the chunks to
 *   the server's writer thread, then a wait until the region files stop changing.
 * - **Players** are saved first: autosave being off doesn't stop their files being written.
 * - **Resume** always, from `finally`, from a watchdog if a backup hangs, and on disable.
 */
internal class BukkitPause(
    private val platform: BukkitPlatform,
    private val maxPauseMillis: Long,
    private val worldFolders: () -> List<Path>,
    private val onWatchdog: () -> Unit,
) : Backup.Pause {
    private val server = platform.plugin.server
    private val folia = platform.threading == co.xenastudios.core.common.platform.Threading.REGIONS
    private val gate = AutosaveGate(Autosave)

    @Volatile
    private var watchdog: Task? = null
    private val longestRegionSave = AtomicLong()

    private object Autosave : AutosaveGate.Switch<World> {
        override fun isOn(world: World): Boolean = world.isAutoSave

        override fun set(world: World, on: Boolean) {
            world.isAutoSave = on
        }
    }

    override fun pause(): Long {
        gate.open()
        watchdog = platform.scheduler.run(Where.Async, delay = maxPauseMillis.milliseconds) {
            if (gate.isWanted) {
                platform.log.warn("A backup kept autosave off for longer than performance.max-pause, so autosave is back on and that backup was abandoned. Nothing was lost.")
                onWatchdog()
                resume()
            }
        }
        return if (folia) pauseFolia() else pauseMain()
    }

    // One task on the main thread: autosave off, players saved, every paused world flushed. If the
    // backup gave up waiting before it ran, it does nothing at all.
    private fun pauseMain(): Long {
        val flush = SAVE_FLUSH
        val millis = Pauses.onGlobal(platform.scheduler, 120) {
            val start = System.nanoTime()
            val worlds = gate.turnOff(server.worlds) ?: return@onGlobal 0L
            server.savePlayers()
            for (world in worlds) if (flush != null) flush.invoke(world, true) else world.save()
            (System.nanoTime() - start) / 1_000_000
        }
        // Without save(true) the chunks go to the server's writer thread: wait until it's done.
        if (flush == null && gate.isWanted) Pauses.awaitQuiet(worldFolders())
        return millis
    }

    private fun pauseFolia(): Long {
        val worlds = gate.turnOff(server.worlds) ?: throw java.util.concurrent.CancellationException("the pause ended before it began")
        Pauses.onGlobal(platform.scheduler, 60) { server.savePlayers() }
        longestRegionSave.set(0)
        val saves = ArrayList<CompletableFuture<Unit>>()
        for (world in worlds) saves += flushRegions(world)
        CompletableFuture.allOf(*saves.toTypedArray()).get(5, TimeUnit.MINUTES)
        return longestRegionSave.get()
    }

    /**
     * One `save(true)` per region: loaded chunks are grouped into 8x8-chunk cells (no bigger than
     * Folia's smallest region section), each cell gets a task on its region, and the first task
     * each region runs saves it and marks every cell that region owns, so the rest skip.
     */
    private fun flushRegions(world: World): List<CompletableFuture<Unit>> {
        val cells = HashMap<Long, IntArray>()
        for (chunk in world.loadedChunks) cells.getOrPut(((chunk.x shr 3).toLong() shl 32) or ((chunk.z shr 3).toLong() and 0xFFFFFFFFL)) { intArrayOf(chunk.x, chunk.z) }
        val done: MutableSet<Long> = ConcurrentHashMap.newKeySet()
        val ref = world.ref
        val saves = ArrayList<CompletableFuture<Unit>>()
        for ((key, at) in cells) {
            val saved = CompletableFuture<Unit>()
            saves += saved
            platform.scheduler.run(Where.At(Location(ref, at[0] * 16.0, 64.0, at[1] * 16.0)), retired = { saved.complete(Unit) }) {
                try {
                    if (key !in done) {
                        for ((other, place) in cells) {
                            if (platform.scheduler.owns(Where.At(Location(ref, place[0] * 16.0, 64.0, place[1] * 16.0)))) done += other
                        }
                        val start = System.nanoTime()
                        SAVE_FLUSH!!.invoke(world, true)
                        longestRegionSave.accumulateAndGet((System.nanoTime() - start) / 1_000_000) { a, b -> maxOf(a, b) }
                    }
                    saved.complete(Unit)
                } catch (e: Throwable) {
                    saved.completeExceptionally(e)
                }
            }
        }
        return saves
    }

    /**
     * Turns autosave back on for every world this pause turned off. Any thread, any number of
     * times: also from the watchdog, and on disable (where it acts at once, on the calling thread).
     */
    override fun resume() {
        watchdog?.cancel()
        gate.close()
    }

    companion object {
        /** Paper's `World#save(boolean flush)`, which writes every changed chunk before it returns; `null` where there's none. */
        @JvmField
        val SAVE_FLUSH: Method? = try {
            World::class.java.getMethod("save", Boolean::class.javaPrimitiveType)
        } catch (e: NoSuchMethodException) {
            null
        }
    }
}
