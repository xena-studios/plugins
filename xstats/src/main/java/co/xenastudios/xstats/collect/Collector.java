package co.xenastudios.xstats.collect;

import co.xenastudios.core.Module;
import co.xenastudios.core.task.Tasks;
import co.xenastudios.xstats.data.Engine;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntSupplier;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * The save: xStats' one repeating task. Every few minutes it asks each online player's
 * own thread for a reading, a few players a tick so a full server is never read in one
 * tick, then hands the readings to xStats' thread to write and rank.
 */
public final class Collector implements Module {

    private final Plugin plugin;
    private final Tasks tasks;
    private final Worker worker;
    private final Engine engine;
    private final Reader reader;
    private final Duration every;
    private final IntSupplier perTick;
    private final boolean excludeBanned;
    private final Runnable afterSave;
    private final AtomicBoolean saving;
    private ScheduledTask task;

    /**
     * @param saving shared by every collector, so one started by a reload never overlaps the last one's save
     * @param afterSave run on xStats' thread after each save: the website's pages, boards in the world, events
     */
    public Collector(Plugin plugin, Tasks tasks, Worker worker, Engine engine, Reader reader, Duration every, IntSupplier perTick,
                     boolean excludeBanned, AtomicBoolean saving, Runnable afterSave) {
        this.plugin = plugin;
        this.tasks = tasks;
        this.worker = worker;
        this.engine = engine;
        this.reader = reader;
        this.every = every;
        this.perTick = perTick;
        this.excludeBanned = excludeBanned;
        this.saving = saving;
        this.afterSave = afterSave;
    }

    @Override
    public void enable() {
        task = tasks.asyncRepeating(() -> begin(), every, every);
    }

    @Override
    public void disable() {
        task.cancel();
    }

    /** Starts a save now, unless one is running. From any thread. */
    public void begin() {
        begin(() -> { });
    }

    /**
     * Starts a save now, unless one is running, and runs {@code done} on xStats' thread after it.
     *
     * @return false if one was already running
     */
    public boolean begin(Runnable done) {
        if (!engine.ready() || !saving.compareAndSet(false, true)) {
            return false;
        }
        boolean scheduled = false;
        try {
            long started = System.currentTimeMillis();
            List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
            int each = Math.max(1, perTick.getAsInt());
            for (int i = 0; i < online.size(); i++) {
                Player player = online.get(i);
                // Retired (they left) is fine: their quit reading covers it.
                player.getScheduler().runDelayed(plugin, task -> engine.queue(reader.read(player, false, true)), null, 1 + i / each);
            }
            if (excludeBanned) {
                // Through the core, which keeps running while an empty Paper server is paused.
                tasks.global(() -> engine.bannedPlayers(banned()));
            }
            // Every reading is in by then, with room for a slow tick or two; a late one waits for the next save.
            long wait = (2L + (online.size() + each - 1) / each + 10) * 50;
            worker.schedule(() -> {
                try {
                    engine.save(started);
                    afterSave.run();
                } finally {
                    saving.set(false);
                    done.run();
                }
            }, wait);
            scheduled = true;
            return true;
        } finally {
            if (!scheduled) {
                // Something above failed: the next save can still start.
                saving.set(false);
            }
        }
    }

    /** The server's own ban list. On the global thread, where it's kept. */
    static Set<UUID> banned() {
        Set<UUID> banned = new HashSet<>();
        for (OfflinePlayer player : Bukkit.getBannedPlayers()) {
            banned.add(player.getUniqueId());
        }
        return banned;
    }
}
