package co.xenastudios.xrewind.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.xenastudios.core.db.Database;
import co.xenastudios.core.db.StorageSettings;
import co.xenastudios.xrewind.storage.Schema;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

/** The writer against a real SQLite file. */
class WriterTest {

    @TempDir
    Path folder;

    private final Logger logger = Logger.getLogger("xRewind test");
    private Database database;
    private BlockData stone;
    private BlockData air;

    @BeforeEach
    void start() throws Exception {
        MockBukkit.mock();
        stone = Material.STONE.createBlockData();
        air = Material.AIR.createBlockData();
        database = Database.open(StorageSettings.defaults("xr_"), folder.resolve("history.db"));
        database.migrate(Schema.STEPS);
    }

    @AfterEach
    void stop() {
        MockBukkit.unmock();
    }

    private Entry.BlockChange broke(Actor actor, int x) {
        return new Entry.BlockChange(10, actor, "world", x, 64, -x, Actions.BREAK, stone, air);
    }

    @Test
    void entriesAreWrittenWithTheirNamesOnce() throws Exception {
        LogQueue queue = new LogQueue(logger);
        Writer writer = new Writer(queue, () -> database, logger);
        Actor steve = new Actor(UUID.randomUUID(), "Steve");
        for (int i = 0; i < 25_000; i++) {
            queue.add(broke(i % 2 == 0 ? steve : Actor.source("tnt"), i));
        }
        queue.add(new Entry.Session(11, steve, "world_nether", 1, 2, 3, Actions.JOIN));
        writer.start();
        await(() -> writer.written() == 25_001);
        writer.stop(5, TimeUnit.SECONDS);

        try (Connection connection = database.connect(); Statement sql = connection.createStatement()) {
            assertEquals(25_000, count(sql, "SELECT COUNT(*) FROM xr_block"));
            assertEquals(1, count(sql, "SELECT COUNT(*) FROM xr_session"));
            assertEquals(2, count(sql, "SELECT COUNT(*) FROM xr_user"), "Steve and #tnt, once each");
            assertEquals(2, count(sql, "SELECT COUNT(*) FROM xr_world"));
            assertEquals(2, count(sql, "SELECT COUNT(*) FROM xr_state"), "stone and air");
            assertEquals(0, count(sql, "SELECT COUNT(*) FROM xr_block b LEFT JOIN xr_state s ON s.id = b.old_state WHERE s.id IS NULL"),
                "every row's state is there");
            try (ResultSet row = sql.executeQuery("SELECT u.name, s.data, b.x, b.z FROM xr_block b JOIN xr_user u ON u.id = b.user"
                + " JOIN xr_state s ON s.id = b.old_state WHERE b.x = 1")) {
                row.next();
                assertEquals("#tnt", row.getString(1));
                assertEquals("minecraft:stone", row.getString(2));
                assertEquals(-1, row.getInt(4));
            }
        }
    }

    @Test
    void entriesWaitUntilTheDatabaseIsReady() throws Exception {
        LogQueue queue = new LogQueue(logger);
        AtomicReference<Database> ready = new AtomicReference<>();
        Writer writer = new Writer(queue, ready::get, logger);
        writer.start();
        queue.add(broke(new Actor(UUID.randomUUID(), "Alex"), 1));
        Thread.sleep(300);
        assertEquals(1, queue.size(), "nothing to write to yet, and nothing lost");
        ready.set(database);
        await(() -> writer.written() == 1);
        writer.stop(5, TimeUnit.SECONDS);
    }

    @Test
    void aRenamedPlayerKeepsTheirIdAndShowsTheNewName() throws Exception {
        UUID id = UUID.randomUUID();
        for (String name : new String[] {"Old", "New"}) {
            LogQueue queue = new LogQueue(logger);
            Writer writer = new Writer(queue, () -> database, logger);
            queue.add(broke(new Actor(id, name), 1));
            writer.start();
            await(() -> writer.written() == 1);
            writer.stop(5, TimeUnit.SECONDS);
        }
        try (Connection connection = database.connect(); Statement sql = connection.createStatement()) {
            assertEquals(1, count(sql, "SELECT COUNT(*) FROM xr_user"));
            try (ResultSet row = sql.executeQuery("SELECT name FROM xr_user")) {
                row.next();
                assertEquals("New", row.getString(1));
            }
        }
    }

    @Test
    void oneRowTheDatabaseWontTakeIsDroppedAndTheRestGetIn() throws Exception {
        LogQueue queue = new LogQueue(logger);
        Writer writer = new Writer(queue, () -> database, logger);
        Actor steve = new Actor(UUID.randomUUID(), "Steve");
        for (int i = 0; i < 50; i++) {
            queue.add(broke(steve, i));
        }
        // An update of a table that isn't there fails every time, as a bad row would.
        queue.add(new Entry.MarkRolledBack("no_such_table", new long[] {1}, true));
        for (int i = 50; i < 100; i++) {
            queue.add(broke(steve, i));
        }
        writer.start();
        await(() -> writer.written() == 100);
        writer.stop(5, TimeUnit.SECONDS);
        assertEquals(1, writer.dropped());
        try (Connection connection = database.connect(); Statement sql = connection.createStatement()) {
            assertEquals(100, count(sql, "SELECT COUNT(*) FROM xr_block"));
        }
    }

    @Test
    void stoppingWritesWhatsLeft() throws Exception {
        LogQueue queue = new LogQueue(logger);
        Writer writer = new Writer(queue, () -> database, logger);
        writer.start();
        for (int i = 0; i < 100; i++) {
            queue.add(broke(Actor.source("fire"), i));
        }
        writer.stop(5, TimeUnit.SECONDS);
        assertEquals(100, writer.written());
    }

