package co.xenastudios.zchat.chat;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.entity.Player;

/**
 * What zChat remembers while the server runs. Kept for the plugin's life, so the mute,
 * hidden chat and reply targets survive a reload. Chat arrives on many threads at once
 * (on Folia especially), so everything here is safe to touch from any of them.
 */
public final class ChatState {

    /** Stands for the console in reply targets. */
    public static final UUID CONSOLE = new UUID(0, 0);

    private volatile boolean muted;
    private final Set<UUID> hidden = ConcurrentHashMap.newKeySet();
    private final Set<UUID> spies = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Recent> recent = new ConcurrentHashMap<>();
    private final Map<UUID, Partner> partners = new ConcurrentHashMap<>();
    // Online players by lowercase name, for mentions. The server's own lookup isn't
    // safe from the many threads chat runs on.
    private final Map<String, Player> names = new ConcurrentHashMap<>();

    /** A player's last message: when, and what it was for anti-repeat. */
    record Recent(long nanos, String key, long keyNanos) {}

    /** Who a player last messaged, or was messaged by. */
    public record Partner(UUID id, String name) {}

    public boolean muted() {
        return muted;
    }

    public void muted(boolean muted) {
        this.muted = muted;
    }

    public boolean hidesChat(UUID player) {
        return hidden.contains(player);
    }

    public void hideChat(UUID player, boolean hide) {
        if (hide) {
            hidden.add(player);
        } else {
            hidden.remove(player);
        }
    }

    public boolean spies(UUID player) {
        return spies.contains(player);
    }

    public void spy(UUID player, boolean spy) {
        if (spy) {
            spies.add(player);
        } else {
            spies.remove(player);
        }
    }

    public Set<UUID> spies() {
        return spies;
    }

    Recent recent(UUID player) {
        return recent.get(player);
    }

    void recent(UUID player, Recent value) {
        recent.put(player, value);
    }

    public Partner partner(UUID player) {
        return partners.get(player);
    }

    public void partner(UUID player, Partner partner) {
        partners.put(player, partner);
    }

    /** The online player with this name, ignoring case, or null. */
    public Player online(String name) {
        return names.get(name.toLowerCase(Locale.ROOT));
    }

    /** Notes a player who joined. */
    public void joined(Player player) {
        names.put(player.getName().toLowerCase(Locale.ROOT), player);
    }

    /** Forgets a player who left. Hidden chat and spying stay on the player, if remembered. */
    public void forget(Player leaving) {
        UUID player = leaving.getUniqueId();
        names.remove(leaving.getName().toLowerCase(Locale.ROOT), leaving);
        hidden.remove(player);
        spies.remove(player);
        recent.remove(player);
        partners.remove(player);
    }
}
