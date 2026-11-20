package co.xenastudios.xteleport.teleport;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.plugin.Plugin;

/**
 * Whether a spot is safe to arrive at: not in lava or fire, not inside blocks, not over the
 * void or a long fall, and inside the world border. The spot's chunk is loaded in the background first, and the blocks are
 * read on the thread that owns them, so the check never loads a chunk on a game thread.
 */
public final class Safety {

    /** Blocks that hurt to be in or stand on. */
    static final Set<Material> HURTS = Set.of(
        Material.LAVA, Material.FIRE, Material.SOUL_FIRE, Material.MAGMA_BLOCK, Material.CAMPFIRE, Material.SOUL_CAMPFIRE,
        Material.CACTUS, Material.SWEET_BERRY_BUSH, Material.WITHER_ROSE, Material.POWDER_SNOW, Material.POINTED_DRIPSTONE);

    private Safety() {}

    /**
     * Loads the spot's chunk in the background, then checks the spot on its own thread.
     *
     * @param maxFall the most blocks of air there may be between the spot and the ground
     * @return a future completed with whether it's safe; false if its world isn't loaded
     */
    public static CompletableFuture<Boolean> check(Plugin plugin, ChunkLoader loader, Location at, int maxFall) {
        World world = at.isWorldLoaded() ? at.getWorld() : null;
        if (world == null) {
            return CompletableFuture.completedFuture(false);
        }
        CompletableFuture<Boolean> safe = new CompletableFuture<>();
        ChunkLoader.safely(loader, world, at.getBlockX() >> 4, at.getBlockZ() >> 4, true).whenComplete((chunk, error) -> {
            if (error != null || chunk == null) {
                safe.complete(false);
                return;
            }
            onRegion(plugin, at, () -> {
                try {
                    safe.complete(arrival(at, maxFall));
                } catch (RuntimeException e) {
                    safe.completeExceptionally(e);
                }
            });
        });
        return safe;
    }

    /** Loads a chunk in the background, as {@code World#getChunkAtAsync} does. */
    @FunctionalInterface
    public interface ChunkLoader {
        CompletableFuture<org.bukkit.Chunk> load(World world, int chunkX, int chunkZ, boolean generate);

        /** Loads it, with a call that throws counted as a chunk that couldn't load. */
        static CompletableFuture<org.bukkit.Chunk> safely(ChunkLoader loader, World world, int chunkX, int chunkZ, boolean generate) {
            try {
                return loader.load(world, chunkX, chunkZ, generate);
            } catch (RuntimeException e) {
                return CompletableFuture.failedFuture(e);
            }
        }
    }

    /** Runs on the thread that owns the spot: now if this is it. */
    static void onRegion(Plugin plugin, Location at, Runnable work) {
        if (Bukkit.isOwnedByCurrentRegion(at)) {
            work.run();
        } else {
            Bukkit.getRegionScheduler().execute(plugin, at, work);
        }
    }

    /**
     * Whether a loaded spot is safe to stand at: inside the world border, nothing harmful
     * at the feet or head, neither inside a full block, and ground that isn't harmful at
     * most {@code maxFall} blocks below, or water at any depth below. A ladder, vine or
     * scaffolding on the way down counts as ground. On the thread that owns the spot.
     *
     * <p>The world border is read from this thread too. On Folia it belongs to no region,
     * but reading it is only reading a few numbers, which every region's entities do.
     */
    public static boolean arrival(Location at, int maxFall) {
        World world = at.getWorld();
        int x = at.getBlockX();
        int y = at.getBlockY();
        int z = at.getBlockZ();
        if (y <= world.getMinHeight() || y >= world.getMaxHeight() || !world.getWorldBorder().isInside(at)) {
            return false;
        }
        Block feet = world.getBlockAt(x, y, z);
        Block head = feet.getRelative(0, 1, 0);
        if (harmful(feet) || harmful(head) || inside(feet, at.getY() - y) || head.getType().isOccluding()) {
            return false;
        }
        // From just under the feet, so a spot on a slab (y = 64.5) finds the slab itself.
        int top = (int) Math.floor(at.getY() - 0.01);
        for (int below = top; below >= world.getMinHeight(); below--) {
            Block block = world.getBlockAt(x, below, z);
            Material type = block.getType();
            if (HURTS.contains(type)) {
                return false;
            }
            if (liquid(type) || UNDERWATER.contains(type) || block.getBlockData() instanceof Waterlogged wet && wet.isWaterlogged()) {
                return true; // Water, kelp or anything waterlogged: lava is in HURTS.
            }
            if (type.isSolid() || Tag.CLIMBABLE.isTagged(type)) {
                return top - below <= maxFall;
            }
        }
        return false;
    }

    // Full blocks that aren't full height: standing on one, the feet are in its block but above it.
    private static final java.util.Map<Material, Double> SHORT = java.util.Map.of(
        Material.SOUL_SAND, 0.875, Material.MUD, 0.875, Material.FARMLAND, 0.9375, Material.DIRT_PATH, 0.9375);

    // Plants that only grow in water: a column of them is water to fall into.
    private static final java.util.Set<Material> UNDERWATER = java.util.EnumSet.of(
        Material.KELP, Material.KELP_PLANT, Material.SEAGRASS, Material.TALL_SEAGRASS);

    // Whether the feet are inside the block they're in: a full block, below its top.
    private static boolean inside(Block feet, double above) {
        if (!feet.getType().isOccluding()) {
            return false;
        }
        return above < SHORT.getOrDefault(feet.getType(), 1.0) - 0.001;
    }

    /** Water and lava, as blocks. Waterlogged blocks count by their own shape. */
    public static boolean liquid(Material type) {
        return type == Material.WATER || type == Material.LAVA || type == Material.BUBBLE_COLUMN;
    }

    private static boolean harmful(Block block) {
        return HURTS.contains(block.getType());
    }
}
