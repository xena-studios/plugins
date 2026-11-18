package co.xenastudios.xteleport.home;

import co.xenastudios.xteleport.api.Home;
import co.xenastudios.xteleport.storage.Store;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * Online players' homes and TPA setting, in memory: read from the database when they join,
 * changed here and written through, forgotten when they leave. Safe from any thread.
 *
 * <p>Another player's homes (staff using {@code /homes <player>}) are read from the
 * database each time unless that player is online.
 */
public final class Homes {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,32}");

    /** One player's homes by lowercase name, sorted, and whether they turned TPA requests off. Never changed. */
    public record Loaded(Map<String, Home> homes, boolean tpaOff) {

        public static Loaded of(Store.PlayerData data) {
            Map<String, Home> homes = new TreeMap<>();
            data.homes().forEach(home -> homes.put(key(home.name()), home));
            return new Loaded(Collections.unmodifiableMap(homes), data.tpaOff());
        }

        public Optional<Home> home(String name) {
            return Optional.ofNullable(homes.get(key(name)));
        }

        public List<Home> list() {
            return List.copyOf(homes.values());
        }

        Loaded with(Home home) {
            Map<String, Home> copy = new TreeMap<>(homes);
            copy.put(key(home.name()), home);
            return new Loaded(Collections.unmodifiableMap(copy), tpaOff);
        }

        Loaded without(String name) {
            Map<String, Home> copy = new TreeMap<>(homes);
            copy.remove(key(name));
            return new Loaded(Collections.unmodifiableMap(copy), tpaOff);
        }
    }

    private final Store store;
    private final Predicate<UUID> online;
    private final Map<UUID, Loaded> loaded = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<Loaded>> loading = new ConcurrentHashMap<>();

    /**
     * @param online whether a player is online now, so a read that finishes after they left
     *     isn't kept
     */
    public Homes(Store store, Predicate<UUID> online) {
        this.store = store;
        this.online = online;
    }

    public static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    /** Whether a name can be a home's or a warp's: 1 to 32 letters, digits, - or _. */
    public static boolean validName(String name) {
        return NAME.matcher(name).matches();
    }

    /** An online player's homes, if they've been read. Any thread. */
    public Optional<Loaded> cached(UUID player) {
        return Optional.ofNullable(loaded.get(player));
    }

    /**
     * A player's homes: from memory when they're online and read, otherwise from the
     * database (and kept, if they're online). Two asks while it's being read share one read.
     */
    public CompletableFuture<Loaded> of(UUID player) {
        Loaded known = loaded.get(player);
        if (known != null) {
            return CompletableFuture.completedFuture(known);
        }
        CompletableFuture<Loaded> future = new CompletableFuture<>();
        CompletableFuture<Loaded> already = loading.putIfAbsent(player, future);
        if (already != null) {
            return already;
        }
        store.loadPlayer(player).whenComplete((data, error) -> {
            loading.remove(player, future);
            if (error != null) {
                future.completeExceptionally(error);
                return;
            }
            Loaded read = Loaded.of(data);
            if (online.test(player)) {
                // A change made while it was being read is newer than what was read.
                Loaded kept = loaded.putIfAbsent(player, read);
                future.complete(kept == null ? read : kept);
            } else {
                future.complete(read);
            }
        });
        return future;
    }

    /** Forgets a player who left. */
    public void forget(UUID player) {
        loaded.remove(player);
    }

    /** Forgets every player, so the next ask reads the database again, as after an import. */
    public void forgetAll() {
        loaded.clear();
    }

    /**
     * Sets a home, replacing one of the same name, and saves it. It's in memory at once; if
     * the save fails, it's taken back out and the future completes exceptionally.
     */
    public CompletableFuture<Boolean> set(Home home) {
        String name = key(home.name());
        Home[] before = new Home[1];
        return change(home.owner(), store.saveHome(home), homes -> {
            before[0] = homes.homes().get(name);
            return homes.with(home);
        }, homes -> homes.homes().get(name) != home ? homes
            : before[0] == null ? homes.without(name) : homes.with(before[0]));
    }

    /**
     * Deletes a home, and from the database. It's gone from memory at once; if the delete
     * fails, it's put back and the future completes exceptionally.
     */
    public CompletableFuture<Boolean> delete(UUID owner, String name) {
        String key = key(name);
        Home[] before = new Home[1];
        return change(owner, store.deleteHome(owner, name), homes -> {
            before[0] = homes.homes().get(key);
            return homes.without(name);
        }, homes -> before[0] == null || homes.homes().containsKey(key) ? homes : homes.with(before[0]));
    }

    /** Turns a player's TPA requests on or off, and saves it; as {@link #set}, undone if the save fails. */
    public CompletableFuture<Boolean> tpaOff(UUID player, boolean off) {
        boolean[] before = new boolean[1];
        return change(player, store.setTpaOff(player, off), homes -> {
            before[0] = homes.tpaOff();
            return new Loaded(homes.homes(), off);
        }, homes -> homes.tpaOff() == off ? new Loaded(homes.homes(), before[0]) : homes);
    }

    /**
     * Makes a change in memory now, while its write is queued behind any read of the player
     * already running, and again once the write is done: a read that was running when the
     * change was made stores what it read, which is older. If the write fails, {@code undo}
     * takes the change back, as long as nothing has changed it since.
     *
     * <p>The store runs everything in order on one thread, and a read stores what it read
     * before the next write starts, so "once the write is done" is always after that read.
     */
    private CompletableFuture<Boolean> change(UUID player, CompletableFuture<Boolean> write, UnaryOperator<Loaded> apply,
                                              UnaryOperator<Loaded> undo) {
        boolean[] applied = new boolean[1];
        loaded.computeIfPresent(player, (id, homes) -> {
            applied[0] = true;
            return apply.apply(homes);
        });
        return write.whenComplete((done, error) -> {
            if (error == null) {
                loaded.computeIfPresent(player, (id, homes) -> apply.apply(homes));
            } else if (applied[0]) {
                loaded.computeIfPresent(player, (id, homes) -> undo.apply(homes));
            }
        });
    }

    /** Whether an online player turned TPA requests off. False until they're read. */
    public boolean tpaOff(UUID player) {
        Loaded known = loaded.get(player);
        return known != null && known.tpaOff();
    }
}
