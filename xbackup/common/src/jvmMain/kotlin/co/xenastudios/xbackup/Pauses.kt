package co.xenastudios.xbackup

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.newPending
import co.xenastudios.core.api.reject
import co.xenastudios.core.api.resolve
import co.xenastudios.core.common.scheduler.Scheduler
import co.xenastudios.core.common.scheduler.Where
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** The world pause's pieces every JVM platform shares. */
public object Pauses {
    /**
     * Runs [work] on the global thread (the main thread on Paper and Spigot, through the
     * scheduler that keeps running while an empty Paper server is paused) and waits up to
     * [seconds] for its answer. Off the game threads only.
     */
    @JvmStatic
    public fun <T> onGlobal(scheduler: Scheduler, seconds: Long, work: () -> T): T {
        val done: Pending<T> = newPending()
        scheduler.run(Where.Global, retired = { done.reject(IllegalStateException("the server is stopping")) }) {
            try {
                done.resolve(work())
            } catch (e: Throwable) {
                done.reject(e)
            }
        }
        return try {
            done.get(seconds, TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        }
    }

    /**
     * Waits until the region files under [worlds] stop changing: what a server whose save only
     * queues its chunks for a writer thread of its own (Spigot, older Paper, Fabric's 1.20.1)
     * needs before the strict read, so it reads what the save wrote rather than catch it half
     * way. Quiet means two looks [interval] apart see the same sizes and times; gives up after
     * [maxMillis], which only costs a re-read (a chunk that moves while it's read is read again).
     */
    @JvmStatic
    @JvmOverloads
    public fun awaitQuiet(worlds: List<Path>, maxMillis: Long = 20_000, interval: Long = 250) {
        val until = System.currentTimeMillis() + maxMillis
        var last = look(worlds)
        while (System.currentTimeMillis() < until) {
            Thread.sleep(interval)
            val now = look(worlds)
            if (now == last) return
            last = now
        }
    }

    // Every region file's path, size and time.
    private fun look(worlds: List<Path>): Map<String, Long> {
        val seen = HashMap<String, Long>()
        for (world in worlds) {
            if (!Files.isDirectory(world)) continue
            try {
                Files.walk(world, 4).use { walk ->
                    for (file in walk) {
                        val name = file.fileName?.toString() ?: continue
                        if (!name.endsWith(".mca") && !name.endsWith(".dat")) continue
                        try {
                            seen[file.toString()] = Files.size(file) * 31 + Files.getLastModifiedTime(file).toMillis()
                        } catch (e: java.io.IOException) {
                            // Gone between listing and reading.
                        }
                    }
                }
            } catch (e: java.io.IOException) {
                // A folder that went away: nothing to wait for.
            } catch (e: java.io.UncheckedIOException) {
                // Likewise, from inside the walk.
            }
        }
        return seen
    }
}
