package co.xenastudios.xplots.hook;

import co.xenastudios.xplots.XPlots;
import co.xenastudios.xplots.model.Field;
import co.xenastudios.xplots.model.Plot;
import co.xenastudios.xplots.rent.Info;
import java.util.List;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * {@code %xplots_owned%}, {@code %xplots_time_left%} (the player's soonest-ending plot),
 * {@code %xplots_plot%} and {@code %xplots_plot_owner%} (the plot they stand in). Only
 * loaded when PlaceholderAPI is installed.
 *
 * <p>Chat plugins ask from the chat thread, while plots change on the main thread. A
 * read that overlaps a change can give a stale answer, never a wrong plot's; one that
 * trips over a list mid-change answers blank rather than throw.
 */
public final class PlotPlaceholders extends PlaceholderExpansion {

    private final XPlots plugin;

    public PlotPlaceholders(XPlots plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "xplots";
    }

    @Override
    public String getAuthor() {
        return "Xena Studios";
    }

    @Override
    public String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (player == null) {
            return "";
        }
        try {
            return switch (params) {
                case "owned" -> Integer.toString(plugin.plots().owned(player.getUniqueId()).size());
                case "time_left" -> {
                    long soonest = Long.MAX_VALUE;
                    for (Plot plot : List.copyOf(plugin.plots().owned(player.getUniqueId()))) {
                        soonest = Math.min(soonest, plot.lease().expires());
                    }
                    yield soonest == Long.MAX_VALUE ? "" : Info.time(Math.max(0, soonest - System.currentTimeMillis()));
                }
                case "plot" -> {
                    Plot here = here(player);
                    yield here == null ? "" : here.id();
                }
                case "plot_owner" -> {
                    Plot here = here(player);
                    yield here == null || here.lease() == null ? "" : here.lease().ownerName();
                }
                default -> null;
            };
        } catch (RuntimeException e) {
            return "";
        }
    }

    private Plot here(OfflinePlayer offline) {
        Player player = offline.getPlayer();
        if (player == null) {
            return null;
        }
        var location = player.getLocation();
        Plot plot = plugin.plots().at(location.getWorld(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
        return plot == Field.PATH ? null : plot;
    }
}
