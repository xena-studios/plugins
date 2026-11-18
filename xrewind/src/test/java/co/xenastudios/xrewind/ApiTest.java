package co.xenastudios.xrewind;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.xenastudios.xrewind.api.Action;
import co.xenastudios.xrewind.api.Actor;
import co.xenastudios.xrewind.api.Entry;
import co.xenastudios.xrewind.api.Plan;
import co.xenastudios.xrewind.api.Query;
import co.xenastudios.xrewind.api.RollbackResult;
import co.xenastudios.xrewind.api.WriterHold;
import co.xenastudios.xrewind.api.XRewindApi;
import co.xenastudios.xrewind.api.event.LookupEvent;
import co.xenastudios.xrewind.api.event.RollbackEvent;
import co.xenastudios.xrewind.api.event.RollbackFinishedEvent;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/** The developer API on a mock server, against the SQLite database xRewind uses by default. */
class ApiTest {

    static final Actor PLOTS = Actor.source("xplots");

    ServerMock server;
    XRewind plugin;
    WorldMock world;
    PlayerMock steve;
    XRewindApi api;
    final List<Object> events = new CopyOnWriteArrayList<>();
    volatile boolean cancelRollbacks;

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
        api = XRewindApi.get();
        server.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            void on(LookupEvent event) {
                events.add(event);
            }

            @EventHandler
            void on(RollbackEvent event) {
                events.add(event);
                event.setCancelled(cancelRollbacks);
            }

