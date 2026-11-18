package co.xenastudios.xbackup.server;

import co.xenastudios.xbackup.backup.Backup;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

/**
 * The server's part of a backup, settled by the M0 spike on Paper and Folia, 1.21.11 and 26.x:
 *
 * <ul>
 * <li><b>Pause:</b> {@code World#setAutoSave(false)} stops every write to a world's files,
 *     chunk unloads included (they're held, not lost).
 * <li><b>Flush, Paper:</b> {@code World#save(true)} on the main thread, which writes every
 *     changed chunk before it returns ({@code save()} and {@code save-all} don't).
 * <li><b>Flush, Folia:</b> {@code World#save(true)} only saves the calling region's chunks,
 *     and must run on that region's thread, so it runs once per region, in parallel. On the
 *     global thread it throws (1.21.11) or silently saves nothing (26.x).
 * <li><b>Players</b> are saved first, on the global thread: autosave being off doesn't stop
 *     their files being written.
 * <li><b>Resume</b> always, from {@code finally}, from a watchdog if a backup hangs, and on
 *     disable, so a world is never left unsaved.
 * </ul>
 *
 * <p>Worlds whose owner had already turned autosave off (an arena that resets, say) are left
 * alone: not flushed, since their owner doesn't want them saved, and not turned back on.
 */
public final class WorldPause implements Backup.Pause {

    private final Plugin plugin;
    private final co.xenastudios.core.task.Tasks tasks;
    private final boolean folia;
    private final Logger logger;
    private final Duration maxPause;
    private final Runnable onWatchdog;
    private final AutosaveGate<World> gate = new AutosaveGate<>(new AutosaveGate.Switch<>() {
        @Override
        public boolean isOn(World world) {
            return world.isAutoSave();
        }

        @Override
        public void set(World world, boolean on) {
            world.setAutoSave(on);
        }
    });
    private volatile ScheduledTask watchdog;
    private final AtomicLong longestRegionSave = new AtomicLong();

    /** @param onWatchdog called if the watchdog had to turn autosave back on */
    public WorldPause(Plugin plugin, boolean folia, Logger logger, Duration maxPause, Runnable onWatchdog) {
        this.plugin = plugin;
        this.tasks = new co.xenastudios.core.task.Tasks(plugin);
        this.folia = folia;
        this.logger = logger;
        this.maxPause = maxPause;
        this.onWatchdog = onWatchdog;
    }

    @Override
    public long pause() throws Exception {
        gate.open();
        watchdog = Bukkit.getAsyncScheduler().runDelayed(plugin, task -> {
            if (gate.wanted()) {
                logger.warning("A backup kept autosave off for longer than performance.max-pause ("
                    + co.xenastudios.core.text.Durations.format(maxPause) + "), so autosave is back on and that backup"
                    + " was abandoned. Nothing was lost.");
                onWatchdog.run();
                resume();
            }
        }, maxPause.toMillis(), TimeUnit.MILLISECONDS);
        return folia ? pauseFolia() : pausePaper();
    }

    // One task on the main thread: autosave off, players saved, every paused world flushed.
    // If the backup gave up waiting before it ran, it does nothing at all.
    private long pausePaper() throws Exception {
        return MainThread.call(tasks, 120, () -> {
            long start = System.nanoTime();
            List<World> worlds = gate.turnOff(Bukkit.getWorlds());
            if (worlds == null) {
                return 0L;
            }
            Bukkit.savePlayers();
            for (World world : worlds) {
                world.save(true);
            }
            return (System.nanoTime() - start) / 1_000_000;
        });
    }

    private long pauseFolia() throws Exception {
        List<World> worlds = gate.turnOff(Bukkit.getWorlds());
        if (worlds == null) {
            throw new java.util.concurrent.CancellationException("the pause ended before it began");
        }
        MainThread.call(tasks, 60, () -> {
            Bukkit.savePlayers();
            return null;
        });
        longestRegionSave.set(0);
        List<CompletableFuture<Void>> saves = new ArrayList<>();
        for (World world : worlds) {
            saves.addAll(flushRegions(world));
        }
        CompletableFuture.allOf(saves.toArray(CompletableFuture[]::new)).get(5, TimeUnit.MINUTES);
        return longestRegionSave.get();
    }

    /**
     * One {@code save(true)} per region: loaded chunks are grouped into 8x8-chunk cells (no
     * bigger than Folia's smallest region section), each cell gets a task on its region, and
     * the first task each region runs saves it and marks every cell that region owns, so the
     * rest skip.
     */
    private List<CompletableFuture<Void>> flushRegions(World world) {
        Map<Long, int[]> cells = new HashMap<>();
        for (Chunk chunk : world.getLoadedChunks()) {
            cells.putIfAbsent(((long) (chunk.getX() >> 3) << 32) | ((chunk.getZ() >> 3) & 0xFFFFFFFFL),
                new int[] {chunk.getX(), chunk.getZ()});
        }
        Set<Long> done = ConcurrentHashMap.newKeySet();
        List<CompletableFuture<Void>> saves = new ArrayList<>();
        for (Map.Entry<Long, int[]> cell : cells.entrySet()) {
            CompletableFuture<Void> saved = new CompletableFuture<>();
            saves.add(saved);
            int[] at = cell.getValue();
            Bukkit.getRegionScheduler().execute(plugin, world, at[0], at[1], () -> {
                try {
                    if (done.contains(cell.getKey())) {
                        return;
                    }
                    for (Map.Entry<Long, int[]> other : cells.entrySet()) {
                        if (Bukkit.isOwnedByCurrentRegion(world, other.getValue()[0], other.getValue()[1])) {
                            done.add(other.getKey());
                        }
                    }
                    long start = System.nanoTime();
                    world.save(true);
                    longestRegionSave.accumulateAndGet((System.nanoTime() - start) / 1_000_000, Math::max);
                } catch (Throwable e) {
                    saved.completeExceptionally(e);
                    return;
                } finally {
                    saved.complete(null);
                }
            });
        }
        return saves;
    }

    /**
     * Turns autosave back on for every world this pause turned off. Any thread, any number of
     * times: also from the watchdog, and on disable (where it acts right away, on the calling
     * thread, since no task would run any more).
     */
    @Override
    public void resume() {
        ScheduledTask task = watchdog;
        if (task != null) {
            task.cancel();
        }
        gate.close();
    }

    /** On disable: turns autosave back on right now, on this thread, if a backup left it off. */
    public void resumeNow() {
        resume();
    }

    public boolean isPaused() {
        return gate.wanted();
    }
}
