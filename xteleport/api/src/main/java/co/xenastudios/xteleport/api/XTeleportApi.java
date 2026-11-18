package co.xenastudios.xteleport.api;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * xTeleport's developer API: teleports that feel like xTeleport's own (warmup, cooldown,
 * cost and a safe-arrival check), players' homes, warps, spawn, and the random teleport
 * search.
 *
 * <p>Get it with {@link #get()}, or from Bukkit's services manager. It's registered
 * while xTeleport is enabled.
 *
 * <p>Every method says how it runs: either it may be called from any thread, answers from
 * memory and returns immediately, or it returns a {@link CompletableFuture} that completes
 * off the calling thread, often off the server's threads. Code that then touches a player
 * or the world must hop to the right thread itself (the player's own scheduler on Folia).
 * Nothing here ever reads the database on the calling thread.
 *
 * @since 1.0.0
 */
public interface XTeleportApi {

    /**
     * xTeleport's API, from Bukkit's services manager.
     *
     * @return the API
     * @throws IllegalStateException if xTeleport isn't installed and enabled
     * @since 1.0.0
     */
    static XTeleportApi get() {
        XTeleportApi api = Bukkit.getServicesManager().load(XTeleportApi.class);
        if (api == null) {
            throw new IllegalStateException("xTeleport isn't enabled. Add it to depend (or softdepend and check first).");
        }
        return api;
    }

    /**
     * Teleports a player the way xTeleport's own commands do: checks the cooldown and
     * the cost, waits out the warmup (cancelled by moving, and by damage if xTeleport's
     * config says so), loads the destination in the background and refuses it if it's
     * unsafe, fires {@link co.xenastudios.xteleport.api.event.PreTeleportEvent}, charges,
     * and teleports with the {@code COMMAND} cause, so {@code /back} returns from it. A
     * teleport that fails after charging is refunded. The player is told each step in
     * xTeleport's messages.
     *
     * <p>Any thread: the work happens on the player's own thread.
     *
     * @param player an online player
     * @param destination where to; it's copied
     * @param options the warmup, cooldown, cost and safety check to use
     * @return a future completed with how it ended, off the calling thread
     * @since 1.0.0
     */
    CompletableFuture<TeleportResult> teleport(Player player, Location destination, TeleportOptions options);

    /**
     * How long until a player may use a cooldown again, such as {@code "home"},
     * {@code "rtp"} or a key a plugin passed in {@link TeleportOptions}. Any thread,
     * answers from memory.
     *
     * @param player the player
     * @param key the cooldown's key
     * @return the time left, or {@link Duration#ZERO} if it's ready
     * @since 1.0.0
     */
    Duration cooldown(UUID player, String key);

    /**
     * An online player's homes, from memory. Any thread. Homes are read when a player
     * joins, so for a moment after joining they may not be there yet.
     *
     * @param player an online player's uuid
     * @return their homes, sorted by name, or empty if they aren't online or their homes
     *     haven't been read yet
     * @since 1.0.0
     */
    Optional<List<Home>> cachedHomes(UUID player);

    /**
     * Any player's homes, online or not.
     *
     * @param player the player's uuid
     * @return a future with their homes, sorted by name, already complete for an online
     *     player whose homes are in memory, otherwise completed off the server's threads
     * @since 1.0.0
     */
    CompletableFuture<List<Home>> homes(UUID player);

    /**
     * Sets a home for a player, online or not, replacing one of the same name. It doesn't
     * check the player's home limit and doesn't fire
     * {@link co.xenastudios.xteleport.api.event.HomeSetEvent}: the caller decided.
     *
     * <p>For an online player it's in {@link #cachedHomes} at once, and taken back out if
     * the save fails.
     *
     * @param player the player's uuid
     * @param name the home's name: 1 to 32 letters, digits, {@code -} or {@code _}
     * @param location where; it's copied, and its world must be loaded
     * @return a future completed off the server's threads: with true once it's saved, with
     *     false at once if the name isn't allowed or the world isn't loaded, or
     *     exceptionally if the database couldn't save it
     * @since 1.0.0
     */
    CompletableFuture<Boolean> setHome(UUID player, String name, Location location);

    /**
     * Deletes a player's home, online or not.
     *
     * @param player the player's uuid
     * @param name the home's name, in any case
     * @return a future completed off the server's threads, with true if a home was deleted,
     *     or exceptionally if the database couldn't delete it (it's then still there)
     * @since 1.0.0
     */
    CompletableFuture<Boolean> deleteHome(UUID player, String name);

    /**
     * How many homes a player may have: from {@code xteleport.homes.<n>} permissions and
     * the config's limits, the highest. Any thread, from memory.
     *
     * @param player an online player
     * @return their limit, or {@link Integer#MAX_VALUE} for no limit
     * @since 1.0.0
     */
    int homeLimit(Player player);

    /**
     * Every warp, from memory. Any thread.
     *
     * @return the warps, sorted by name
     * @since 1.0.0
     */
    List<Warp> warps();

    /**
     * A warp, from memory. Any thread.
     *
     * @param name the warp's name, in any case
     * @return the warp, or empty if there's none by that name
     * @since 1.0.0
     */
    Optional<Warp> warp(String name);

    /**
     * Sets a warp, replacing one of the same name. It's in {@link #warps()} at once, and
     * taken back out if the save fails.
     *
     * @param name the warp's name: 1 to 32 letters, digits, {@code -} or {@code _}
     * @param location where; it's copied, and its world must be loaded
     * @return a future completed off the server's threads: with true once it's saved, with
     *     false at once if the name isn't allowed or the world isn't loaded, or
     *     exceptionally if the database couldn't save it
     * @since 1.0.0
     */
    CompletableFuture<Boolean> setWarp(String name, Location location);

    /**
     * Deletes a warp. It's gone from {@link #warps()} at once, and put back if the delete
     * fails.
     *
     * @param name the warp's name, in any case
     * @return a future completed off the server's threads, with true if a warp was deleted,
     *     or exceptionally if the database couldn't delete it
     * @since 1.0.0
     */
    CompletableFuture<Boolean> deleteWarp(String name);

    /**
     * Where {@code /spawn} goes: the spawn set with {@code /setspawn}, or the main world's
     * spawn point if none was set. Any thread, from memory.
     *
     * @return a copy of the spot, or empty if no spawn is set and no world is loaded
     * @since 1.0.0
     */
    Optional<Location> spawn();

    /**
     * Looks for a safe random spot, with the same search {@code /rtp} uses: no lava,
     * water, leaves, powder snow or void, under a roof in the Nether, and off the main
     * island in the End. It counts towards xTeleport's limit on searches running at once,
     * and loads each candidate chunk in the background.
     *
     * @param world the world to search
     * @param settings where to search and how many spots to try
     * @return a future completed off the calling thread with a safe spot, or empty if none
     *     was found in the attempts given, or too many searches are already running
     * @since 1.0.0
     */
    CompletableFuture<Optional<Location>> findSafeLocation(World world, RtpSettings settings);
}
