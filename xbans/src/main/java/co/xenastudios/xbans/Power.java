package co.xenastudios.xbans;

import co.xenastudios.xbans.api.PunishmentType;
import co.xenastudios.xbans.config.XBConfig;
import co.xenastudios.xbans.model.Length;
import java.util.Locale;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;

/**
 * What a staff member may do, worked out from their permissions on the thread their command
 * runs on: their weight, the longest ban and mute their groups allow (null for no limit), and
 * whether a template's own lengths may go past those. The console and plugins have no limits
 * at all.
 */
public record Power(int weight, Length banCap, Length muteCap, boolean bypassTemplates) {

    public static final String WEIGHT_PREFIX = "xbans.weight.";
    public static final String EXEMPT = "xbans.exempt";
    /** A template's own lengths aren't held to the sender's staff-limits group. */
    public static final String BYPASS_TEMPLATES = "xbans.limit.bypass-templates";

    /** The sender's power, or null for no limits (the console, a command block). */
    public static Power of(CommandSender sender, XBConfig.StaffLimits limits) {
        if (!(sender instanceof Player player)) {
            return null;
        }
        Length ban = null;
        Length mute = null;
        boolean grouped = false;
        for (XBConfig.Limit limit : limits.groups().values()) {
            if (!limit.permission().isBlank() && player.hasPermission(limit.permission())) {
                ban = grouped ? longer(ban, limit.ban()) : limit.ban();
                mute = grouped ? longer(mute, limit.mute()) : limit.mute();
                grouped = true;
            }
        }
        return new Power(weight(player), unlimited(ban), unlimited(mute), player.hasPermission(BYPASS_TEMPLATES));
    }

    /** The highest {@code xbans.weight.<n>} the player has been given; 0 without one. Operators get none by default. */
    public static int weight(Player player) {
        int weight = 0;
        for (PermissionAttachmentInfo info : player.getEffectivePermissions()) {
            String name = info.getPermission().toLowerCase(Locale.ROOT);
            if (info.getValue() && name.startsWith(WEIGHT_PREFIX)) {
                try {
                    weight = Math.max(weight, Integer.parseInt(name.substring(WEIGHT_PREFIX.length())));
                } catch (NumberFormatException ignored) {
                    // xbans.weight.* or a typo: not a weight.
                }
            }
        }
        return weight;
    }

    public Length cap(PunishmentType type) {
        return switch (type) {
            case BAN -> banCap;
            case MUTE -> muteCap;
            default -> null;
        };
    }

    private static Length longer(Length a, Length b) {
        return a.compareTo(b) >= 0 ? a : b;
    }

    private static Length unlimited(Length cap) {
        return cap == null || cap.permanent() ? null : cap;
    }
}
