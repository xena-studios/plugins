package co.xenastudios.xteleport.rtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/** What the search picks in one column, in each kind of world, and which chunks it asks for. */
class ColumnTest {

    ServerMock server;
    Plugin plugin;

    @BeforeEach
    void start() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
    }

    @AfterEach
    void stop() {
        MockBukkit.unmock();
    }

    /** An empty world of air from y 0 up (to 127 in the Nether, as there), with nothing at y 0 either. */
    WorldMock world(World.Environment environment) {
        WorldMock world = new WorldMock(Material.AIR, 0, environment == World.Environment.NETHER ? 128 : 256, 0);
        world.setName(environment.name().toLowerCase());
        world.setEnvironment(environment);
        server.addWorld(world);
        return world;
    }

    void column(World world, int x, int z, int y, Material type) {
        world.getBlockAt(x, 0, z).setType(Material.AIR);
        world.getBlockAt(x, y, z).setType(type);
    }

    @Test
    void standsOnTheTopBlock() {
        WorldMock world = world(World.Environment.NORMAL);
        column(world, 3, 4, 70, Material.GRASS_BLOCK);
        Location spot = Search.safeSpot(world, 3, 4, Set.of());
        assertNotNull(spot);
        assertEquals(71, spot.getBlockY());
        assertEquals(3.5, spot.getX());
        assertEquals(4.5, spot.getZ());
    }

    @Test
    void notOnWaterLeavesOrLava() {
        WorldMock world = world(World.Environment.NORMAL);
        for (Material bad : List.of(Material.WATER, Material.OAK_LEAVES, Material.LAVA, Material.MAGMA_BLOCK)) {
            column(world, 0, 0, 70, bad);
            assertNull(Search.safeSpot(world, 0, 0, Set.of()), bad.name());
        }
    }

    @Test
    void notInABlockedBiome() {
        WorldMock world = world(World.Environment.NORMAL);
        column(world, 0, 0, 70, Material.SAND);
        world.setBiome(0, 71, 0, Biome.DESERT);
        assertNull(Search.safeSpot(world, 0, 0, Set.of(Biome.DESERT)));
        assertNotNull(Search.safeSpot(world, 0, 0, Set.of(Biome.OCEAN)));
    }

    @Test
    void theNetherBelowItsRoof() {
        WorldMock world = world(World.Environment.NETHER);
        column(world, 0, 0, 40, Material.NETHERRACK);
        for (int y = 100; y < 128; y++) {
            world.getBlockAt(0, y, 0).setType(Material.BEDROCK);
        }
        Location spot = Search.safeSpot(world, 0, 0, Set.of());
        assertNotNull(spot);
        assertEquals(41, spot.getBlockY(), "the floor under the roof, not the roof");
        // A floor with one block of room isn't a spot.
        world.getBlockAt(0, 42, 0).setType(Material.NETHERRACK);
        world.getBlockAt(0, 43, 0).setType(Material.NETHERRACK);
        Location higher = Search.safeSpot(world, 0, 0, Set.of());
        assertNotNull(higher);
        assertEquals(44, higher.getBlockY());
        world.getBlockAt(0, 46, 0).setType(Material.MAGMA_BLOCK);
        assertEquals(44, Search.safeSpot(world, 0, 0, Set.of()).getBlockY(), "a bad floor above is passed over");
        for (int y = 45; y < 100; y++) {
            world.getBlockAt(0, y, 0).setType(Material.NETHERRACK);
        }
        assertNull(Search.safeSpot(world, 0, 0, Set.of()), "solid up to the roof");
    }

    @Test
    void theEndsVoidIsNoSpot() {
        WorldMock world = world(World.Environment.THE_END);
        world.getBlockAt(0, 0, 0).setType(Material.AIR);
        assertNull(Search.safeSpot(world, 0, 0, Set.of()), "an empty column");
        column(world, 0, 0, 60, Material.END_STONE);
        assertEquals(61, Search.safeSpot(world, 0, 0, Set.of()).getBlockY(), "an outer island");
    }

    /** A loader that records each chunk asked for, and has none of them. */
    static final class NoChunks implements co.xenastudios.xteleport.teleport.Safety.ChunkLoader {
        final List<Boolean> asked = new ArrayList<>();

        @Override
        public CompletableFuture<org.bukkit.Chunk> load(World world, int chunkX, int chunkZ, boolean generate) {
            asked.add(generate);
            return CompletableFuture.completedFuture(null);
        }
    }

    @Test
    void theEndsMainIslandIsSkippedUnlessAllowed() {
        WorldMock world = world(World.Environment.THE_END);
        NoChunks loader = new NoChunks();
        Search search = new Search(plugin, loader);
        Search.Area onTheIsland = new Search.Area(0, 0, 0, 900, 8, false, false, Set.of());
        assertNull(search.find(world, onTheIsland).join());
        assertTrue(loader.asked.isEmpty(), "no chunk loaded for a spot on the main island");

        search.find(world, new Search.Area(0, 0, 0, 900, 8, false, true, Set.of())).join();
        assertEquals(8, loader.asked.size(), "allowed, each try loads its chunk");
    }

    @Test
    void onlyGeneratedChunksNeverGenerates() {
        WorldMock world = world(World.Environment.NORMAL);
        NoChunks loader = new NoChunks();
        Search search = new Search(plugin, loader);
        assertNull(search.find(world, new Search.Area(0, 0, 100, 1000, 5, true, false, Set.of())).join(),
            "a chunk never generated is skipped");
        assertEquals(List.of(false, false, false, false, false), loader.asked);
        loader.asked.clear();
        search.find(world, new Search.Area(0, 0, 100, 1000, 2, false, false, Set.of())).join();
        assertEquals(List.of(true, true), loader.asked);
    }

    @Test
    void aBrokenWorldFailsTheSearchInsteadOfThrowing() {
        Search search = new Search(plugin, new NoChunks());
        World broken = (World) java.lang.reflect.Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] {World.class},
            (self, method, args) -> {
                throw new IllegalStateException("no border");
            });
        CompletableFuture<Search.Found> found = search.find(broken, new Search.Area(0, 0, 0, 10, 1, false, false, Set.of()));
        assertTrue(found.isCompletedExceptionally());
    }
}