    @Test
    void stoppingWithAHoldOpenStillWritesWhatsLeft() throws Exception {
        LogQueue queue = new LogQueue(logger);
        Writer writer = new Writer(queue, () -> database, logger);
        writer.start();
        WriteGate.Hold hold = writer.gate().hold(TimeUnit.MINUTES.toNanos(1));
        await(hold.ready()::isDone);
        for (int i = 0; i < 100; i++) {
            queue.add(broke(Actor.source("fire"), i));
        }
        Thread.sleep(600);
        assertEquals(0, writer.written(), "held");
        writer.stop(5, TimeUnit.SECONDS);
        assertEquals(100, writer.written(), "the last writes don't wait for a backup");
        assertFalse(hold.held(), "and the hold is gone, so xRewind enabled again starts open");

        queue.add(broke(Actor.source("fire"), 100));
        writer.start();
        await(() -> writer.written() == 101);
        writer.stop(5, TimeUnit.SECONDS);
    }

    /**
     * Entries the writer has taken off the queue but not written yet still count: flush is
     * never true while one logged before it is unwritten, however the two line up.
     */
    @Test
    void flushIsNeverTrueEarlyWhileTheWriterDrainsTheQueue() throws Exception {
        LogQueue queue = new LogQueue(logger);
        Writer writer = new Writer(queue, () -> database, logger);
        writer.start();
        AtomicLong added = new AtomicLong();
        AtomicBoolean logging = new AtomicBoolean(true);
        Actor steve = new Actor(UUID.randomUUID(), "Steve");
        Thread busy = new Thread(() -> {
            int i = 0;
            while (logging.get()) {
                for (int n = 0; n < 1_000; n++) {
                    queue.add(broke(steve, i++));
                    added.incrementAndGet();
                }
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
            }
        });
        busy.start();
        try {
            long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1_500);
            int flushes = 0;
            while (System.nanoTime() < until) {
                long before = added.get();
                assertTrue(writer.flush(10_000));
                assertTrue(writer.written() >= before, "flush was true with " + (before - writer.written()) + " unwritten");
                flushes++;
            }
            assertTrue(flushes > 5);
        } finally {
            logging.set(false);
            busy.join();
        }
        assertEquals(0, queue.dropped());
        writer.stop(10, TimeUnit.SECONDS);
    }

    /**
     * A batch written row by row, after a row the database won't take: a backup asking to
     * hold writes gets them between rows, and flush counts the rows still to go.
     */
    @Test
    void aBatchWrittenRowByRowStopsForAHoldAndFlushWaitsForTheRest() throws Exception {
        LogQueue queue = new LogQueue(logger);
        Writer writer = new Writer(queue, () -> database, logger);
        AtomicReference<WriteGate.Hold> hold = new AtomicReference<>();
        // Asked for on the writer's own thread, in the middle of the batch, as the row goes.
        Handler midBatch = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getMessage().startsWith("Dropped one logged change") && hold.get() == null) {
                    hold.set(writer.gate().hold(TimeUnit.MINUTES.toNanos(1)));
                }
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        logger.addHandler(midBatch);
        try {
            queue.add(new Entry.MarkRolledBack("no_such_table", new long[] {1}, true));
            Actor steve = new Actor(UUID.randomUUID(), "Steve");
            for (int i = 0; i < 99; i++) {
                queue.add(broke(steve, i));
            }
            writer.start();
            await(() -> hold.get() != null && hold.get().ready().isDone());
            assertFalse(hold.get().ready().isCompletedExceptionally());
            assertEquals(1, writer.dropped());
            assertEquals(0, writer.written(), "the rest of the batch waits for the hold");
            assertFalse(writer.flush(300), "and flush counts it");

            CompletableFuture<Boolean> flushed = CompletableFuture.supplyAsync(() -> writer.flush(20_000));
            Thread.sleep(50);
            hold.get().release();
            assertTrue(flushed.get(25, TimeUnit.SECONDS));
            assertEquals(99, writer.written(), "true only once every row is written");
        } finally {
            logger.removeHandler(midBatch);
            writer.stop(5, TimeUnit.SECONDS);
        }
    }

    /**
     * What a listener costs: making the entry and queueing it. The budget is 2 µs; this
     * is usually well under 0.2. The limit here is loose so a busy CI machine can't fail it.
     */
    @Test
    void queueingAnEntryIsCheap() {
        LogQueue queue = new LogQueue(logger);
        Actor steve = new Actor(UUID.randomUUID(), "Steve");
        int rounds = 200_000;
        for (int warm = 0; warm < 3; warm++) {
            for (int i = 0; i < rounds; i++) {
                queue.add(broke(steve, i));
                queue.poll();
            }
        }
        long start = System.nanoTime();
        for (int i = 0; i < rounds; i++) {
            queue.add(broke(steve, i));
            queue.poll();
        }
        double nanos = (System.nanoTime() - start) / (double) rounds;
        System.out.printf("xRewind: queueing an entry takes %.0f ns%n", nanos);
        assertTrue(nanos < 2_000, "queueing took " + nanos + " ns");
    }

    private static void await(BooleanSupplier done) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!done.getAsBoolean()) {
            assertTrue(System.nanoTime() < until, "timed out");
            Thread.sleep(20);
        }
    }

    private static long count(Statement sql, String query) throws Exception {
        try (ResultSet result = sql.executeQuery(query)) {
            result.next();
            return result.getLong(1);
        }
    }
}
