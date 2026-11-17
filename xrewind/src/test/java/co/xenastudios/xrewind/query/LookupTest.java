package co.xenastudios.xrewind.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.xenastudios.core.db.Database;
import co.xenastudios.core.db.StorageSettings;
import co.xenastudios.xrewind.log.Actions;
import co.xenastudios.xrewind.log.Actor;
import co.xenastudios.xrewind.log.Entry;
import co.xenastudios.xrewind.log.LogQueue;
import co.xenastudios.xrewind.log.Writer;
import co.xenastudios.xrewind.storage.Clock;
import co.xenastudios.xrewind.storage.Schema;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

/** Lookups against a real SQLite file, filled through the writer. */
class LookupTest {

    @TempDir
    Path folder;

    Database database;
    final Lookup.Center spawn = new Lookup.Center("world", 0, 64, 0);

    @BeforeEach
    void start() throws Exception {
        MockBukkit.mock();
        database = Database.open(StorageSettings.defaults("xr_"), folder.resolve("history.db"));
        database.migrate(Schema.STEPS);
        BlockData stone = Material.STONE.createBlockData();
        BlockData log = Material.OAK_LOG.createBlockData();
        BlockData air = Material.AIR.createBlockData();
        Actor steve = new Actor(UUID.randomUUID(), "Steve");
        Actor alex = new Actor(UUID.randomUUID(), "Alex");
        int now = Clock.now();
        LogQueue queue = new LogQueue(Logger.getLogger("test"));
        // Steve breaks stone near spawn; Alex places oak logs far away; #tnt, a day ago.
        for (int i = 0; i < 10; i++) {
            queue.add(new Entry.BlockChange(now - i, steve, "world", i, 64, 0, Actions.BREAK, stone, air));
        }
        for (int i = 0; i < 3; i++) {
            queue.add(new Entry.BlockChange(now, alex, "world", 500 + i, 64, 0, Actions.PLACE, air, log));
        }
        queue.add(new Entry.BlockChange(now - 86_400, Actor.source("tnt"), "world", 1, 64, 1, Actions.BREAK, stone, air));
        queue.add(new Entry.BlockChange(now, steve, "world_nether", 0, 64, 0, Actions.PLACE, air, stone));
        queue.add(new Entry.Session(now, alex, "world", 0, 64, 0, Actions.JOIN));
        Writer writer = new Writer(queue, () -> database, Logger.getLogger("test"));
        writer.start();
        writer.stop(10, TimeUnit.SECONDS);
        assertEquals(16, writer.written());
    }

    @AfterEach
    void stop() {
        MockBukkit.unmock();
    }

    private Lookup.Result look(String... params) throws Exception {
        return Lookup.page(database, Params.parse(List.of(params)), spawn, 1, 100);
    }

    @Test
    void filtersCombine() throws Exception {
        assertEquals(14, look().total(), "the sender's world, every block change");
        assertEquals(15, look("r:#global").total(), "every world");
        assertEquals(10, look("u:steve").total());
        assertEquals(11, look("r:20").total(), "near spawn: Steve's and the TNT");
        assertEquals(10, look("r:20", "t:1h").total(), "and recent");
        assertEquals(1, look("u:#tnt").total());
        assertEquals(3, look("a:+block").total());
        assertEquals(3, look("i:oak_log").total(), "the block placed");
        assertEquals(11, look("e:oak_log").total());
        assertEquals(11, look("e:alex").total());
        assertEquals(1, look("a:session").total());
    }

    @Test
    void newestFirstWithPages() throws Exception {
        Query steve = Params.parse(List.of("u:steve"));
        Lookup.Result first = Lookup.page(database, steve, spawn, 1, 4);
        Lookup.Result third = Lookup.page(database, steve, spawn, 3, 4);
        assertEquals(4, first.rows().size());
        assertEquals(0, first.rows().getFirst().x(), "the newest");
        assertEquals(2, third.rows().size(), "10 in pages of 4");
        assertEquals("minecraft:stone", first.rows().getFirst().before());
        assertEquals("stone", Formats.name(first.rows().getFirst().before()));
    }

    @Test
    void oneBlocksHistory() throws Exception {
        Lookup.Result result = Lookup.page(database, Lookups.at(), new Lookup.Center("world", 501, 64, 0), 1, 7);
        assertEquals(1, result.total());
        assertEquals("Alex", result.rows().getFirst().user());
        assertTrue(Formats.name(result.rows().getFirst().after()).equals("oak_log"));
    }
}
