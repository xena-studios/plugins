package co.xenastudios.xbans.enforce;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What xBans knows about the players online: the IP key each joined from, worked out once
 * at join so the chat check never hashes anything. Safe from any thread.
 */
public final class Sessions {

    private final Map<UUID, String> ips = new ConcurrentHashMap<>();
    // How many online players are on each IP key, to notice a proxy that isn't forwarding addresses.
    private final Map<String, Integer> sharing = new ConcurrentHashMap<>();
    // Players being kicked for a ban, whose join and quit lines are left out.
    private final Set<UUID> quiet = ConcurrentHashMap.newKeySet();

    /** The IP key an online player joined from, or null. */
    public String ip(UUID player) {
        return ips.get(player);
    }

    /** Records the IP key a player joined from; returns how many online players are on it now. */
    int joined(UUID player, String ip) {
        if (ip == null) {
            return 0;
        }
        String old = ips.put(player, ip);
        if (ip.equals(old)) {
            return sharing.getOrDefault(ip, 1);
        }
        if (old != null) {
            sharing.computeIfPresent(old, (key, count) -> count <= 1 ? null : count - 1);
        }
        return sharing.merge(ip, 1, Integer::sum);
    }

    void left(UUID player) {
        String ip = ips.remove(player);
        if (ip != null) {
            sharing.computeIfPresent(ip, (key, count) -> count <= 1 ? null : count - 1);
        }
    }

    /** Leaves out the "left the game" line when this player is kicked next. */
    public void quietQuit(UUID player) {
        quiet.add(player);
    }

    boolean takeQuiet(UUID player) {
        return quiet.remove(player);
    }
}
