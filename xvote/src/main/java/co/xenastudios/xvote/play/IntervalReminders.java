package co.xenastudios.xvote.play;

import co.xenastudios.core.Module;
import co.xenastudios.xvote.XVote;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Reminds everyone online who can vote, every so many minutes. xVote's only repeating
 * task, and only while {@code reminders.every-minutes} is above 0.
 */
public final class IntervalReminders implements Module {

    private final XVote plugin;
    private final int minutes;
    private ScheduledTask task;

    public IntervalReminders(XVote plugin, int minutes) {
        this.plugin = plugin;
        this.minutes = minutes;
    }

    @Override
    public void enable() {
        long ticks = minutes * 60L * 20L;
        task = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, scheduled -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                plugin.tasks().entity(player, () -> plugin.remind(player));
            }
        }, ticks, ticks);
    }

    @Override
    public void disable() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }
}
