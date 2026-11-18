package co.xenastudios.core.task;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Runs work on the right thread on Paper and Folia alike. On Paper every region is
 * the main thread, so "now if this thread owns it" means now.
 *
 * <p>Global work goes through Bukkit's scheduler on Paper, not the global region
 * scheduler: a Paper server that pauses because it's empty ({@code pause-when-empty-seconds},
 * 60 by default) still runs Bukkit's scheduler and console commands, but not the global
 * region scheduler, so work queued there waits until a player joins. Folia never pauses.
 */
public final class Tasks {

    private static final boolean FOLIA = folia();

    private final Plugin plugin;

    public Tasks(Plugin plugin) {
        this.plugin = plugin;
    }

    private static boolean folia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
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

    /**
     * Runs where messages to this sender belong: a player's own thread, or the global
     * thread for the console and command blocks. For replying from off the server's threads.
     */
    public void sender(CommandSender sender, Runnable work) {
        if (sender instanceof Player player) {
            entity(player, work);
        } else {
            global(work);
        }
    }

    /** Runs on the global thread (the main thread on Paper): right away if this is it. */
    public void global(Runnable work) {
        if (Bukkit.isGlobalTickThread()) {
            work.run();
        } else {
            nextTick(work);
        }
    }

    /**
     * Runs on the global thread's next tick, never right away: for work that must happen
     * after the current command or event, such as a reload that re-registers commands.
     */
    public void nextTick(Runnable work) {
        if (FOLIA) {
            Bukkit.getGlobalRegionScheduler().execute(plugin, work);
        } else {
            Bukkit.getScheduler().runTask(plugin, work);
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
