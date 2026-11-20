package co.xenastudios.xteleport.teleport;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.Location;
import org.bukkit.Material;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/** Where a player may arrive: on something, not in it, not far above it, inside the border. */
class SafetyTest {

    static final int FALL = 3;

    WorldMock world;

    @BeforeEach
    void start() {
        ServerMock server = MockBukkit.mock();
        // Nothing but air from y 0 to 255, and bedrock at 0, which these tests clear where they need the void.
        world = new WorldMock(Material.AIR, 0, 256, 0);
        world.setName("test");
        server.addWorld(world);
    }

    @AfterEach
    void stop() {
        MockBukkit.unmock();
    }

    boolean safe(double x, double y, double z) {
        return Safety.arrival(new Location(world, x, y, z), FALL);
    }

    @Test
    void standingOnTheGround() {
        world.getBlockAt(0, 63, 0).setType(Material.STONE);
        assertTrue(safe(0.5, 64, 0.5));
    }

    @Test
    void overTheVoid() {
        world.getBlockAt(0, 0, 0).setType(Material.AIR);
        assertFalse(safe(0.5, 64, 0.5));
    }

    @Test
    void onASlab() {
        world.getBlockAt(0, 64, 0).setType(Material.STONE_SLAB);
        assertTrue(safe(0.5, 64.5, 0.5), "the slab itself is the ground");
    }

    @Test
    void insideBlocks() {
        world.getBlockAt(0, 63, 0).setType(Material.STONE);
        world.getBlockAt(0, 65, 0).setType(Material.STONE);
        assertFalse(safe(0.5, 64, 0.5), "head inside a block");
        world.getBlockAt(0, 65, 0).setType(Material.AIR);
        world.getBlockAt(0, 64, 0).setType(Material.DIRT);
        assertFalse(safe(0.5, 64, 0.5), "feet inside a block");
    }

    @Test
    void onSoulSandOrMud() {
        for (Material shorter : new Material[] {Material.SOUL_SAND, Material.MUD}) {
            world.getBlockAt(0, 64, 0).setType(shorter);
            assertTrue(safe(0.5, 64.875, 0.5), "standing on " + shorter + ", 2 pixels lower than a full block");
            assertFalse(safe(0.5, 64, 0.5), "inside " + shorter);
        }
    }

    @Test
    void kelpIsWater() {
        world.getBlockAt(0, 50, 0).setType(Material.STONE);
        for (int y = 51; y < 64; y++) {
            world.getBlockAt(0, y, 0).setType(y == 63 ? Material.KELP : Material.KELP_PLANT);
        }
        assertTrue(safe(0.5, 64, 0.5), "a kelp forest is water to fall into");
    }

    @Test
    void lavaAndFire() {
        world.getBlockAt(0, 63, 0).setType(Material.LAVA);
        assertFalse(safe(0.5, 64, 0.5));
        world.getBlockAt(0, 63, 0).setType(Material.STONE);
        world.getBlockAt(0, 64, 0).setType(Material.FIRE);
        assertFalse(safe(0.5, 64, 0.5));
    }

    @Test
    void aShortFallButNotALongOne() {
        world.getBlockAt(0, 60, 0).setType(Material.STONE);
        assertTrue(safe(0.5, 64, 0.5), "three blocks of air, which don't hurt");
        assertFalse(safe(0.5, 65, 0.5), "four");
        assertFalse(safe(0.5, 120, 0.5), "high in the air over land");
    }

    @Test
    void waterBreaksAnyFall() {
        world.getBlockAt(0, 40, 0).setType(Material.WATER);
        assertTrue(safe(0.5, 120, 0.5));
    }

    @Test
    void aLadderOnTheWayDownCounts() {
        world.getBlockAt(0, 60, 0).setType(Material.LADDER);
        assertTrue(safe(0.5, 61, 0.5));
    }

    @Test
    void outsideTheWorldBorder() {
        world.getWorldBorder().setSize(100);
        world.getBlockAt(200, 63, 0).setType(Material.STONE);
        assertFalse(safe(200.5, 64, 0.5));
        world.getBlockAt(10, 63, 0).setType(Material.STONE);
        assertTrue(safe(10.5, 64, 0.5));
    }
}
