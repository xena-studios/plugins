package co.xenastudios.xteleport.warp;

import co.xenastudios.xteleport.api.Warp;
import co.xenastudios.xteleport.home.Homes;
import co.xenastudios.xteleport.storage.Store;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

/**
 * Warps and the spawn, which the whole server shares: read once when the database opens,
 * kept in memory, changed here and written through. Safe from any thread.
 */
public final class Places {

    private final Store store;
    // Replaced whole on each change.
    private volatile Map<String, Warp> warps = Map.of();
    private volatile Warp spawn;
    private volatile boolean loaded;

    public Places(Store store) {
        this.store = store;
    }

    /** Takes what was read from the database. */
    public void load(Store.ServerData data) {
        Map<String, Warp> read = new TreeMap<>();
        data.warps().forEach(warp -> read.put(Homes.key(warp.name()), warp));
        warps = Collections.unmodifiableMap(read);
        spawn = data.spawn();
        loaded = true;
    }

    /** Whether the warps have been read yet. */
    public boolean loaded() {
        return loaded;
    }

    public List<Warp> warps() {
        return List.copyOf(warps.values());
    }

    public Optional<Warp> warp(String name) {
        return Optional.ofNullable(warps.get(Homes.key(name)));
    }

    public synchronized CompletableFuture<Boolean> setWarp(Warp warp) {
        Map<String, Warp> copy = new TreeMap<>(warps);
        copy.put(Homes.key(warp.name()), warp);
        warps = Collections.unmodifiableMap(copy);
        return store.saveWarp(warp);
    }

    public synchronized CompletableFuture<Boolean> deleteWarp(String name) {
        Map<String, Warp> copy = new TreeMap<>(warps);
        copy.remove(Homes.key(name));
        warps = Collections.unmodifiableMap(copy);
        return store.deleteWarp(name);
    }

    /** The spawn set with /setspawn, or null. */
    public Warp setSpawn() {
        return spawn;
    }

    public CompletableFuture<Boolean> setSpawn(Warp at) {
        spawn = at;
        return store.saveSpawn(at);
    }

    /**
     * Where /spawn goes: the spawn set with /setspawn if its world is loaded, otherwise the
     * main world's spawn point. Null only if no world is loaded at all.
     */
    public Location spawn() {
        Warp set = spawn;
        Location location = set == null ? null : set.location();
        if (location != null) {
            return location;
        }
        List<World> worlds = Bukkit.getWorlds();
        return worlds.isEmpty() ? null : worlds.getFirst().getSpawnLocation();
    }

    /** A place as stored, from a location. */
    public static Warp of(String name, Location at) {
        return new Warp(name, at.getWorld().getName(), at.getX(), at.getY(), at.getZ(), at.getYaw(), at.getPitch());
    }
}
