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
    public synchronized void load(Store.ServerData data) {
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

    /**
     * Sets a warp and saves it. It's in memory at once; if the save fails, it's taken back
     * out and the future completes exceptionally. Once saved, it's put in memory again, in
     * case the warps were being read when it was set (the read, ahead of the save, didn't
     * have it).
     */
    public CompletableFuture<Boolean> setWarp(Warp warp) {
        String key = Homes.key(warp.name());
        Warp before = put(key, warp);
        return store.saveWarp(warp).whenComplete((done, error) -> {
            if (error == null) {
                put(key, warp);
            } else {
                replaceIf(key, warp, before);
            }
        });
    }

    /** Deletes a warp; as {@link #setWarp}, put back if the delete fails. */
    public CompletableFuture<Boolean> deleteWarp(String name) {
        String key = Homes.key(name);
        Warp before = put(key, null);
        return store.deleteWarp(name).whenComplete((done, error) -> {
            if (error == null) {
                put(key, null);
            } else {
                replaceIf(key, null, before);
            }
        });
    }

    // Sets or removes one warp, returning what it replaced.
    private synchronized Warp put(String key, Warp warp) {
        Map<String, Warp> copy = new TreeMap<>(warps);
        Warp before = warp == null ? copy.remove(key) : copy.put(key, warp);
        warps = Collections.unmodifiableMap(copy);
        return before;
    }

    // Undoes a change that failed to save, unless something changed that warp since.
    private synchronized void replaceIf(String key, Warp expected, Warp restore) {
        if (warps.get(key) == expected && (expected != null || restore != null)) {
            put(key, restore);
        }
    }

    /** The spawn set with /setspawn, or null. */
    public Warp setSpawn() {
        return spawn;
    }

    /** Sets the spawn and saves it; as {@link #setWarp}, the old one comes back if the save fails. */
    public CompletableFuture<Boolean> setSpawn(Warp at) {
        Warp before;
        synchronized (this) {
            before = spawn;
            spawn = at;
        }
        return store.saveSpawn(at).whenComplete((done, error) -> {
            synchronized (this) {
                if (error == null) {
                    spawn = at;
                } else if (spawn == at) {
                    spawn = before;
                }
            }
        });
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
