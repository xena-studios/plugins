package co.xenastudios.xplots;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.xenastudios.xplots.menu.Menu;
import co.xenastudios.xplots.model.Field;
import co.xenastudios.xplots.model.Plot;
import java.util.function.BooleanSupplier;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/** xPlots on a mock server: a field made from the console, then protected, rented and put back. */
class PlotsTest {

    ServerMock server;
    WorldMock world;
    XPlots plugin;
    PlayerMock steve;
    PlayerMock alex;

    @BeforeEach
    void start() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
        world.loadChunk(0, 0);
        plugin = MockBukkit.load(XPlots.class);
        steve = server.addPlayer("Steve");
        alex = server.addPlayer("Alex");
    }

    @AfterEach
    void stop() {
        MockBukkit.unmock();
    }

    private Field makeField() {
        // Two 5×5 plots side by side with a 2-block path between, 7 blocks high.
        server.dispatchCommand(server.getConsoleSender(), "xplots select world 0 64 0 11 70 4");
        server.dispatchCommand(server.getConsoleSender(), "xplots field create box size:5 path:2");
        server.dispatchCommand(server.getConsoleSender(), "xplots confirm");
        await(() -> plugin.plots().field("box") != null && signAt(plugin.plots().plot("box-2")));
        return plugin.plots().field("box");
    }

    private boolean signAt(Plot plot) {
        return plot != null && Tag.STANDING_SIGNS.isTagged(world.getBlockAt(plot.signX(), plot.signY(), plot.signZ()).getType());
    }

    @Test
    void consoleMakesAFieldWithSigns() {
        Field field = makeField();
        assertEquals(2, field.plots().size());
        Plot first = plugin.plots().plot("box-1");
        assertEquals(0, first.box().minX());
        assertEquals(4, first.box().maxX());
        assertEquals(BlockFace.SOUTH, first.signFacing());
        assertTrue(signAt(first));
        assertEquals(first, plugin.plots().signAt(world.getBlockAt(first.signX(), first.signY(), first.signZ())));
    }

    @Test
    void onlyRentersAndMembersBuild() {
        Field field = makeField();
        Plot plot = plugin.plots().plot("box-1");
        assertTrue(denied(steve, world.getBlockAt(1, 65, 1)), "a free plot is nobody's");
        assertTrue(denied(steve, world.getBlockAt(6, 65, 1)), "the path is staff's");
        assertTrue(denied(steve, world.getBlockAt(plot.signX(), plot.signY(), plot.signZ())), "and so are signs");
        assertFalse(denied(steve, world.getBlockAt(20, 65, 20)), "outside the field isn't ours");

        server.dispatchCommand(server.getConsoleSender(), "xplots field set box price 0");
        assertEquals(0, field.settings(plugin.config().plots()).price());
        assertTrue(plugin.rentals().rent(steve, plot));
        assertEquals(steve.getUniqueId(), plot.lease().owner());
        assertFalse(denied(steve, world.getBlockAt(1, 65, 1)));
        assertTrue(denied(steve, world.getBlockAt(1, 71, 1)), "above the plot's height is protected");
        assertTrue(denied(alex, world.getBlockAt(1, 65, 1)));

        steve.performCommand("plots add Alex");
        assertTrue(plot.lease().members().containsKey(alex.getUniqueId()));
        assertFalse(denied(alex, world.getBlockAt(1, 65, 1)));
        steve.performCommand("plots remove Alex");
        assertTrue(denied(alex, world.getBlockAt(1, 65, 1)));

        // The default limit is one plot.
        assertFalse(plugin.rentals().rent(steve, plugin.plots().plot("box-2")));
    }

    @Test
    void clickingSignsOpensTheRightMenu() {
        makeField();
        Plot plot = plugin.plots().plot("box-1");
        Block sign = world.getBlockAt(plot.signX(), plot.signY(), plot.signZ());
        click(steve, sign);
        assertTrue(steve.getOpenInventory().getTopInventory().getHolder() instanceof Menu, "the rent menu");
        steve.closeInventory();

        server.dispatchCommand(server.getConsoleSender(), "xplots plot setowner box-1 Steve");
        assertEquals(steve.getUniqueId(), plot.lease().owner());
        assertTrue(plot.lease().expires() > System.currentTimeMillis(), "setowner on a free plot gives it a full period");
        click(steve, sign);
        assertTrue(steve.getOpenInventory().getTopInventory().getHolder() instanceof Menu, "the plot menu");
    }

    @Test
    void expiredRentFreesThePlotAndPutsItBack() {
        makeField();
        Plot plot = plugin.plots().plot("box-1");
        Material before = world.getBlockAt(2, 66, 2).getType();
        server.dispatchCommand(server.getConsoleSender(), "xplots plot setowner box-1 Steve");
        world.getBlockAt(2, 66, 2).setType(Material.DIAMOND_BLOCK);

        plot.lease().expires(System.currentTimeMillis() - 1);
        plugin.expiry().fire();
        assertNull(plot.lease());
        assertTrue(plugin.plots().owned(steve.getUniqueId()).isEmpty());
        await(() -> !plugin.resets().isBusy(plot));
        assertEquals(before, world.getBlockAt(2, 66, 2).getType(), "put back as it was made");
        assertTrue(signAt(plot), "its sign stays");
    }

    @Test
    void reloadKeepsEverything() {
        makeField();
        server.dispatchCommand(server.getConsoleSender(), "xplots plot setowner box-2 Alex");
        plugin.reload();
        assertNotNull(plugin.plots().plot("box-2").lease());
        assertTrue(denied(steve, world.getBlockAt(8, 65, 1)));
    }

    private boolean denied(Player player, Block block) {
        BlockBreakEvent event = new BlockBreakEvent(block, player);
        server.getPluginManager().callEvent(event);
        return event.isCancelled();
    }

    private void click(Player player, Block block) {
        PlayerInteractEvent event = new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, null, block, BlockFace.UP, EquipmentSlot.HAND);
        server.getPluginManager().callEvent(event);
        // Menus open on the next tick.
        server.getScheduler().performTicks(2);
    }

    private void await(BooleanSupplier condition) {
        long until = System.nanoTime() + 10_000_000_000L;
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < until, "timed out");
            server.getScheduler().performOneTick();
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        }
    }
}
