package co.xenastudios.xrewind.maintain;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

/** Purges, and importing a CoreProtect database made with CoreProtect's own SQLite tables. */
class UpkeepTest {

    @TempDir
    Path folder;

    Database database;
    final Logger logger = Logger.getLogger("test");

    @BeforeEach
    void start() throws Exception {
        MockBukkit.mock();
        database = Database.open(StorageSettings.defaults("xr_"), folder.resolve("xRewind/history.db"));
        database.migrate(Schema.STEPS);
    }

    @AfterEach
    void stop() {
        MockBukkit.unmock();
    }

    private void write(Entry... entries) {
        LogQueue queue = new LogQueue(logger);
        Writer writer = new Writer(queue, () -> database, logger);
        for (Entry entry : entries) {
            queue.add(entry);
        }
        writer.start();
        writer.stop(10, TimeUnit.SECONDS);
    }

    @Test
    void aPurgeDeletesOnlyWhatsOlder() throws Exception {
        Actor steve = new Actor(UUID.randomUUID(), "Steve");
        int now = Clock.now();
        write(new Entry.BlockChange(now - 40 * 86_400, steve, "world", 0, 64, 0, Actions.BREAK,
                Material.STONE.createBlockData(), Material.AIR.createBlockData()),
            new Entry.BlockChange(now, steve, "world", 1, 64, 0, Actions.BREAK,
                Material.STONE.createBlockData(), Material.AIR.createBlockData()),
            new Entry.Session(now - 40 * 86_400, steve, "world", 0, 64, 0, Actions.JOIN));
        assertEquals(2, Purge.run(database, Purge.daysAgo(30)));
        assertEquals(1, count("SELECT COUNT(*) FROM xr_block"));
        assertEquals(0, count("SELECT COUNT(*) FROM xr_session"));
    }

