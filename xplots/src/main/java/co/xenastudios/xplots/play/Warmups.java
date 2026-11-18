package co.xenastudios.xplots.play;

import co.xenastudios.core.text.Placeholders;
import co.xenastudios.xplots.XPlots;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/**
 * Teleports after standing still for the warmup. Each waiting player is checked four
 * times a second by their own short task, which ends with the teleport; there's no move
 * listener. Main thread.
 */
public final class Warmups {

    public static final String BYPASS = "xplots.warmup.bypass";

    private final XPlots plugin;
    private final Map<UUID, BukkitTask> waiting = new HashMap<>();

    public Warmups(XPlots plugin) {
        this.plugin = plugin;
    }

    public void teleport(Player player, Location to) {
        cancel(player.getUniqueId());
        int seconds = plugin.config().teleportWarmup();
        if (seconds <= 0 || player.hasPermission(BYPASS)) {
            player.teleportAsync(to);
            return;
        }
        plugin.config().messages().teleporting().send(player, Placeholders.text("seconds", seconds));
        Location start = player.getLocation();
        UUID id = player.getUniqueId();
        int[] checks = {seconds * 4};
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline()) {
                cancel(id);
                return;
            }
            Location now = player.getLocation();
            if (now.getWorld() != start.getWorld() || now.distanceSquared(start) > 0.25) {
                cancel(id);
                plugin.config().messages().teleportCancelled().send(player);
                return;
            }
            if (--checks[0] <= 0) {
                cancel(id);
                player.teleportAsync(to);
            }
        }, 5, 5);
        waiting.put(id, task);
    }

    public void cancel(UUID player) {
        BukkitTask task = waiting.remove(player);
        if (task != null) {
            task.cancel();
        }
    }

    public void clear() {
        waiting.values().forEach(BukkitTask::cancel);
        waiting.clear();
    }
}
