package co.xenastudios.xteleport.rtp;

import co.xenastudios.xteleport.teleport.Safety;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.plugin.Plugin;

/**
 * Finds a safe random spot in a world, for {@code /rtp} and the API, cheaply:
 *
 * <ul>
 *   <li>One candidate at a time: a point in the ring between the radii, inside the world
 *       border. Its chunk loads in the background ({@code getChunkAtAsync}), or, when only
 *       generated chunks may be used, is skipped if it was never generated.</li>
 *   <li>The blocks are read on the thread that owns the chunk (its region's on Folia, the
 *       main thread on Paper), as one short read of a column.</li>
 *   <li>At most {@code attempts} candidates per search, one search per player at a time,
 *       and a server-wide cap on searches running at once.</li>
 * </ul>
 */
public final class Search {

    /** The End's main island reaches about this far from 0,0; its outer islands start beyond. */
    static final int MAIN_ISLAND = 1000;
    /** Spots stay this far inside the world border. */
    static final int BORDER_MARGIN = 16;

    /** Ground nobody should land on. */
    static final Set<Material> BAD_GROUND = Set.of(
        Material.LAVA, Material.MAGMA_BLOCK, Material.FIRE, Material.SOUL_FIRE, Material.CAMPFIRE, Material.SOUL_CAMPFIRE,
        Material.CACTUS, Material.SWEET_BERRY_BUSH, Material.WITHER_ROSE, Material.POWDER_SNOW, Material.POINTED_DRIPSTONE,
        Material.WATER, Material.BUBBLE_COLUMN, Material.KELP, Material.KELP_PLANT, Material.SEAGRASS, Material.TALL_SEAGRASS,
        Material.BEDROCK);

    /**
     * Where to look.
     *
     * @param allowMainIsland the End only: whether spots on the main island may be picked
     * @param blockedBiomes biomes never picked
     */
    public record Area(int centerX, int centerZ, int minRadius, int maxRadius, int attempts, boolean onlyGenerated,
                       boolean allowMainIsland, Set<Biome> blockedBiomes) {}

    /** A spot found, and how many candidates it took. */
    public record Found(Location location, int tries) {}

    /** Whether a search may start. */
    public enum Start { STARTED, BUSY, ALREADY }

    private final Plugin plugin;
    private final Safety.ChunkLoader loader;
    private final AtomicInteger running = new AtomicInteger();
    private final Set<UUID> searching = ConcurrentHashMap.newKeySet();

    public Search(Plugin plugin, Safety.ChunkLoader loader) {
        this.plugin = plugin;
        this.loader = loader;
    }

    /**
     * Takes a place among the searches running, if there's room and the player (null for
     * none, as for the API) isn't already searching. Each start needs one {@link #end}.
     */
    public Start begin(UUID player, int max) {
        if (player != null && !searching.add(player)) {
            return Start.ALREADY;
        }
        while (true) {
            int now = running.get();
            if (now >= max) {
                if (player != null) {
                    searching.remove(player);
                }
                return Start.BUSY;
            }
            if (running.compareAndSet(now, now + 1)) {
                return Start.STARTED;
            }
        }
    }

    public void end(UUID player) {
        running.decrementAndGet();
        if (player != null) {
            searching.remove(player);
        }
    }

    /** Whether the player has a search running. */
    public boolean searching(UUID player) {
        return searching.contains(player);
    }

    /** How many searches are running, for tests. */
    int running() {
        return running.get();
    }

    /**
     * Searches. Call {@link #begin} first and {@link #end} when the future completes. Any
     * thread.
     *
     * @return a future completed with a spot, or null if none was found in the attempts;
     *     never thrown, so the caller's {@link #end} always runs
     */
    public CompletableFuture<Found> find(World world, Area area) {
        CompletableFuture<Found> found = new CompletableFuture<>();
        try {
            WorldBorder border = world.getWorldBorder();
            double half = border.getSize() / 2 - BORDER_MARGIN;
            Location center = border.getCenter();
            Bounds bounds = new Bounds(center.getX() - half, center.getX() + half, center.getZ() - half, center.getZ() + half);
            boolean end = world.getEnvironment() == World.Environment.THE_END && !area.allowMainIsland();
            attempt(world, area, bounds, end, 1, found);
        } catch (RuntimeException e) {
            found.completeExceptionally(e);
        }
        return found;
    }

    record Bounds(double minX, double maxX, double minZ, double maxZ) {
        boolean contains(int x, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }

        /** How far the furthest corner is from a point. */
        double furthest(double x, double z) {
            double dx = Math.max(Math.abs(minX - x), Math.abs(maxX - x));
            double dz = Math.max(Math.abs(minZ - z), Math.abs(maxZ - z));
            return Math.sqrt(dx * dx + dz * dz);
        }
    }

    // Points outside the border or on the End's main island drawn before giving up.
    static final int MAX_MISSES = 1000;

