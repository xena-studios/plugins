package co.xenastudios.xvote.vote;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who is online on this server, by uuid and by name, for the receiver and the database
 * thread to ask without touching the server's own player list. Kept on join and quit.
 */
public final class Online {

    private final Map<UUID, String> names = new ConcurrentHashMap<>();
    private final Map<String, UUID> ids = new ConcurrentHashMap<>();

    public void add(UUID id, String name) {
        names.put(id, name);
        ids.put(name.toLowerCase(Locale.ROOT), id);
    }

    public void remove(UUID id, String name) {
        names.remove(id, name);
        ids.remove(name.toLowerCase(Locale.ROOT), id);
    }

    public void clear() {
        names.clear();
        ids.clear();
    }

    /** Whether the player is online here. */
    public boolean has(UUID id) {
        return names.containsKey(id);
    }

    /** The uuid of the player with this name, ignoring case, if they're online here. */
    public UUID byName(String name) {
        return ids.get(name.toLowerCase(Locale.ROOT));
    }

    /** Their name as they write it, if they're online here. */
    public String name(UUID id) {
        return names.get(id);
    }
}