            @EventHandler
            void on(RollbackFinishedEvent event) {
                events.add(event);
            }
        }, MockBukkit.createMockPlugin("Other"));
    }

    @AfterEach
    void stop() {
        MockBukkit.unmock();
    }

    Location at(int x, int y, int z) {
        return new Location(world, x, y, z);
    }

    @Test
    void registeredWhileEnabled() {
        assertSame(api, server.getServicesManager().load(XRewindApi.class));
        server.getPluginManager().disablePlugin(plugin);
        assertNull(server.getServicesManager().load(XRewindApi.class));
        assertThrows(IllegalStateException.class, XRewindApi::get);
    }

    @Test
    void actorsAreAPlayerOrASource() {
        assertEquals("#xplots", Actor.source("xplots").name());
        assertEquals("#xplots", Actor.source("#xplots").name());
        assertTrue(Actor.player(steve).isPlayer());
        assertThrows(IllegalArgumentException.class, () -> new Actor(null, "xplots"));
        assertThrows(IllegalArgumentException.class, () -> new Actor(steve.getUniqueId(), "#steve"));
    }

    @Test
    void loggedChangesCanBeLookedUp() throws Exception {
        api.logPlacement(PLOTS, at(1, 70, 1), Material.STONE.createBlockData());
        api.logRemoval(Actor.player(steve), at(2, 70, 1), Material.OAK_LOG.createBlockData());
        api.logChange(PLOTS, at(3, 70, 1), Material.GRASS_BLOCK.createBlockData(), Material.AIR.createBlockData());
        api.logContainerChange(PLOTS, at(4, 70, 1), new ItemStack(Material.DIAMOND, 64), -5);
        assertThrows(IllegalArgumentException.class,
            () -> api.logContainerChange(PLOTS, at(4, 70, 1), new ItemStack(Material.DIAMOND), 0));

        List<Entry> all = done(api.lookup(Query.builder().build()));
        assertEquals(4, all.size(), "lookups see everything logged before them");

        List<Entry> plots = done(api.lookup(Query.builder().users("#xplots").actions(Action.PLACE, Action.BREAK).build()));
        assertEquals(2, plots.size());
        Entry placed = plots.stream().filter(entry -> entry.action() == Action.PLACE).findFirst().orElseThrow();
        assertEquals("#xplots", placed.actor());
        assertEquals("minecraft:air", placed.target());
        assertEquals("minecraft:stone", placed.after());
        assertEquals(1, placed.x());
        Entry reset = plots.stream().filter(entry -> entry.action() == Action.BREAK).findFirst().orElseThrow();
        assertEquals("minecraft:grass_block[snowy=false]", reset.target(), "a change to air is a removal");

        Entry taken = done(api.lookup(Query.builder().actions(Action.REMOVE).build())).get(0);
        assertEquals("minecraft:diamond", taken.target());
        assertEquals(5, taken.amount());

        assertEquals(1, done(api.lookup(Query.builder().users("Steve").build())).size());
        assertEquals(2, done(api.lookup(Query.builder().radius(at(1, 70, 1), 1).build())).size());
        assertEquals(1, done(api.lookup(Query.builder().blocks(Material.OAK_LOG).build())).size());
        assertEquals(2, done(api.lookup(Query.builder().limit(2).build())).size());
        assertEquals(0, done(api.lookup(Query.builder().world("world_nether").build())).size());
    }

    @Test
    void lookupsAreAudited() throws Exception {
        done(api.lookup(Query.builder().users("Steve").time(Duration.ofHours(1)).build()));
        LookupEvent fromApi = (LookupEvent) events.get(0);
        assertNull(fromApi.getSender());
        assertTrue(fromApi.isAsynchronous());
        assertEquals("u:steve t:3600s r:#global", fromApi.getQuery().toString());

        steve.setOp(true);
        steve.performCommand("xr l u:Alex t:1d r:5");
        await(() -> events.size() == 2);
        LookupEvent fromCommand = (LookupEvent) events.get(1);
        assertSame(steve, fromCommand.getSender());
        assertEquals(List.of("alex"), fromCommand.getQuery().users());
        assertEquals(Query.Area.RADIUS, fromCommand.getQuery().area());
        assertEquals(5, fromCommand.getQuery().radius());
        assertEquals(1, fromCommand.getPage());

        // A sender's second lookup is ignored while their first still runs, and the event
        // fires as it starts: ask again until it's taken.
        await(() -> {
            if (events.size() < 3) {
                steve.performCommand("xr page 2");
            }
            return events.size() >= 3;
        });
        assertEquals(2, ((LookupEvent) events.get(2)).getPage(), "the page that ran");
    }

    @Test
    void rollbackRestoreAndUndo() throws Exception {
        for (int x = 0; x < 3; x++) {
            Block block = world.getBlockAt(x, 70, 10);
            block.setType(Material.OAK_PLANKS);
            server.getPluginManager().callEvent(new BlockBreakEvent(block, steve));
            block.setType(Material.AIR);
        }
        Query query = Query.builder().users("Steve").time(Duration.ofHours(1)).radius(at(0, 70, 10), 10).build();
        assertThrows(IllegalArgumentException.class, () -> api.planRollback(Query.builder().users("Steve").build()),
            "a rollback needs a time, as the command does");

        Plan plan = done(api.planRollback(query));
        assertEquals(Plan.Kind.ROLLBACK, plan.kind());
        assertEquals(3, plan.blocks().size());
        assertEquals(Material.OAK_PLANKS, plan.blocks().get(0).target().getMaterial());
        assertEquals(Material.AIR, world.getBlockAt(0, 70, 10).getType(), "a plan changes nothing");

        RollbackResult result = done(api.apply(plan));
        assertEquals(3, result.applied());
        for (int x = 0; x < 3; x++) {
            assertEquals(Material.OAK_PLANKS, world.getBlockAt(x, 70, 10).getType());
        }
        RollbackEvent before = (RollbackEvent) events.get(0);
        assertNull(before.getSender());
        RollbackFinishedEvent after = (RollbackFinishedEvent) events.get(1);
        assertEquals(3, after.getResult().applied());

        assertEquals(Plan.Kind.UNDO, result.undo().kind());
        done(api.apply(result.undo()));
        assertEquals(Material.AIR, world.getBlockAt(1, 70, 10).getType());

        // Rolled back again, then restored.
        done(api.apply(done(api.planRollback(query))));
        Plan restore = done(api.planRestore(query));
        assertEquals(Plan.Kind.RESTORE, restore.kind());
        done(api.apply(restore));
        assertEquals(Material.AIR, world.getBlockAt(2, 70, 10).getType());
    }

    @Test
    void oneRollbackAtATimeAndListenersCanCancel() throws Exception {
        Block block = world.getBlockAt(0, 70, 20);
        block.setType(Material.GOLD_BLOCK);
        server.getPluginManager().callEvent(new BlockBreakEvent(block, steve));
        block.setType(Material.AIR);
        Query query = Query.builder().users("Steve").time(Duration.ofHours(1)).build();
        Plan plan = done(api.planRollback(query));
        Plan other = done(api.planRollback(query));

        CompletableFuture<RollbackResult> first = api.apply(plan);
        assertThrows(IllegalStateException.class, () -> api.apply(plan), "a plan is applied once");
        assertInstanceOf(IllegalStateException.class, failure(api.apply(other)), "the first is still running");
        done(first);
        assertEquals(Material.GOLD_BLOCK, block.getType());
        assertThrows(IllegalStateException.class, () -> api.apply(plan), "not again once it's done either");

        block.setType(Material.AIR);
        cancelRollbacks = true;
        // The plan is given back before the future fails, so trying again from there works.
        CompletableFuture<Throwable> retried = new CompletableFuture<>();
        CompletableFuture<RollbackResult> cancelledOnce = api.apply(other);
        cancelledOnce.whenComplete((result, error) -> {
            try {
                retried.complete(failure(api.apply(other)));
            } catch (Throwable e) {
                retried.complete(e);
            }
        });
        assertInstanceOf(CancellationException.class, failure(cancelledOnce));
        assertInstanceOf(CancellationException.class, done(retried), "tried again, and cancelled again");
        assertEquals(Material.AIR, block.getType(), "a cancelled rollback changes nothing");
        RollbackEvent cancelled = (RollbackEvent) events.get(events.size() - 1);
        assertThrows(IllegalStateException.class, () -> api.apply(cancelled.getPlan()), "an event's plan can't be applied");
        cancelRollbacks = false;
        done(api.apply(other));
        assertEquals(Material.GOLD_BLOCK, block.getType(), "a plan that wasn't applied still can be, and the lock was given back");
    }

    @Test
    void aCancelledPreviewCanStillBeCancelled() throws Exception {
        steve.setOp(true);
        Block block = world.getBlockAt(2, 70, 20);
        block.setType(Material.STONE);
        server.getPluginManager().callEvent(new BlockBreakEvent(block, steve));
        block.setType(Material.AIR);
        steve.setLocation(block.getLocation());
        steve.performCommand("xr rb u:Steve t:1h #preview");
        assertTrue(message().contains("Previewing 1 blocks"));

        cancelRollbacks = true;
        steve.performCommand("xr apply");
        assertEquals(Material.AIR, block.getType(), "a listener stopped it");
        assertTrue(message().contains("Another plugin stopped it"), "and the player is told");
        steve.performCommand("xr cancel");
        assertTrue(message().contains("Preview cancelled"), "the preview is still there to cancel");

        // And an undo a listener stopped can be tried again.
        cancelRollbacks = false;
        steve.performCommand("xr rb u:Steve t:1h");
        assertTrue(message().contains("Rolled back 1 blocks"));
        cancelRollbacks = true;
        steve.performCommand("xr undo");
        assertEquals(Material.STONE, block.getType());
        assertTrue(message().contains("Another plugin stopped it"));
        cancelRollbacks = false;
        steve.performCommand("xr undo");
        await(() -> block.getType() == Material.AIR);
    }

    @Test
    void disablingFailsARollbackStillRunning() throws Exception {
        Block block = world.getBlockAt(3, 70, 20);
        block.setType(Material.STONE);
        server.getPluginManager().callEvent(new BlockBreakEvent(block, steve));
        block.setType(Material.AIR);
        Plan plan = done(api.planRollback(Query.builder().users("Steve").time(Duration.ofHours(1)).build()));
        WriterHold hold = done(api.pause());
        CompletableFuture<RollbackResult> running = api.apply(plan);
        server.getPluginManager().disablePlugin(plugin);
        assertTrue(running.isDone());
        assertInstanceOf(IllegalStateException.class, failure(running));
        assertFalse(hold.isHeld(), "a hold doesn't outlive the writer");

        // The same instance enabled again: writes go through and a rollback can run.
        server.getPluginManager().enablePlugin(plugin);
        await(() -> plugin.storage().database() != null);
        XRewindApi again = XRewindApi.get();
        long written = plugin.writer().written();
        again.logPlacement(PLOTS, at(3, 71, 20), Material.STONE.createBlockData());
        assertTrue(done(again.flush()));
        assertTrue(plugin.writer().written() > written);
        Plan fresh = done(again.planRollback(Query.builder().users("Steve").time(Duration.ofHours(1)).build()));
        assertEquals(1, done(again.apply(fresh)).applied(), "the rollback that was running when it stopped isn't still holding the lock");
    }

    @Test
    void commandRollbacksFireTheEventsToo() throws Exception {
        steve.setOp(true);
        Block block = world.getBlockAt(1, 70, 20);
        block.setType(Material.STONE);
        server.getPluginManager().callEvent(new BlockBreakEvent(block, steve));
        block.setType(Material.AIR);
        steve.setLocation(block.getLocation());
        steve.performCommand("xr rb u:Steve t:1h");
        await(() -> events.size() == 2);
        assertSame(steve, ((RollbackEvent) events.get(0)).getSender());
        RollbackFinishedEvent finished = (RollbackFinishedEvent) events.get(1);
        assertSame(steve, finished.getSender());
        assertThrows(IllegalStateException.class, () -> api.apply(finished.getResult().undo()),
            "a command's undo is the sender's /xr undo, not a plugin's to apply as well");
    }

    @Test
    void pauseHoldsTheWriterUntilEveryHoldIsLetGo() throws Exception {
        assertTrue(done(api.flush()));
        long written = plugin.writer().written();
        WriterHold first = done(api.pause());
        CompletableFuture<WriterHold> asked = api.pause();
        String thread = done(asked.thenApply(hold -> Thread.currentThread().getName()));
        assertFalse(thread.equals("xRewind writer"), "completed on the background tasks, not the writer's thread");
        WriterHold second = done(asked);
        assertTrue(first.isHeld());
        api.logPlacement(PLOTS, at(9, 70, 9), Material.STONE.createBlockData());
        Thread.sleep(1_200);
        assertEquals(written, plugin.writer().written(), "nothing written while held");
        assertEquals(1, plugin.queue().size(), "but kept");

        first.release();
        assertFalse(first.isHeld());
        Thread.sleep(700);
        assertEquals(written, plugin.writer().written(), "one plugin letting go doesn't end another's hold");
        second.close();
        await(() -> plugin.writer().written() == written + 1);
    }

    @Test
    void aPauseGivenUpOnLetsTheHoldGo() throws Exception {
        assertTrue(done(api.flush()), "Steve's join, from the setup, is written");
        // A write running, so the holds wait.
        plugin.writer().gate().enterAnyway();
        CompletableFuture<WriterHold> cancelled = api.pause();
        CompletableFuture<WriterHold> timedOut = api.pause().orTimeout(50, TimeUnit.MILLISECONDS);
        assertTrue(plugin.writer().gate().closed());
        cancelled.cancel(false);
        assertInstanceOf(java.util.concurrent.TimeoutException.class, failure(timedOut));
        // Nothing would ever let them go, so they went.
        await(() -> !plugin.writer().gate().closed());
        plugin.writer().gate().exit();
        long written = plugin.writer().written();
        api.logPlacement(PLOTS, at(9, 71, 9), Material.STONE.createBlockData());
        assertTrue(done(api.flush()), "writes go on");
        assertTrue(plugin.writer().written() > written);
    }

    @Test
    void flushIsTrueOnABusyServer() throws Exception {
        AtomicBoolean logging = new AtomicBoolean(true);
        Thread busy = new Thread(() -> {
            int i = 0;
            while (logging.get()) {
                api.logPlacement(PLOTS, at(i++ % 50, 80, 5), Material.STONE.createBlockData());
                LockSupport.parkNanos(TimeUnit.MICROSECONDS.toNanos(200));
            }
        });
        busy.start();
        try {
            Thread.sleep(200);
            assertTrue(done(api.flush()), "what was logged before is written, though more keeps coming");
        } finally {
            logging.set(false);
            busy.join();
        }
    }

    /** Steve's next chat message, as plain text. */
    String message() throws InterruptedException {
        String[] next = new String[1];
        await(() -> {
            var component = steve.nextComponentMessage();
            next[0] = component == null ? null : PlainTextComponentSerializer.plainText().serialize(component);
            return next[0] != null;
        });
        return next[0];
    }

    <T> T done(CompletableFuture<T> future) throws InterruptedException {
        await(future::isDone);
        return future.join();
    }

    Throwable failure(CompletableFuture<?> future) throws InterruptedException {
        await(future::isDone);
        // join() throws a CancellationException as it is, anything else wrapped.
        RuntimeException thrown = assertThrows(RuntimeException.class, future::join);
        return thrown instanceof CompletionException wrapped ? wrapped.getCause() : thrown;
    }

    void await(BooleanSupplier done) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!done.getAsBoolean()) {
            assertTrue(System.nanoTime() < until, "timed out");
            server.getScheduler().performOneTick();
            Thread.sleep(10);
        }
    }
}
