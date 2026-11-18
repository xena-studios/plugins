package co.xenastudios.core.player;

import co.xenastudios.core.task.Tasks;
import com.destroystokyo.paper.profile.PlayerProfile;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * Names and uuids of players who may be offline, for commands such as {@code /ban Steve}
 * or {@code /pay Alex 5}: name to uuid, and uuid to the last name known. Never blocks the
 * calling thread.
 *
 * <p>Answers from memory when it can, with a future that's already complete: an online
 * player, then this cache, then the server's own cache of players who have joined.
 * Otherwise it asks off the server's threads: the player's saved data, then Mojang
 * through Paper's profile lookup (or the offline uuid, on an offline-mode server that
 * isn't behind a proxy). The last 2,000 answers are kept, and a name Mojang doesn't know
 * isn't asked about again for ten minutes, so a typo can't run into Mojang's rate limit.
 *
 * <p>A future completed off the server's threads runs its callbacks there too: code that
 * then touches a player or the world goes through {@link Tasks}.
 */
public final class Profiles {

    /** A player's uuid and their name, as they write it. */
    public record Profile(UUID id, String name) {}

    private static final Pattern NAME = Pattern.compile("\\w{1,16}");
    static final int KEEP = 2_000;
    static final long MISS_MS = 10 * 60_000;

    private final Source source;
    private final Executor executor;
    private final LongSupplier clock;
    private final Map<String, Profile> byName = lru();
    private final Map<UUID, Profile> byId = lru();
    private final Map<String, Long> missing = lru();
    private final Map<Object, CompletableFuture<Optional<Profile>>> asking = new ConcurrentHashMap<>();

    public Profiles(Tasks tasks) {
        this(new ServerSource(), tasks::async, System::currentTimeMillis);
    }

    Profiles(Source source, Executor executor, LongSupplier clock) {
        this.source = source;
        this.executor = executor;
        this.clock = clock;
    }

    private static <K, V> Map<K, V> lru() {
        return java.util.Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > KEEP;
            }
        });
    }

    /** Whether a name could be a Minecraft name: 1 to 16 letters, digits and underscores. */
    public static boolean validName(String name) {
        return NAME.matcher(name).matches();
    }

    /**
     * The player with this name, online or not, ignoring case.
     *
     * @return a future, already complete when the answer was in memory, otherwise completed
     *     off the server's threads; empty if no such player exists
     */
    public CompletableFuture<Optional<Profile>> byName(String name) {
        if (!validName(name)) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        Optional<Profile> known = cached(name);
        if (known.isPresent()) {
            return CompletableFuture.completedFuture(known);
        }
        String key = name.toLowerCase(Locale.ROOT);
        Long missed = missing.get(key);
        if (missed != null && clock.getAsLong() - missed < MISS_MS) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return ask(key, () -> source.lookUp(name));
    }

    /**
     * The last name known for this uuid.
     *
     * @return a future, already complete when the answer was in memory, otherwise completed
     *     off the server's threads; empty if the name can't be found
     */
    public CompletableFuture<Optional<Profile>> byId(UUID id) {
        Optional<Profile> known = cached(id);
        if (known.isPresent()) {
            return CompletableFuture.completedFuture(known);
        }
        return ask(id, () -> source.lookUp(id));
    }

    /** The player with this name from memory only, or empty. Any thread. */
    public Optional<Profile> cached(String name) {
        Profile profile = source.online(name);
        if (profile == null) {
            profile = byName.get(name.toLowerCase(Locale.ROOT));
        }
        if (profile == null) {
            profile = source.serverCached(name);
        }
        return found(profile);
    }

    /** The last name known for this uuid from memory only, or empty. Any thread. */
    public Optional<Profile> cached(UUID id) {
        Profile profile = source.online(id);
        if (profile == null) {
            profile = byId.get(id);
        }
        return found(profile);
    }

    /** Remembers a player's name, as a plugin that sees one (in its own tables, say) can. */
    public void remember(UUID id, String name) {
        Profile profile = new Profile(id, name);
        Profile before = byId.put(id, profile);
        if (before != null && !before.name().equalsIgnoreCase(name)) {
            byName.remove(before.name().toLowerCase(Locale.ROOT));
        }
        byName.put(name.toLowerCase(Locale.ROOT), profile);
        missing.remove(name.toLowerCase(Locale.ROOT));
    }

    private Optional<Profile> found(Profile profile) {
        if (profile == null) {
            return Optional.empty();
        }
        remember(profile.id(), profile.name());
        return Optional.of(profile);
    }

    private CompletableFuture<Optional<Profile>> ask(Object key, Supplier<Profile> lookUp) {
        // Two commands asking about the same player at once share one lookup.
        CompletableFuture<Optional<Profile>> future = new CompletableFuture<>();
        CompletableFuture<Optional<Profile>> already = asking.putIfAbsent(key, future);
        if (already != null) {
            return already;
        }
        try {
            executor.execute(() -> {
                try {
                    Profile profile = lookUp.get();
                    if (profile == null && key instanceof String name) {
                        missing.put(name, clock.getAsLong());
                    }
                    Optional<Profile> result = found(profile);
                    asking.remove(key, future);
                    future.complete(result);
                } catch (RuntimeException e) {
                    asking.remove(key, future);
                    future.completeExceptionally(e);
                }
            });
        } catch (RuntimeException e) {
            // The plugin is disabled and can't schedule any more.
            asking.remove(key, future);
            future.completeExceptionally(e);
        }
        return future;
    }

    /** Where answers come from: the live server, or a test's stand-in. */
    interface Source {
        /** An online player with this name, from memory. */
        Profile online(String name);

        Profile online(UUID id);

        /** A player who has joined, from the server's cache in memory. */
        Profile serverCached(String name);

        /** Blocking: may read disk or ask Mojang. Null if there's no such player. */
        Profile lookUp(String name);

        /** Blocking: may read disk or ask Mojang. Null if the name can't be found. */
        Profile lookUp(UUID id);
    }

    private static final class ServerSource implements Source {

        private static final Function<Player, Profile> OF_PLAYER = player -> new Profile(player.getUniqueId(), player.getName());

        @Override
        public Profile online(String name) {
            Player player = Bukkit.getPlayerExact(name);
            return player == null ? null : OF_PLAYER.apply(player);
        }

        @Override
        public Profile online(UUID id) {
            Player player = Bukkit.getPlayer(id);
            return player == null ? null : OF_PLAYER.apply(player);
        }

        @Override
        public Profile serverCached(String name) {
            // The server's user cache, in memory: no web request.
            OfflinePlayer player = Bukkit.getOfflinePlayerIfCached(name);
            return player == null || player.getName() == null ? null : new Profile(player.getUniqueId(), player.getName());
        }

        @Override
        public Profile lookUp(String name) {
            return complete(Bukkit.createProfile(null, name));
        }

        @Override
        public Profile lookUp(UUID id) {
            // The user cache, then the player's saved data on disk.
            String name = Bukkit.getOfflinePlayer(id).getName();
            if (name != null) {
                return new Profile(id, name);
            }
            return complete(Bukkit.createProfile(id, null));
        }

        // Paper's lookup: the user cache, then Mojang (online mode or behind an online-mode
        // proxy), or the offline uuid otherwise.
        private static Profile complete(PlayerProfile profile) {
            if (!profile.complete(false) || profile.getId() == null || profile.getName() == null) {
                return null;
            }
            return new Profile(profile.getId(), profile.getName());
        }
    }
}
