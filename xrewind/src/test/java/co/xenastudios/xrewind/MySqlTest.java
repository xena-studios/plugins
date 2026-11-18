package co.xenastudios.xrewind;

import static org.junit.jupiter.api.Assertions.assertEquals;

import co.xenastudios.core.db.Database;
import co.xenastudios.core.db.StorageSettings;
import co.xenastudios.xrewind.log.Actions;
import co.xenastudios.xrewind.log.Actor;
import co.xenastudios.xrewind.log.Entry;
import co.xenastudios.xrewind.log.LogQueue;
import co.xenastudios.xrewind.log.Writer;
import co.xenastudios.xrewind.maintain.Purge;
import co.xenastudios.xrewind.query.ApiTypes;
import co.xenastudios.xrewind.query.Lookup;
import co.xenastudios.xrewind.query.Params;
import co.xenastudios.xrewind.storage.Clock;
import co.xenastudios.xrewind.storage.Schema;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockbukkit.mockbukkit.MockBukkit;

/**
 * The same SQL on MySQL or MariaDB. Runs only with XR_MYSQL=host:port:database:user:password,
 * such as against a throwaway container:
 * {@code docker run -d -p 3307:3306 -e MARIADB_ROOT_PASSWORD=pw -e MARIADB_DATABASE=xr mariadb}.
 */
@EnabledIfEnvironmentVariable(named = "XR_MYSQL", matches = ".+")
class MySqlTest {

    Database database;
    final Logger logger = Logger.getLogger("test");

    @BeforeEach
    void start() throws Exception {
        MockBukkit.mock();
        String[] parts = System.getenv("XR_MYSQL").split(":");
        String prefix = "t" + Long.toHexString(System.nanoTime()) + "_";
        database = Database.open(new StorageSettings(StorageSettings.Type.MYSQL, prefix,
            new StorageSettings.MySql(parts[0], Integer.parseInt(parts[1]), parts[2], parts[3], parts[4], "")), Path.of("unused"));
        assertEquals(Schema.STEPS.size(), database.migrate(Schema.STEPS));
        assertEquals(Schema.STEPS.size(), database.migrate(Schema.STEPS), "a second start changes nothing");
    }

    @AfterEach
    void stop() {
        MockBukkit.unmock();
    }

    @Test
    void writeLookUpMarkAndPurge() throws Exception {
        Actor steve = new Actor(UUID.randomUUID(), "Steve");
        int now = Clock.now();
        LogQueue queue = new LogQueue(logger);
        Writer writer = new Writer(queue, () -> database, logger);
        for (int i = 0; i < 2_500; i++) {
            queue.add(new Entry.BlockChange(now - (i % 2) * 40 * 86_400, i % 3 == 0 ? Actor.source("tnt") : steve, "world",
                i, 64, 0, Actions.BREAK, Material.OAK_LOG.createBlockData(), Material.AIR.createBlockData()));
        }
        queue.add(new Entry.Kill(now, steve, "world", 0, 64, 0, "minecraft:zombie"));
        queue.add(new Entry.Text(now, steve, "world", 0, 64, 0, Actions.CHAT, "hello ünïcode 🙂"));
        queue.add(new Entry.Session(now, steve, "world", 0, 64, 0, Actions.JOIN));
        queue.add(new Entry.Text(now, steve, "world", 5, 64, 5, Actions.SIGN, "❤ love | 𝓯𝓪𝓷𝓬𝔂"));
        writer.start();
        writer.stop(30, TimeUnit.SECONDS);
        assertEquals(2_504, writer.written());

        Lookup.Center spawn = new Lookup.Center("world", 0, 64, 0);
        assertEquals(1_250, Lookup.page(database, Params.parse(List.of("t:1d", "i:oak_log")), spawn, 1, 7).total());
        assertEquals(834, Lookup.page(database, Params.parse(List.of("u:#tnt")), spawn, 1, 7).total());
        assertEquals("hello ünïcode 🙂", Lookup.page(database, Params.parse(List.of("a:chat")), spawn, 1, 7).rows().getFirst().thing());

        long[] ids = Lookup.allBlocks(database, Params.parse(List.of("t:1d", "r:#global")), spawn, false).stream()
            .mapToLong(co.xenastudios.xrewind.query.Row::id).toArray();
        LogQueue marks = new LogQueue(logger);
        Writer marker = new Writer(marks, () -> database, logger);
        marks.add(new Entry.MarkRolledBack("block", ids, true));
        marker.start();
        marker.stop(30, TimeUnit.SECONDS);
        assertEquals(1_250, count("SELECT COUNT(*) FROM " + database.table("block") + " WHERE rolled_back = 1"));

        assertEquals(1_250, Purge.run(database, Purge.daysAgo(30)));
        assertEquals(1_250, count("SELECT COUNT(*) FROM " + database.table("block")));
    }

    /** The API's queries and entries, through the same SQL, on MySQL. */
    @Test
    void theApisQueriesWork() throws Exception {
        int now = Clock.now();
        LogQueue queue = new LogQueue(logger);
        Writer writer = new Writer(queue, () -> database, logger);
        queue.add(new Entry.BlockChange(now, Actor.source("xplots"), "world", 10, 64, 10, Actions.BREAK,
            Material.GRASS_BLOCK.createBlockData(), Material.AIR.createBlockData()));
        queue.add(new Entry.BlockChange(now, new Actor(UUID.randomUUID(), "Steve"), "world", 500, 64, 500, Actions.PLACE,
            Material.AIR.createBlockData(), Material.STONE.createBlockData()));
        writer.start();
        writer.stop(30, TimeUnit.SECONDS);

        co.xenastudios.xrewind.api.Query nearby = co.xenastudios.xrewind.api.Query.builder()
            .users("#xplots").time(java.time.Duration.ofHours(1)).radius("world", 10, 64, 10, 5).build();
        List<co.xenastudios.xrewind.api.Entry> found = Lookup.page(database, ApiTypes.query(nearby), ApiTypes.center(nearby), 1, 100)
            .rows().stream().map(ApiTypes::entry).toList();
        assertEquals(1, found.size());
        assertEquals("#xplots", found.getFirst().actor());
        assertEquals(co.xenastudios.xrewind.api.Action.BREAK, found.getFirst().action());

        co.xenastudios.xrewind.api.Query everywhere = co.xenastudios.xrewind.api.Query.builder().world("world").build();
        assertEquals(2, Lookup.page(database, ApiTypes.query(everywhere), ApiTypes.center(everywhere), 1, 100).total());
    }

    private long count(String query) throws Exception {
        try (Connection connection = database.connect(); Statement sql = connection.createStatement();
             ResultSet result = sql.executeQuery(query)) {
            result.next();
            return result.getLong(1);
        }
    }
}
