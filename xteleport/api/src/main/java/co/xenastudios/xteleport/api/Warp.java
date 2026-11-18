package co.xenastudios.xteleport.api;

import org.bukkit.Location;

/**
 * A warp.
 *
 * @param name its name, as it was written
 * @param world the name of its world, which may not be loaded
 * @param x where it is, x
 * @param y where it is, y
 * @param z where it is, z
 * @param yaw which way it faces
 * @param pitch which way it faces, up and down
 * @since 1.0.0
 */
public record Warp(String name, String world, double x, double y, double z, float yaw, float pitch) {

    /**
     * Where the warp is, if its world is loaded. Any thread.
     *
     * @return a new location, or null if the world isn't loaded
     * @since 1.0.0
     */
    public Location location() {
        org.bukkit.World loaded = org.bukkit.Bukkit.getWorld(world);
        return loaded == null ? null : new Location(loaded, x, y, z, yaw, pitch);
    }
}
