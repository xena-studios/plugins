package co.xenastudios.xrewind;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.xenastudios.core.db.Database;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/** xRewind loaded on a mock server: events in, rows out. */
class LoggingTest {

    ServerMock server;
    XRewind plugin;
    PlayerMock steve;
    WorldMock world;

    @BeforeEach
    void start() throws Exception {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
        plugin = MockBukkit.load(XRewind.class);
        await(() -> plugin.storage().database() != null);
        steve = server.addPlayer("Steve");
    }

    @AfterEach
    void stop() {
        MockBukkit.unmock();
    }

    @Test
    void breaksPlacesAndSessionsAreLogged() throws Exception {
        Block block = world.getBlockAt(5, 70, -5);
        block.setType(Material.OAK_PLANKS);
        server.getPluginManager().callEvent(new BlockBreakEvent(block, steve));
        block.setType(Material.AIR); // what the server does once the event goes through

        BlockState before = block.getState();
        block.setType(Material.GLASS);
        server.getPluginManager().callEvent(new BlockPlaceEvent(block, before, block.getRelative(0, -1, 0),
            new ItemStack(Material.GLASS), steve, true, EquipmentSlot.HAND));

        BlockBreakEvent cancelled = new BlockBreakEvent(block, steve);
        cancelled.setCancelled(true);
        server.getPluginManager().callEvent(cancelled);

        await(() -> plugin.writer().written() >= 3);
        try (Connection connection = plugin.storage().database().connect(); Statement sql = connection.createStatement()) {
            try (ResultSet rows = sql.executeQuery("SELECT b.action, o.data, n.data, u.name FROM xrewind_block b"
                + " JOIN xrewind_state o ON o.id = b.old_state JOIN xrewind_state n ON n.id = b.new_state"
                + " JOIN xrewind_user u ON u.id = b.user ORDER BY b.id")) {
                rows.next();
                assertEquals("0 minecraft:oak_planks minecraft:air Steve", row(rows));
                rows.next();
                assertEquals("1 minecraft:air minecraft:glass Steve", row(rows));
                assertTrue(!rows.next(), "the cancelled break isn't logged");
            }
            assertEquals(1, count(sql, "SELECT COUNT(*) FROM xrewind_session WHERE action = 1"), "Steve's join");
        }
    }

    @Test
    void loggingThatsOffIsntListenedFor() throws Exception {
        plugin.editConfig(yaml -> {
            yaml.set("log.block-break", false);
            return true;
        }, problem -> {});
        server.getScheduler().performTicks(2);
        Block block = world.getBlockAt(1, 70, 1);
        block.setType(Material.STONE);
        assertEquals(0, BlockBreakEvent.getHandlerList().getRegisteredListeners().length);
        server.getPluginManager().callEvent(new BlockBreakEvent(block, steve));
        Thread.sleep(700);
        Database database = plugin.storage().database();
        try (Connection connection = database.connect(); Statement sql = connection.createStatement()) {
            assertEquals(0, count(sql, "SELECT COUNT(*) FROM xrewind_block"));
        }
    }

    @Test
    void statusSaysWhereTheLogGoes() {
        steve.setOp(true);
        steve.performCommand("xr status");
        String reply = steve.nextMessage();
        assertTrue(reply != null && reply.contains("the SQLite database history.db"), reply);
    }

    private static String row(ResultSet rows) throws Exception {
        return rows.getInt(1) + " " + rows.getString(2) + " " + rows.getString(3) + " " + rows.getString(4);
    }

    private void await(BooleanSupplier done) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!done.getAsBoolean()) {
            assertTrue(System.nanoTime() < until, "timed out");
            server.getScheduler().performOneTick();
            Thread.sleep(10);
        }
    }

    private static long count(Statement sql, String query) throws Exception {
        try (ResultSet result = sql.executeQuery(query)) {
            result.next();
            return result.getLong(1);
        }
    }
}
