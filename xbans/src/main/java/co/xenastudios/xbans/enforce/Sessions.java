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
    // Players being kicked for a ban, whose join and quit lines are left out.
    private final Set<UUID> quiet = ConcurrentHashMap.newKeySet();

    /** The IP key an online player joined from, or null. */
    public String ip(UUID player) {
        return ips.get(player);
    }

    void joined(UUID player, String ip) {
        if (ip != null) {
            ips.put(player, ip);
        }
    }

    void left(UUID player) {
        ips.remove(player);
    }

    /** Leaves out the "left the game" line when this player is kicked next. */
    public void quietQuit(UUID player) {
        quiet.add(player);
    }

    boolean takeQuiet(UUID player) {
        return quiet.remove(player);
    }
}
