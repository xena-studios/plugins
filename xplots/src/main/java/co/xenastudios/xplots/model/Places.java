package co.xenastudios.xplots.model;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;

/** Places players are sent to. */
public final class Places {

    private Places() {}

    /**
     * Where a player stands to face the plot: by its sign, on the path side, looking at
     * the plot.
     */
    public static Location front(World world, Plot plot) {
        BlockFace facing = plot.signFacing();
        int x = plot.signX();
        int z = plot.signZ();
        if (plot.box().containsColumn(x, z)) {
            // The sign stands inside the plot's edge: stand just outside it.
            x += facing.getModX();
            z += facing.getModZ();
        }
        Location location = new Location(world, x + 0.5, plot.signY(), z + 0.5);
        location.setDirection(new org.bukkit.util.Vector(-facing.getModX(), 0, -facing.getModZ()));
        return location;
    }

    /** {@code x,y,z,yaw,pitch}, as a field's warp is stored. */
    public static String encode(Location location) {
        return String.format(java.util.Locale.ROOT, "%.2f,%.2f,%.2f,%.1f,%.1f",
            location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
    }

    /** A stored warp in the world, or null if it can't be read. */
    public static Location decode(World world, String stored) {
        if (stored == null) {
            return null;
        }
        String[] parts = stored.split(",");
        if (parts.length != 5) {
            return null;
        }
        try {
            return new Location(world, Double.parseDouble(parts[0]), Double.parseDouble(parts[1]), Double.parseDouble(parts[2]),
                Float.parseFloat(parts[3]), Float.parseFloat(parts[4]));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