    private void attempt(World world, Area area, Bounds bounds, boolean skipMainIsland, int attempt, CompletableFuture<Found> found) {
        // Candidates the border or the main island rule out cost no chunk and don't count as
        // tries: with a border smaller than max-radius, most of the ring is outside it. Only
        // the ring as far as the border's furthest corner is drawn from, and a bounded number
        // of misses (cheap arithmetic) ends the search when the ring and border barely meet.
        int x = 0;
        int z = 0;
        int tries = attempt;
        boolean picked = false;
        double reach = Math.min(area.maxRadius(), bounds.furthest(area.centerX(), area.centerZ()));
        for (int misses = 0; reach >= area.minRadius() && misses < MAX_MISSES; misses++) {
            int[] point = pick(area.minRadius(), reach, area.centerX(), area.centerZ(), ThreadLocalRandom.current());
            x = point[0];
            z = point[1];
            if (bounds.contains(x, z) && !(skipMainIsland && (long) x * x + (long) z * z < (long) MAIN_ISLAND * MAIN_ISLAND)) {
                picked = true;
                break;
            }
        }
        if (!picked) {
            found.complete(null);
            return;
        }
        int bx = x;
        int bz = z;
        int tried = tries;
        Safety.ChunkLoader.safely(loader, world, bx >> 4, bz >> 4, !area.onlyGenerated())
            .whenComplete((chunk, error) -> {
            if (error != null || chunk == null) {
                next(world, area, bounds, skipMainIsland, tried, found);
                return;
            }
            Runnable check = () -> {
                Location spot;
                try {
                    spot = safeSpot(world, bx, bz, area.blockedBiomes());
                } catch (RuntimeException e) {
                    found.completeExceptionally(e);
                    return;
                }
                if (spot != null) {
                    found.complete(new Found(spot, tried));
                } else {
                    next(world, area, bounds, skipMainIsland, tried, found);
                }
            };
            if (Bukkit.isOwnedByCurrentRegion(world, bx >> 4, bz >> 4)) {
                check.run();
            } else {
                Bukkit.getRegionScheduler().execute(plugin, world, bx >> 4, bz >> 4, check);
            }
        });
    }

    private void next(World world, Area area, Bounds bounds, boolean skipMainIsland, int tried, CompletableFuture<Found> found) {
        if (tried >= area.attempts() || !plugin.isEnabled()) {
            found.complete(null);
        } else {
            attempt(world, area, bounds, skipMainIsland, tried + 1, found);
        }
    }

    /** A point in the ring, evenly spread over its area, not bunched at the centre. */
    static int[] pick(Area area, java.util.Random random) {
        return pick(area.minRadius(), area.maxRadius(), area.centerX(), area.centerZ(), random);
    }

    static int[] pick(double min, double max, double centerX, double centerZ, java.util.Random random) {
        double radius = Math.sqrt(min * min + random.nextDouble() * (max * max - min * min));
        double angle = random.nextDouble() * Math.PI * 2;
        return new int[] {
            (int) Math.floor(centerX + radius * Math.cos(angle)),
            (int) Math.floor(centerZ + radius * Math.sin(angle))};
    }

    /**
     * The spot to stand on in this column, or null if there's none that's safe. On the
     * thread that owns the column.
     */
    static Location safeSpot(World world, int x, int z, Set<Biome> blockedBiomes) {
        Block ground = world.hasCeiling() ? floorUnderRoof(world, x, z) : top(world, x, z);
        if (ground == null || !goodGround(ground)) {
            return null;
        }
        Block feet = ground.getRelative(0, 1, 0);
        Block head = ground.getRelative(0, 2, 0);
        if (!room(feet) || !room(head)) {
            return null;
        }
        if (!blockedBiomes.isEmpty() && blockedBiomes.contains(world.getBiome(x, feet.getY(), z))) {
            return null;
        }
        float yaw = ThreadLocalRandom.current().nextInt(4) * 90f;
        return new Location(world, x + 0.5, feet.getY(), z + 0.5, yaw, 0);
    }

    // The highest block, from the heightmap; null for an empty column (the End's void),
    // which the heightmap reports as below the world's floor.
    private static Block top(World world, int x, int z) {
        int y = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING);
        return y < world.getMinHeight() ? null : world.getBlockAt(x, y, z);
    }

    // The Nether: the highest good floor with two blocks of room above it, below the roof.
    private static Block floorUnderRoof(World world, int x, int z) {
        int top = Math.min(world.getLogicalHeight(), world.getMaxHeight()) - 2;
        int room = 0;
        for (int y = top; y > world.getMinHeight(); y--) {
            Block block = world.getBlockAt(x, y, z);
            if (room(block)) {
                room++;
            } else {
                if (room >= 2 && goodGround(block)) {
                    return block;
                }
                room = 0;
            }
        }
        return null;
    }

    private static boolean goodGround(Block ground) {
        Material type = ground.getType();
        return type.isSolid() && !BAD_GROUND.contains(type) && !Tag.LEAVES.isTagged(type);
    }

    private static boolean room(Block block) {
        Material type = block.getType();
        return !type.isSolid() && !Safety.liquid(type) && !BAD_GROUND.contains(type);
    }
}
