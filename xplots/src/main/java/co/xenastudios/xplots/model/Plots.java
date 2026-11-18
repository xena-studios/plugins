package co.xenastudios.xplots.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * Every field and plot, held in memory and indexed so the questions events ask are
 * answered without a search: which plot is this block in, is this block a plot's sign,
 * which plots does this player rent. Main thread only.
 */
public final class Plots {

    private final Map<String, Field> fields = new HashMap<>();
    private final Map<String, WorldIndex> worlds = new HashMap<>();
    private final Map<UUID, List<Plot>> owned = new HashMap<>();

    /** One world's fields, with the box around all of them so most events stop at one check. */
    private static final class WorldIndex {
        final List<Field> fields = new ArrayList<>();
        final Map<Long, Plot> signs = new HashMap<>();
        final Map<Long, List<Plot>> signChunks = new HashMap<>();
        int minX;
        int minZ;
        int maxX;
        int maxZ;

        void bound() {
            minX = Integer.MAX_VALUE;
            minZ = Integer.MAX_VALUE;
            maxX = Integer.MIN_VALUE;
            maxZ = Integer.MIN_VALUE;
            for (Field field : fields) {
                Box b = field.bounds();
                minX = Math.min(minX, b.minX());
                minZ = Math.min(minZ, b.minZ());
                maxX = Math.max(maxX, b.maxX());
                maxZ = Math.max(maxZ, b.maxZ());
            }
        }
    }

    public boolean isEmpty() {
        return fields.isEmpty();
    }

    public Collection<Field> fields() {
        return fields.values();
    }

    public Field field(String name) {
        return fields.get(name.toLowerCase(Locale.ROOT));
    }

    /** {@code box-12}, or null. */
    public Plot plot(String id) {
        int dash = id.lastIndexOf('-');
        if (dash <= 0) {
            return null;
        }
        Field field = field(id.substring(0, dash));
        if (field == null) {
            return null;
        }
        try {
            int number = Integer.parseInt(id.substring(dash + 1));
            for (Plot plot : field.plots()) {
                if (plot.number() == number) {
                    return plot;
                }
            }
        } catch (NumberFormatException ignored) {
            // Not a plot id.
        }
        return null;
    }

    /** Every plot's id, for tab completion. */
    public List<String> plotIds() {
        List<String> ids = new ArrayList<>();
        for (Field field : fields.values()) {
            for (Plot plot : field.plots()) {
                ids.add(plot.id());
            }
        }
        return ids;
    }

    /** Adds a new field with the plots it has. */
    public void index(Field field) {
        fields.put(field.name(), field);
        WorldIndex world = worlds.computeIfAbsent(field.world(), key -> new WorldIndex());
        world.fields.add(field);
        for (Plot plot : field.plots()) {
            indexSign(world, plot);
            indexOwner(plot);
        }
        world.bound();
    }

    /** Adds a plot to a field already indexed. */
    public void add(Plot plot) {
        plot.field().add(plot);
        WorldIndex world = worlds.get(plot.field().world());
        indexSign(world, plot);
        indexOwner(plot);
        world.bound();
    }

    public void remove(Field field) {
        fields.remove(field.name());
        WorldIndex world = worlds.get(field.world());
        if (world != null) {
            world.fields.remove(field);
            for (Plot plot : field.plots()) {
                unindexSign(world, plot);
                unindexOwner(plot);
            }
            world.bound();
            if (world.fields.isEmpty()) {
                worlds.remove(field.world());
            }
        }
    }

    /** Takes one plot out of its field and the index. */
    public void remove(Plot plot) {
        WorldIndex world = worlds.get(plot.field().world());
        if (world != null) {
            unindexSign(world, plot);
        }
        unindexOwner(plot);
        plot.field().remove(plot);
    }

    /**
     * The plot at a block, {@link Field#PATH} for the rest of a field, or null outside
     * every field. Most blocks are outside every field, and stop at the first check.
     */
    public Plot at(World world, int x, int y, int z) {
        WorldIndex index = worlds.get(world.getName());
        if (index == null || x < index.minX || x > index.maxX || z < index.minZ || z > index.maxZ) {
            return null;
        }
        List<Field> list = index.fields;
        for (int i = 0, n = list.size(); i < n; i++) {
            Plot plot = list.get(i).locate(x, y, z);
            if (plot != null) {
                return plot;
            }
        }
        return null;
    }

    public Plot at(Block block) {
        return at(block.getWorld(), block.getX(), block.getY(), block.getZ());
    }

    /** The plot whose sign this is, or null. */
    public Plot signAt(Block block) {
        WorldIndex index = worlds.get(block.getWorld().getName());
        return index == null ? null : index.signs.get(blockKey(block.getX(), block.getY(), block.getZ()));
    }

    /** Plots whose signs are in this chunk. */
    public List<Plot> signsInChunk(World world, int chunkX, int chunkZ) {
        WorldIndex index = worlds.get(world.getName());
        if (index == null) {
            return List.of();
        }
        return index.signChunks.getOrDefault(chunkKey(chunkX, chunkZ), List.of());
    }

    /** Whether the world has any fields at all. */
    public boolean hasFields(World world) {
        return worlds.containsKey(world.getName());
    }

    public void moveSign(Plot plot, int x, int y, int z, org.bukkit.block.BlockFace facing) {
        WorldIndex world = worlds.get(plot.field().world());
        unindexSign(world, plot);
        plot.moveSign(x, y, z, facing);
        indexSign(world, plot);
    }

    /** The plots the player rents. */
    public List<Plot> owned(UUID player) {
        return owned.getOrDefault(player, List.of());
    }

    /** Gives the plot a lease, or takes it away with null, keeping the owner index right. */
    public void lease(Plot plot, Lease lease) {
        unindexOwner(plot);
        plot.lease(lease);
        indexOwner(plot);
    }

    /** Every rented plot. */
    public List<Plot> rented() {
        List<Plot> all = new ArrayList<>();
        owned.values().forEach(all::addAll);
        return all;
    }

    private void indexSign(WorldIndex world, Plot plot) {
        world.signs.put(blockKey(plot.signX(), plot.signY(), plot.signZ()), plot);
        world.signChunks.computeIfAbsent(chunkKey(plot.signX() >> 4, plot.signZ() >> 4), key -> new ArrayList<>()).add(plot);
    }

    private void unindexSign(WorldIndex world, Plot plot) {
        world.signs.remove(blockKey(plot.signX(), plot.signY(), plot.signZ()), plot);
        long chunk = chunkKey(plot.signX() >> 4, plot.signZ() >> 4);
        List<Plot> list = world.signChunks.get(chunk);
        if (list != null) {
            list.remove(plot);
            if (list.isEmpty()) {
                world.signChunks.remove(chunk);
            }
        }
    }

    private void indexOwner(Plot plot) {
        if (plot.lease() != null) {
            owned.computeIfAbsent(plot.lease().owner(), key -> new ArrayList<>(1)).add(plot);
        }
    }

    private void unindexOwner(Plot plot) {
        if (plot.lease() != null) {
            List<Plot> list = owned.get(plot.lease().owner());
            if (list != null) {
                list.remove(plot);
                if (list.isEmpty()) {
                    owned.remove(plot.lease().owner());
                }
            }
        }
    }

    /** Packs a block position into one number: 26 bits each for x and z, 12 for y. */
    public static long blockKey(int x, int y, int z) {
        return ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | (y & 0xFFF);
    }

    public static long chunkKey(int chunkX, int chunkZ) {
        return (long) chunkX << 32 | (chunkZ & 0xFFFFFFFFL);
    }
}
