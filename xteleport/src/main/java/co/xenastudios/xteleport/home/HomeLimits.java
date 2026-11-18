package co.xenastudios.xteleport.home;

import co.xenastudios.xteleport.XTeleport;
import co.xenastudios.xteleport.config.Settings.HomeLimit;
import co.xenastudios.xteleport.config.XTConfig;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;

/**
 * How many homes a player may have: the highest of the default, the config's limits and
 * {@code xteleport.homes.<n>}.
 *
 * <p>Working it out reads every permission the player has, so what's asked often (a
 * placeholder on a scoreboard) is answered from what was last worked out, for up to
 * {@link #FRESH_MILLIS}. Paper has no event for a player's permissions changing, so that
 * short time is what lets a rank change show. Setting a home and {@code /homes} always
 * work it out again. Forgotten as players leave, and all of it on reload.
 */
public final class HomeLimits {

    /** How long a limit worked out is used for. */
    static final long FRESH_MILLIS = 30_000;

    private static final String NUMBERED = "xteleport.homes.";

    private record Known(int limit, long at) {}

    private final Map<UUID, Known> known = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public HomeLimits() {
        this(System::currentTimeMillis);
    }

    HomeLimits(LongSupplier clock) {
        this.clock = clock;
    }

    /** Works out the player's limit now, and keeps it for {@link #cached}. On the player's thread. */
    public int of(Player player, XTConfig.Homes settings) {
        int limit = workOut(player, settings);
        known.put(player.getUniqueId(), new Known(limit, clock.getAsLong()));
        return limit;
    }

    /** The player's limit as last worked out, if that was recent; otherwise works it out. */
    public int cached(Player player, XTConfig.Homes settings) {
        Known last = known.get(player.getUniqueId());
        if (last != null && clock.getAsLong() - last.at() < FRESH_MILLIS) {
            return last.limit();
        }
        return of(player, settings);
    }

    /** Forgets a player who left. */
    public void forget(UUID player) {
        known.remove(player);
    }

    /** Forgets everyone, as on reload, when the limits may have changed. */
    public void clear() {
        known.clear();
    }

    static int workOut(Player player, XTConfig.Homes settings) {
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
