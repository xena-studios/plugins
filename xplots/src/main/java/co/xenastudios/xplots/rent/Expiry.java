package co.xenastudios.xplots.rent;

import co.xenastudios.core.text.Placeholders;
import co.xenastudios.xplots.XPlots;
import co.xenastudios.xplots.model.Lease;
import co.xenastudios.xplots.model.Plot;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/**
 * One timer, set for the next thing due: a lease running out, or its owner's warning a
 * day or an hour before. Nothing polls. When it fires it handles everything due, then
 * sets itself for the next. Leases that ran out while the server was off are handled
 * when it starts.
 *
 * <p>A warning due while its owner is offline is sent when they join instead.
 * Main thread.
 */
public final class Expiry {

    // The timer never waits longer than this, so lag or a changed clock can't put it far off.
    private static final long LONGEST_WAIT = 20 * 60_000L;

    private final XPlots plugin;
    private BukkitTask task;
    private long due = Long.MAX_VALUE;
    // While firing, changes don't each reset the timer; it's set once at the end.
    private boolean firing;

    public Expiry(XPlots plugin) {
        this.plugin = plugin;
    }

    /** Sets the timer for whatever's due next. Cheap: one pass over the rented plots. */
    public void reschedule() {
        if (firing) {
            return;
        }
        long now = System.currentTimeMillis();
        long next = Long.MAX_VALUE;
        for (Plot plot : plugin.plots().rented()) {
            Lease lease = plot.lease();
            next = Math.min(next, lease.expires());
            long day = lease.expires() - 86_400_000L;
            long hour = lease.expires() - 3_600_000L;
            if (lease.warned() < Lease.WARNED_DAY && day > now) {
                next = Math.min(next, day);
            }
            if (lease.warned() < Lease.WARNED_HOUR && hour > now) {
                next = Math.min(next, hour);
            }
        }
        if (next == Long.MAX_VALUE) {
            stop();
            return;
        }
        long at = Math.min(next, now + LONGEST_WAIT);
        if (task != null && at == due) {
            return;
        }
        stop();
        due = at;
        long ticks = Math.max(1, (at - now + 49) / 50);
        task = Bukkit.getScheduler().runTaskLater(plugin, this::fire, ticks);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        due = Long.MAX_VALUE;
    }

    /** Handles everything due now, then sets the timer for the next. */
    public void fire() {
        task = null;
        due = Long.MAX_VALUE;
        long now = System.currentTimeMillis();
        firing = true;
        try {
            sweep(now);
        } finally {
            firing = false;
        }
        reschedule();
    }

    private void sweep(long now) {
        for (Plot plot : plugin.plots().rented()) {
            Lease lease = plot.lease();
            if (lease == null) {
                continue;
            }
            if (lease.expires() <= now) {
                plugin.rentals().expire(plot);
            } else {
                Player owner = Bukkit.getPlayer(lease.owner());
                if (owner != null) {
                    warn(owner, plot, now);
                }
            }
        }
    }

    /** A player joined: warnings that came due while they were away. */
    public void joined(Player player) {
        long now = System.currentTimeMillis();
        for (Plot plot : List.copyOf(plugin.plots().owned(player.getUniqueId()))) {
            Lease lease = plot.lease();
            if (!lease.ownerName().equals(player.getName())) {
                lease.ownerName(player.getName());
                plugin.rentals().saved(plot);
            }
            warn(player, plot, now);
        }
    }

    private void warn(Player owner, Plot plot, long now) {
        Lease lease = plot.lease();
        int level = Lease.warningFor(lease.remaining(now));
        if (level > lease.warned()) {
            lease.warned(level);
            plugin.store().saveLease(plot);
            plugin.config().messages().expiresSoon().send(owner,
                Placeholders.text("plot", plot.id()), Placeholders.text("time", Info.time(lease.remaining(now))));
        }
    }
}
