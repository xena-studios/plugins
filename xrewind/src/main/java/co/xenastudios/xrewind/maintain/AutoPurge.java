package co.xenastudios.xrewind.maintain;

import co.xenastudios.core.Module;
import co.xenastudios.core.db.Database;
import co.xenastudios.xrewind.XRewind;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.time.Duration;
import java.util.logging.Level;

/**
 * Deletes history older than {@code purge.keep-days}, five minutes after startup and then
 * once a day. Runs only while keep-days is above 0.
 */
public final class AutoPurge implements Module {

    private final XRewind plugin;
    private ScheduledTask task;

    public AutoPurge(XRewind plugin) {
        this.plugin = plugin;
    }

    @Override
    public void enable() {
        task = plugin.tasks().asyncRepeating(this::run, Duration.ofMinutes(5), Duration.ofDays(1));
    }

    @Override
    public void disable() {
        if (task != null) {
            task.cancel();
        }
    }

    private void run() {
        int days = plugin.config().purge().keepDays();
        Database database = plugin.storage().database();
        if (days <= 0 || database == null) {
            return;
        }
        try {
            long deleted = Purge.run(database, plugin.writer().gate(), Purge.daysAgo(days));
            if (deleted > 0) {
                plugin.getLogger().info("Purged " + String.format("%,d", deleted) + " changes older than " + days + " days.");
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "The daily purge failed; it tries again tomorrow", e);
        }
    }
}
