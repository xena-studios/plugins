package co.xenastudios.xplots.play;

import co.xenastudios.core.player.Warmups;
import co.xenastudios.core.text.Placeholders;
import co.xenastudios.xplots.XPlots;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/** {@code /plots home} and {@code /plots warp}: a teleport after standing still for the warmup. */
public final class Teleports {

    public static final String BYPASS = "xplots.warmup.bypass";

    private final XPlots plugin;
    private final Warmups warmups;

    public Teleports(XPlots plugin) {
        this.plugin = plugin;
        this.warmups = new Warmups(plugin);
    }

    public void teleport(Player player, Location to) {
        warmups.cancel(player.getUniqueId());
        int seconds = plugin.config().teleportWarmup();
        if (seconds <= 0 || player.hasPermission(BYPASS)) {
            player.teleportAsync(to);
            return;
        }
        plugin.config().messages().teleporting().send(player, Placeholders.text("seconds", seconds));
        warmups.start(player, Warmups.Warmup.<Player>of(seconds, p -> p.teleportAsync(to)).onCancel((p, reason) -> {
            if (reason == Warmups.Cancel.MOVED) {
                plugin.config().messages().teleportCancelled().send(p);
            }
        }));
    }

    public void cancel(UUID player) {
        warmups.cancel(player);
    }

    public void clear() {
        warmups.clear();
    }
}
