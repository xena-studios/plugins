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
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
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
        // The mock server can't load chunks in the background, as rollbacks ask for them.
        world.loadChunk(0, 0);
        world.loadChunk(0, 1);
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
        String reply = plain(steve.nextMessage());
        assertTrue(reply != null && reply.contains("the SQLite database history.db"), reply);
    }

    @Test
    void theInspectorShowsABlocksHistoryAndChangesNothing() throws Exception {
        steve.setOp(true);
        Block block = world.getBlockAt(2, 70, 2);
        block.setType(Material.STONE);
        server.getPluginManager().callEvent(new BlockBreakEvent(block, steve));
        block.setType(Material.AIR);

        steve.performCommand("xr i");
        String on = plain(steve.nextMessage());
        assertTrue(on != null && on.contains("Inspector on"), on);
        PlayerInteractEvent click = new PlayerInteractEvent(steve, Action.LEFT_CLICK_BLOCK, null, block, BlockFace.UP, EquipmentSlot.HAND);
        server.getPluginManager().callEvent(click);
        assertTrue(click.useInteractedBlock() == org.bukkit.event.Event.Result.DENY, "the click is only a question");

        String reply = awaitMessage(steve);
        assertTrue(reply.contains("History of 2 70 2"), reply);
        String line = awaitMessage(steve);
        assertTrue(line.contains("Steve broke stone at 2 70 2"), line);

        steve.performCommand("xr i");
        assertTrue(plain(steve.nextMessage()).contains("Inspector off"));
        assertEquals(0, PlayerInteractEvent.getHandlerList().getRegisteredListeners().length, "no listener once nobody inspects");
    }

    @Test
    void lookupFindsAndExplainsMistakes() throws Exception {
        steve.setOp(true);
        Block block = world.getBlockAt(3, 70, 3);
        block.setType(Material.DIRT);
        server.getPluginManager().callEvent(new BlockBreakEvent(block, steve));
        steve.performCommand("xr l u:Steve t:1h");
        String header = awaitMessage(steve);
        assertTrue(header.contains("Lookup: 1 changes"), header);
        assertTrue(awaitMessage(steve).contains("broke dirt"));
        steve.performCommand("xr l t:soon");
        assertTrue(plain(steve.nextMessage()).contains("isn't a time"));
    }

    @Test
    void rollbackRestoreAndUndo() throws Exception {
        steve.setOp(true);
        for (int x = 0; x < 3; x++) {
            Block block = world.getBlockAt(x, 70, 10);
            block.setType(Material.OAK_PLANKS);
            server.getPluginManager().callEvent(new BlockBreakEvent(block, steve));
            block.setType(Material.AIR);
        }
        steve.setLocation(world.getBlockAt(0, 70, 10).getLocation());

        steve.performCommand("xr rb u:Steve");
        assertTrue(plain(steve.nextMessage()).contains("Add a time"), "a time is required");

        steve.performCommand("xr rb u:Steve t:1h");
        assertTrue(awaitMessage(steve).contains("Rolled back 3 blocks"));
        for (int x = 0; x < 3; x++) {
            assertEquals(Material.OAK_PLANKS, world.getBlockAt(x, 70, 10).getType());
        }
        await(() -> rolledBack() == 3);

        steve.performCommand("xr undo");
        assertTrue(awaitMessage(steve).contains("Undid 3 blocks"));
        assertEquals(Material.AIR, world.getBlockAt(1, 70, 10).getType());
        await(() -> rolledBack() == 0);

        steve.performCommand("xr rb u:Steve t:1h");
        awaitMessage(steve);
        steve.performCommand("xr rs u:Steve t:1h");
        assertTrue(awaitMessage(steve).contains("Restored 3 blocks"));
        assertEquals(Material.AIR, world.getBlockAt(2, 70, 10).getType());
    }

    @Test
    void aPreviewChangesNothingUntilApplied() throws Exception {
        steve.setOp(true);
        Block block = world.getBlockAt(0, 70, 20);
        block.setType(Material.GOLD_BLOCK);
        server.getPluginManager().callEvent(new BlockBreakEvent(block, steve));
        block.setType(Material.AIR);
        steve.setLocation(block.getLocation());

        steve.performCommand("xr rb u:Steve t:1h #preview");
        assertTrue(awaitMessage(steve).contains("Previewing 1 blocks"));
        assertEquals(Material.AIR, block.getType(), "only shown, not changed");
        steve.performCommand("xr apply");
        assertTrue(awaitMessage(steve).contains("Rolled back 1 blocks"));
        assertEquals(Material.GOLD_BLOCK, block.getType());
    }

    private long rolledBack() {
        try (Connection connection = plugin.storage().database().connect(); Statement sql = connection.createStatement()) {
            return count(sql, "SELECT COUNT(*) FROM xrewind_block WHERE rolled_back = 1");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String awaitMessage(PlayerMock player) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        String message;
        while ((message = player.nextMessage()) == null) {
            assertTrue(System.nanoTime() < until, "no reply");
            server.getScheduler().performOneTick();
            Thread.sleep(10);
        }
        return plain(message);
    }

    private static String plain(String message) {
        return message == null ? null : net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
            .serialize(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().deserialize(message));
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
