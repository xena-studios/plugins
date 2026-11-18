package co.xenastudios.xbans.model;

import co.xenastudios.xbans.api.PunishmentType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every ban and mute in force, in memory, so the join check and the chat check never ask
 * the database. Loaded once when the database opens, then kept up to date by every
 * punishment made here or (in network mode) on another server.
 *
 * <p>Safe from any thread: each player's or IP's entries are an immutable list, replaced
 * whole. Expiry is checked when read, so nothing runs on a timer; an expired entry is
 * dropped the next time it's read.
 */
public final class Active {

    private final Map<UUID, List<Entry>> bans = new ConcurrentHashMap<>();
    private final Map<UUID, List<Entry>> mutes = new ConcurrentHashMap<>();
    private final Map<String, List<Entry>> ipBans = new ConcurrentHashMap<>();
    private final Map<String, List<Entry>> ipMutes = new ConcurrentHashMap<>();
    private final Map<Long, Entry> byId = new ConcurrentHashMap<>();

    /** Adds a ban or mute that's in force. Anything else is ignored. */
    public void add(Entry entry) {
        PunishmentType type = entry.type();
        if (type != PunishmentType.BAN && type != PunishmentType.MUTE || entry.punishment().removal() != null) {
            return;
        }
        if (byId.putIfAbsent(entry.id(), entry) != null) {
            return;
        }
        boolean ban = type == PunishmentType.BAN;
        UUID target = entry.punishment().target();
        if (target != null) {
            append(ban ? bans : mutes, target, entry);
        }
        if (entry.punishment().ipBased() && entry.ip() != null) {
            append(ban ? ipBans : ipMutes, entry.ip(), entry);
        }
    }

    /** Takes a punishment out, as when it's revoked. Returns what was there, or null. */
    public Entry remove(long id) {
        Entry entry = byId.remove(id);
        if (entry == null) {
            return null;
        }
        boolean ban = entry.type() == PunishmentType.BAN;
        UUID target = entry.punishment().target();
        if (target != null) {
            drop(ban ? bans : mutes, target, id);
        }
        if (entry.ip() != null) {
            drop(ban ? ipBans : ipMutes, entry.ip(), id);
        }
        return entry;
    }

    /** The punishment with this id, if it's a ban or mute in memory. */
    public Entry byId(long id) {
        return byId.get(id);
    }

    /**
     * The ban that keeps this player out of {@code server}: on their account first, then on
     * their IP. Null if none.
     *
     * @param ip the IP key they're joining from, or null
     */
    public Entry ban(UUID player, String ip, String server, long now) {
        Entry found = first(bans, player, server, now);
        return found != null || ip == null ? found : first(ipBans, ip, server, now);
    }

    /** The mute that applies to this player on {@code server}, by account or IP. Null if none. */
    public Entry mute(UUID player, String ip, String server, long now) {
        Entry found = first(mutes, player, server, now);
        return found != null || ip == null ? found : first(ipMutes, ip, server, now);
    }

    /** The player's ban or mute on exactly this scope, to tell whether a new one replaces it. */
    public Entry onScope(PunishmentType type, UUID player, String ip, String scope, long now) {
        Map<UUID, List<Entry>> byPlayer = type == PunishmentType.BAN ? bans : mutes;
        Map<String, List<Entry>> byIp = type == PunishmentType.BAN ? ipBans : ipMutes;
        if (player != null) {
            for (Entry entry : byPlayer.getOrDefault(player, List.of())) {
                if (entry.active(now) && entry.punishment().scope().equalsIgnoreCase(scope)) {
                    return entry;
                }
            }
        }
        if (ip != null) {
            for (Entry entry : byIp.getOrDefault(ip, List.of())) {
                if (entry.active(now) && entry.punishment().scope().equalsIgnoreCase(scope)) {
                    return entry;
                }
            }
        }
        return null;
    }

    /** Every ban or mute that applies on {@code server} (null: on any), newest first, for /banlist and /mutelist. */
    public List<Entry> all(PunishmentType type, String server, long now) {
        List<Entry> found = new ArrayList<>();
        for (Entry entry : byId.values()) {
            if (entry.type() == type && entry.active(now) && (server == null || entry.appliesOn(server))) {
                found.add(entry);
            }
        }
        found.sort(Comparator.comparingLong(Entry::id).reversed());
        return found;
    }

    /** How many bans and mutes are in memory, expired ones not yet dropped included. */
    public int size() {
        return byId.size();
    }

    private static <K> void append(Map<K, List<Entry>> map, K key, Entry entry) {
        map.compute(key, (k, list) -> {
            if (list == null) {
                return List.of(entry);
            }
            List<Entry> copy = new ArrayList<>(list.size() + 1);
            copy.addAll(list);
            copy.add(entry);
            return List.copyOf(copy);
        });
    }

    private static <K> void drop(Map<K, List<Entry>> map, K key, long id) {
        map.computeIfPresent(key, (k, list) -> {
            List<Entry> kept = list.stream().filter(entry -> entry.id() != id).toList();
            return kept.isEmpty() ? null : kept;
        });
    }

    private <K> Entry first(Map<K, List<Entry>> map, K key, String server, long now) {
        List<Entry> list = map.get(key);
        if (list == null) {
            return null;
        }
        Entry found = null;
        for (Entry entry : list) {
            if (!entry.active(now)) {
                // Ended on its own: forget it, so it's never looked at again.
                remove(entry.id());
            } else if (found == null && entry.appliesOn(server)) {
                found = entry;
            }
        }
        return found;
    }
}