    @Test
    void coreProtectsHistoryComesAcross() throws Exception {
        Path coreProtect = folder.resolve("CoreProtect");
        Files.createDirectories(coreProtect);
        Files.writeString(coreProtect.resolve("config.yml"), "use-mysql: false\ntable-prefix: co_\n");
        long now = System.currentTimeMillis() / 1000;
        try (Connection cp = DriverManager.getConnection("jdbc:sqlite:" + coreProtect.resolve("database.db"));
             Statement sql = cp.createStatement()) {
            // CoreProtect's own SQLite tables, as its Database.java makes them.
            sql.execute("CREATE TABLE co_user (id INTEGER PRIMARY KEY ASC, time INTEGER, user TEXT, uuid TEXT)");
            sql.execute("CREATE TABLE co_world (id INTEGER, world TEXT)");
            sql.execute("CREATE TABLE co_material_map (id INTEGER, material TEXT)");
            sql.execute("CREATE TABLE co_blockdata_map (id INTEGER, data TEXT)");
            sql.execute("CREATE TABLE co_entity_map (id INTEGER, entity TEXT)");
            sql.execute("CREATE TABLE co_block (time INTEGER, user INTEGER, wid INTEGER, x INTEGER, y INTEGER, z INTEGER, type INTEGER,"
                + " data INTEGER, meta BLOB, blockdata BLOB, action INTEGER, rolled_back INTEGER)");
            sql.execute("CREATE TABLE co_container (time INTEGER, user INTEGER, wid INTEGER, x INTEGER, y INTEGER, z INTEGER,"
                + " type INTEGER, data INTEGER, amount INTEGER, metadata BLOB, action INTEGER, rolled_back INTEGER)");
            sql.execute("CREATE TABLE co_session (time INTEGER, user INTEGER, wid INTEGER, x INTEGER, y INTEGER, z INTEGER, action INTEGER)");
            sql.execute("CREATE TABLE co_sign (time INTEGER, user INTEGER, wid INTEGER, x INTEGER, y INTEGER, z INTEGER, action INTEGER,"
                + " color INTEGER, color_secondary INTEGER, data INTEGER, waxed INTEGER, face INTEGER, line_1 TEXT, line_2 TEXT,"
                + " line_3 TEXT, line_4 TEXT, line_5 TEXT, line_6 TEXT, line_7 TEXT, line_8 TEXT, rolled_back INTEGER DEFAULT 0)");
            sql.execute("CREATE TABLE co_chat (time INTEGER, user INTEGER, wid INTEGER, x INTEGER, y INTEGER, z INTEGER, message TEXT)");
            sql.execute("CREATE TABLE co_command (time INTEGER, user INTEGER, wid INTEGER, x INTEGER, y INTEGER, z INTEGER, message TEXT)");
            sql.execute("INSERT INTO co_user (id, time, user, uuid) VALUES (1, 0, 'Notch', '069a79f4-44e9-4726-a5be-fca90e38aaf5'), (2, 0, '#tnt', NULL)");
            sql.execute("INSERT INTO co_world VALUES (1, 'world')");
            sql.execute("INSERT INTO co_material_map VALUES (1, 'minecraft:oak_door'), (2, 'minecraft:stone'), (3, 'minecraft:diamond')");
            sql.execute("INSERT INTO co_blockdata_map VALUES (1, 'facing=north'), (2, 'half=lower')");
            sql.execute("INSERT INTO co_entity_map VALUES (1, 'minecraft:zombie')");
            sql.execute("INSERT INTO co_block VALUES (" + now + ", 1, 1, 10, 64, 10, 1, 0, NULL, CAST('1,2' AS BLOB), 1, 0)");
            sql.execute("INSERT INTO co_block VALUES (" + (now - 86_400) + ", 2, 1, 11, 64, 10, 2, 0, NULL, NULL, 0, 1)");
            sql.execute("INSERT INTO co_block VALUES (" + now + ", 1, 1, 12, 64, 10, 1, 0, NULL, NULL, 3, 0)");
            sql.execute("INSERT INTO co_session VALUES (" + now + ", 1, 1, 0, 64, 0, 1)");
            sql.execute("INSERT INTO co_sign (time, user, wid, x, y, z, action, line_1, line_2) VALUES (" + now + ", 1, 1, 5, 64, 5, 1, 'Hello', 'there')");
            sql.execute("INSERT INTO co_chat VALUES (" + now + ", 1, 1, 0, 64, 0, 'hi all')");
        }
        LogQueue queue = new LogQueue(logger);
        Writer writer = new Writer(queue, () -> database, logger);
        writer.start();
        CoreProtectImport.Source source = CoreProtectImport.find(folder);
        long count = new CoreProtectImport(source, queue, logger).run();
        writer.stop(10, TimeUnit.SECONDS);
        assertEquals(6, count, "two block changes, a kill, a session, a sign and a chat message");

        try (Connection connection = database.connect(); Statement sql = connection.createStatement()) {
            try (ResultSet row = sql.executeQuery("SELECT u.name, o.data, n.data, b.action, b.rolled_back FROM xr_block b"
                + " JOIN xr_user u ON u.id = b.user JOIN xr_state o ON o.id = b.old_state JOIN xr_state n ON n.id = b.new_state ORDER BY b.x")) {
                row.next();
                assertEquals("Notch minecraft:air minecraft:oak_door[facing=north,half=lower] 1 0",
                    row.getString(1) + " " + row.getString(2) + " " + row.getString(3) + " " + row.getInt(4) + " " + row.getInt(5));
                row.next();
                assertEquals("#tnt minecraft:stone minecraft:air 0 1",
                    row.getString(1) + " " + row.getString(2) + " " + row.getString(3) + " " + row.getInt(4) + " " + row.getInt(5),
                    "a break, already rolled back in CoreProtect");
            }
            assertEquals(1, count("SELECT COUNT(*) FROM xr_entity e JOIN xr_state t ON t.id = e.type WHERE t.data = 'minecraft:zombie'"));
            assertEquals(1, count("SELECT COUNT(*) FROM xr_session WHERE action = 1"));
            assertEquals(1, count("SELECT COUNT(*) FROM xr_text WHERE message = 'Hello | there'"));
            assertEquals(1, count("SELECT COUNT(*) FROM xr_text WHERE message = 'hi all' AND kind = 1"));
            assertTrue(count("SELECT COUNT(*) FROM xr_user WHERE uuid_most IS NOT NULL") == 1, "Notch keeps his uuid");
        }
    }

    private long count(String query) throws Exception {
        try (Connection connection = database.connect(); Statement sql = connection.createStatement();
             ResultSet result = sql.executeQuery(query)) {
            result.next();
            return result.getLong(1);
        }
    }
}
