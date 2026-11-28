package co.xenastudios.xrewind

import co.xenastudios.core.jvm.db.Drivers
import co.xenastudios.core.testing.RecordingLog
import co.xenastudios.xrewind.api.Action
import co.xenastudios.xrewind.api.Query
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `/xrewind import coreprotect` from a CoreProtect SQLite database as CoreProtect lays it out. */
class ImportTest {
    private val plugins = Files.createTempDirectory("plugins")

    @AfterTest
    fun clean() {
        plugins.toFile().deleteRecursively()
    }

    private fun coreProtect() {
        val folder = Files.createDirectories(plugins.resolve("CoreProtect"))
        Files.write(folder.resolve("config.yml"), "use-mysql: false\ntable-prefix: co_\n".toByteArray())
        DriverManager.getConnection("jdbc:sqlite:${folder.resolve("database.db")}").use { c ->
            c.createStatement().use { s ->
                for (sql in listOf(
                    "CREATE TABLE co_user (id INTEGER PRIMARY KEY ASC, time INTEGER, user TEXT, uuid TEXT)",
                    "CREATE TABLE co_world (id INTEGER, world TEXT)",
                    "CREATE TABLE co_material_map (id INTEGER, material TEXT)",
                    "CREATE TABLE co_blockdata_map (id INTEGER, data TEXT)",
                    "CREATE TABLE co_entity_map (id INTEGER, entity TEXT)",
                    "CREATE TABLE co_block (rowid INTEGER PRIMARY KEY ASC, time INTEGER, user INTEGER, wid INTEGER, x INTEGER, y INTEGER, z INTEGER, type INTEGER, data INTEGER, meta BLOB, blockdata BLOB, action INTEGER, rolled_back INTEGER)",
                    "CREATE TABLE co_container (rowid INTEGER PRIMARY KEY ASC, time INTEGER, user INTEGER, wid INTEGER, x INTEGER, y INTEGER, z INTEGER, type INTEGER, data INTEGER, amount INTEGER, metadata BLOB, action INTEGER, rolled_back INTEGER)",
                    "CREATE TABLE co_session (rowid INTEGER PRIMARY KEY ASC, time INTEGER, user INTEGER, wid INTEGER, x INTEGER, y INTEGER, z INTEGER, action INTEGER)",
                    "CREATE TABLE co_chat (rowid INTEGER PRIMARY KEY ASC, time INTEGER, user INTEGER, wid INTEGER, x INTEGER, y INTEGER, z INTEGER, message TEXT)",
                    "INSERT INTO co_user VALUES (1, 1780000000, 'Steve', '5627dd98-e6be-3c21-b8a8-e92344183641')",
                    "INSERT INTO co_user VALUES (2, 1780000000, '#creeper', NULL)",
                    "INSERT INTO co_world VALUES (1, 'world')",
                    "INSERT INTO co_material_map VALUES (1, 'minecraft:oak_stairs')",
                    "INSERT INTO co_material_map VALUES (2, 'minecraft:diamond')",
                    "INSERT INTO co_blockdata_map VALUES (1, 'facing=east')",
                    "INSERT INTO co_blockdata_map VALUES (2, 'half=bottom')",
                    "INSERT INTO co_entity_map VALUES (1, 'minecraft:zombie')",
                    "INSERT INTO co_block VALUES (1, 1780000100, 1, 1, 5, 64, 5, 1, 0, NULL, CAST('1,2' AS BLOB), 0, 0)",
                    "INSERT INTO co_block VALUES (2, 1780000200, 2, 1, 6, 64, 5, 1, 0, NULL, NULL, 1, 1)",
                    "INSERT INTO co_block VALUES (3, 1780000300, 1, 1, 7, 64, 5, 1, 0, NULL, NULL, 3, 0)",
                    "INSERT INTO co_container VALUES (1, 1780000400, 1, 1, 8, 64, 5, 2, 0, 4, NULL, 0, 0)",
                    "INSERT INTO co_session VALUES (1, 1780000000, 1, 1, 0, 64, 0, 1)",
                    "INSERT INTO co_chat VALUES (1, 1780000050, 1, 1, 0, 64, 0, 'hi all')",
                )) s.execute(sql)
            }
        }
    }

    @Test
    fun coreProtectsHistoryComesAcrossOnceUnlessAgain() {
        coreProtect()
        val log = RecordingLog()
        val history = CoreProtectImport(plugins, Drivers.serverOnly(javaClass.classLoader), javaClass.classLoader, log) { "item:$it".encodeToByteArray() }
        val h = Harness(history = history).start()
        try {
            h.run("xrewind import coreprotect") { h.console.replies.any { "Imported" in it || "Couldn't" in it } }
            assertTrue(h.console.replies.any { "Imported 6 changes" in it }, h.console.replies.toString())
            val all = h.platform.await(h.plugin.lookup(Query.builder().actions(*Action.entries.toTypedArray()).build()))
            assertEquals(6, all.size)
            val broke = all.single { it.action == Action.BREAK }
            assertEquals("Steve", broke.actor)
            assertEquals("minecraft:oak_stairs[facing=east,half=bottom]", broke.target)
            assertTrue(all.single { it.action == Action.PLACE }.rolledBack, "CoreProtect's rolled-back flag comes across")
            assertEquals("minecraft:zombie", all.single { it.action == Action.KILL }.target)
            assertEquals(4, all.single { it.action == Action.REMOVE }.amount)
            assertEquals("hi all", all.single { it.action == Action.CHAT }.target)
            h.run("xrewind import coreprotect") { h.console.replies.any { "already imported" in it } }
        } finally {
            h.stop()
        }
    }

    @Test
    fun withoutCoreProtectItSaysSo() {
        val history = CoreProtectImport(plugins, Drivers.serverOnly(javaClass.classLoader), javaClass.classLoader, RecordingLog()) { null }
        val h = Harness(history = history).start()
        try {
            h.run("xrewind import coreprotect") { h.console.replies.any { "Couldn't read" in it } }
            assertTrue(h.console.replies.last().contains("there's no plugins/CoreProtect/config.yml."), h.console.replies.toString())
        } finally {
            h.stop()
        }
    }
}
