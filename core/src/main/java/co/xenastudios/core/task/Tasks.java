package co.xenastudios.core.task;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

/**
 * Runs work on the right thread on Paper and Folia alike. On Paper every region is
 * the main thread, so "now if this thread owns it" means now.
 */
public final class Tasks {

    private final Plugin plugin;

    public Tasks(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Runs on the thread that owns the entity: right away if that's this thread,
     * otherwise on its next tick. Skipped if the entity is removed first, such as a
     * player who logs out.
     */
    public void entity(Entity entity, Runnable work) {
        if (Bukkit.isOwnedByCurrentRegion(entity)) {
            work.run();
        } else {
            entity.getScheduler().run(plugin, task -> work.run(), null);
        }
    }

    /** Runs on the global thread (the main thread on Paper): right away if this is it. */
    public void global(Runnable work) {
        if (Bukkit.isGlobalTickThread()) {
            work.run();
        } else {
            Bukkit.getGlobalRegionScheduler().execute(plugin, work);
        }
    }

    /** Runs off the server threads, for network and disk work. */
    public void async(Runnable work) {
        Bukkit.getAsyncScheduler().runNow(plugin, task -> work.run());
    }

    /** Repeats off the server threads. */
    public ScheduledTask asyncRepeating(Runnable work, Duration delay, Duration period) {
        return Bukkit.getAsyncScheduler().runAtFixedRate(
            plugin, task -> work.run(), delay.toMillis(), period.toMillis(), TimeUnit.MILLISECONDS);
    }
}
