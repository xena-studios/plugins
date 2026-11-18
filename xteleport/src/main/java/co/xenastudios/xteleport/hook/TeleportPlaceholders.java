package co.xenastudios.xteleport.hook;

import co.xenastudios.xteleport.XTeleport;
import co.xenastudios.xteleport.home.HomeLimits;
import java.time.Duration;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * {@code %xteleport_homes%}, {@code %xteleport_homes_limit%}, {@code %xteleport_warps%},
 * {@code %xteleport_requests%} (pending TPA requests) and {@code %xteleport_cooldown_<command>%}
 * (seconds left, 0 when ready). Only loaded when PlaceholderAPI is installed. Every answer
 * comes from memory.
 */
public final class TeleportPlaceholders extends PlaceholderExpansion {

    private static final String COOLDOWN = "cooldown_";

    private final XTeleport plugin;

    public TeleportPlaceholders(XTeleport plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String getIdentifier() {
        return "xteleport";
    }

    @Override
    public String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public String getAuthor() {
        return "Xena Studios";
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (params.equals("warps")) {
            return Integer.toString(plugin.places().warps().size());
        }
        if (player == null) {
            return "";
        }
        if (params.startsWith(COOLDOWN)) {
            Duration left = plugin.teleporter().cooldowns().remaining(player.getUniqueId(), params.substring(COOLDOWN.length()));
            return Long.toString((left.toMillis() + 999) / 1000);
        }
        return switch (params) {
            case "homes" -> plugin.homes().cached(player.getUniqueId()).map(loaded -> Integer.toString(loaded.homes().size())).orElse("0");
            case "homes_limit" -> {
                if (!(player instanceof Player online)) {
                    yield "";
                }
                int limit = HomeLimits.of(online, plugin.config().homes());
                yield limit == Integer.MAX_VALUE ? "∞" : Integer.toString(limit);
            }
            case "requests" -> Integer.toString(plugin.requests().pending(player.getUniqueId(),
                plugin.config().tpa().expireAfter() * 1000L).size());
            default -> null;
        };
    }
}
