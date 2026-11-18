package co.xenastudios.xteleport.teleport;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.plugin.Plugin;

/**
 * Whether a spot is safe to arrive at: not in lava or fire, not inside blocks, and not
 * over the void. The spot's chunk is loaded in the background first, and the blocks are
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
     * @return a future completed with whether it's safe; false if its world isn't loaded
     */
    public static CompletableFuture<Boolean> check(Plugin plugin, ChunkLoader loader, Location at) {
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
                    safe.complete(arrival(at));
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
     * Whether a loaded spot is safe to stand at, as a player saved it: nothing harmful at the
     * feet or head, neither inside a full block, and something to land on above the void
     * that isn't lava. A fall onto solid ground is allowed: the player stood there before.
     * On the thread that owns the spot.
     */
    public static boolean arrival(Location at) {
        World world = at.getWorld();
        int x = at.getBlockX();
        int y = at.getBlockY();
        int z = at.getBlockZ();
        if (y <= world.getMinHeight() || y >= world.getMaxHeight()) {
            return false;
        }
        Block feet = world.getBlockAt(x, y, z);
        Block head = feet.getRelative(0, 1, 0);
        if (harmful(feet) || harmful(head) || feet.getType().isOccluding() || head.getType().isOccluding()) {
            return false;
        }
        // From just under the feet, so a spot on a slab (y = 64.5) finds the slab itself.
        for (int below = (int) Math.floor(at.getY() - 0.01); below >= world.getMinHeight(); below--) {
            Block ground = world.getBlockAt(x, below, z);
            Material type = ground.getType();
            if (HURTS.contains(type)) {
                return false;
            }
            if (type.isSolid() || liquid(type)) {
                return true;
            }
        }
        return false;
    }

    /** Water and lava, as blocks. Waterlogged blocks count by their own shape. */
    public static boolean liquid(Material type) {
        return type == Material.WATER || type == Material.LAVA || type == Material.BUBBLE_COLUMN;
    }

    private static boolean harmful(Block block) {
        return HURTS.contains(block.getType());
    }
}
