package co.xenastudios.xteleport.api;

import java.util.UUID;
import org.bukkit.Location;

/**
 * A player's home.
 *
 * @param owner whose home it is
 * @param name its name, as the player wrote it
 * @param world the name of its world, which may not be loaded
 * @param x where it is, x
 * @param y where it is, y
 * @param z where it is, z
 * @param yaw which way it faces
 * @param pitch which way it faces, up and down
 * @since 1.0.0
 */
public record Home(UUID owner, String name, String world, double x, double y, double z, float yaw, float pitch) {

    /**
     * Where the home is, if its world is loaded. Any thread.
     *
     * @return a new location, or null if the world isn't loaded
     * @since 1.0.0
     */
    public Location location() {
        org.bukkit.World loaded = org.bukkit.Bukkit.getWorld(world);
        return loaded == null ? null : new Location(loaded, x, y, z, yaw, pitch);
    }
}
