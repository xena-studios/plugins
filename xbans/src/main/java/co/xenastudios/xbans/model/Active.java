package co.xenastudios.xbans.model;

import co.xenastudios.xbans.api.PunishmentType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Every ban and mute in force, in memory, so the join check and the chat check never ask
 * the database. Loaded once when the database opens, then kept up to date by every
 * punishment made here or (in network mode) on another server.
 *
 * <p>Safe from any thread: each player's or IP's entries are an immutable list, replaced
 * whole, so reads take no lock. Changes (adding, removing, a reload finishing) take this
 * object's lock: they're rare, and each touches several maps that must agree. Expiry is
 * checked when read, so nothing runs on a timer; an expired entry is dropped the next time
 * it's read.
 */
public final class Active {

    private final Map<UUID, List<Entry>> bans = new ConcurrentHashMap<>();
    private final Map<UUID, List<Entry>> mutes = new ConcurrentHashMap<>();
    private final Map<String, List<Entry>> ipBans = new ConcurrentHashMap<>();
    private final Map<String, List<Entry>> ipMutes = new ConcurrentHashMap<>();
    private final Map<Long, Entry> byId = new ConcurrentHashMap<>();
    // While a reload from the database runs: the ids added or removed meanwhile, which it leaves alone.
    private final AtomicInteger syncing = new AtomicInteger();
    private final Set<Long> touched = ConcurrentHashMap.newKeySet();

    /** Adds a ban or mute that's in force. Anything else is ignored. */
    public synchronized void add(Entry entry) {
        PunishmentType type = entry.type();
        if (type != PunishmentType.BAN && type != PunishmentType.MUTE || entry.punishment().removal() != null) {
            return;
        }
        if (byId.putIfAbsent(entry.id(), entry) != null) {
            return;
        }
        touch(entry.id());
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
    public synchronized Entry remove(long id) {
        touch(id);
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

    /**
     * The bans or mutes on exactly this scope that touch this player's account or this IP
     * key, to tell whether a new one replaces them or is covered by them; with a null scope,
     * on any (for /checkban). Two map lookups, however many are in force.
     */
    public List<Entry> onScope(PunishmentType type, UUID player, String ip, String scope, long now) {
        Map<UUID, List<Entry>> byPlayer = type == PunishmentType.BAN ? bans : mutes;
        Map<String, List<Entry>> byIp = type == PunishmentType.BAN ? ipBans : ipMutes;
        Set<Entry> found = new LinkedHashSet<>();
        if (player != null) {
            for (Entry entry : byPlayer.getOrDefault(player, List.of())) {
                if (entry.active(now) && (scope == null || entry.punishment().scope().equalsIgnoreCase(scope))) {
                    found.add(entry);
                }
            }
        }
        if (ip != null) {
            for (Entry entry : byIp.getOrDefault(ip, List.of())) {
                if (entry.active(now) && (scope == null || entry.punishment().scope().equalsIgnoreCase(scope))) {
                    found.add(entry);
                }
            }
        }
        return List.copyOf(found);
    }

    /**
     * Every ban or mute that applies on {@code server} (null: on any), newest first, for
     * /banlist and /mutelist. Those that have ended are dropped on the way.
     */
    public List<Entry> all(PunishmentType type, String server, long now) {
        List<Entry> found = new ArrayList<>();
        for (Entry entry : byId.values()) {
            if (!entry.active(now)) {
                remove(entry.id());
            } else if (entry.type() == type && (server == null || entry.appliesOn(server))) {
                found.add(entry);
            }
        }
        found.sort(Comparator.comparingLong(Entry::id).reversed());
        return found;
    }

    /** Up to {@code limit} names of players with a ban or mute in force, for tab completion: no sorting. */
    public List<String> names(PunishmentType type, long now, int limit) {
        Map<UUID, List<Entry>> byPlayer = type == PunishmentType.BAN ? bans : mutes;
        Set<String> names = new LinkedHashSet<>();
        for (List<Entry> list : byPlayer.values()) {
            for (Entry entry : list) {
                if (!entry.active(now)) {
                    remove(entry.id());
                } else if (names.size() < limit) {
                    names.add(entry.punishment().targetName());
                }
            }
            if (names.size() >= limit) {
                break;
            }
        }
        return List.copyOf(names);
    }

    // ---- Reloading from the database ----

    /** A reload under way. Begun before the database is read, so changes made meanwhile are noticed. */
    public final class Sync {

        private boolean done;

        private Sync() {}

        /**
         * Brings memory in line with what the database holds in force: adds what's missing,
         * drops what isn't there any more, and leaves alone anything added or removed since
         * the reload began (memory is newer for those).
         *
         * @return the entries that were added
         */
        public List<Entry> finish(Collection<Entry> inForce) {
            synchronized (Active.this) {
                return apply(inForce);
            }
        }

        private List<Entry> apply(Collection<Entry> inForce) {
            try {
                Set<Long> ids = new HashSet<>();
                List<Entry> added = new ArrayList<>();
                for (Entry entry : inForce) {
                    ids.add(entry.id());
                    if (!touched.contains(entry.id()) && !byId.containsKey(entry.id())) {
                        add(entry);
                        added.add(entry);
                    }
                }
                for (Long id : List.copyOf(byId.keySet())) {
                    if (!ids.contains(id) && !touched.contains(id)) {
                        remove(id);
                    }
                }
                return added;
            } finally {
                end();
            }
        }

        /** Ends the reload without changing anything, as when the database couldn't be read. */
        public void abandon() {
            synchronized (Active.this) {
                end();
            }
        }

        private void end() {
            if (!done) {
                done = true;
                if (syncing.decrementAndGet() == 0) {
                    touched.clear();
                }
            }
        }
    }

    /** Starts a reload; call {@link Sync#finish} or {@link Sync#abandon} once the database has been read. */
    public synchronized Sync beginSync() {
        syncing.incrementAndGet();
        return new Sync();
    }

    private void touch(long id) {
        if (syncing.get() > 0) {
            touched.add(id);
        }
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
