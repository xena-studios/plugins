package co.xenastudios.xteleport.home;

import co.xenastudios.xteleport.XTeleport;
import co.xenastudios.xteleport.config.Settings.HomeLimit;
import co.xenastudios.xteleport.config.XTConfig;
import java.util.Map;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;

/** How many homes a player may have: the highest of the default, the config's limits and {@code xteleport.homes.<n>}. */
public final class HomeLimits {

    private static final String NUMBERED = "xteleport.homes.";

    private HomeLimits() {}

    public static int of(Player player, XTConfig.Homes settings) {
        if (player.hasPermission(XTeleport.UNLIMITED_HOMES)) {
            return Integer.MAX_VALUE;
        }
        int best = settings.defaultLimit();
        for (Map.Entry<String, HomeLimit> limit : settings.limits().entrySet()) {
            String permission = limit.getValue().permission().isBlank() ? "group." + limit.getKey() : limit.getValue().permission();
            if (limit.getValue().homes() > best && player.hasPermission(permission)) {
                best = limit.getValue().homes();
            }
        }
        for (PermissionAttachmentInfo info : player.getEffectivePermissions()) {
            String permission = info.getPermission();
            if (info.getValue() && permission.startsWith(NUMBERED)) {
                best = Math.max(best, number(permission.substring(NUMBERED.length())));
            }
        }
        return best;
    }

    // xteleport.homes.5 -> 5; anything else (others, unlimited) -> 0.
    static int number(String suffix) {
        if (suffix.isEmpty() || suffix.length() > 6) {
            return 0;
        }
        for (int i = 0; i < suffix.length(); i++) {
            if (!Character.isDigit(suffix.charAt(i))) {
                return 0;
            }
        }
        return Integer.parseInt(suffix);
    }
}
