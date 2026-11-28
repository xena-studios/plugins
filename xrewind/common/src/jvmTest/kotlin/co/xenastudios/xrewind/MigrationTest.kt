package co.xenastudios.xrewind

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.config.YamlNode
import co.xenastudios.core.common.family.Family
import co.xenastudios.core.common.family.MemoryBoard
import co.xenastudios.core.jvm.config.JvmYaml
import co.xenastudios.core.jvm.platform.FolderStorage
import co.xenastudios.core.testing.RecordingLog
import co.xenastudios.xrewind.api.Action
import co.xenastudios.xrewind.api.Actor
import co.xenastudios.xrewind.api.Query
import co.xenastudios.xrewind.config.Legacy
import co.xenastudios.xrewind.config.XRewindConfig
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * 1.x's config and database, carried into 2.0, and left so the 1.x jar put back still reads them:
 * its config as a newer one (with its copy aside), its database exactly as it made it, 2.0's rows
 * in 1.x's own format. The configs are kept as `.yml.txt`: as shipped, their blank MySQL password
 * is the setting owners fill in, which code scanning would take for a credential.
 */
class MigrationTest {
    private val folder = Files.createTempDirectory("xrewind-migration")
    private val log = RecordingLog()

    @AfterTest
    fun clean() {
        folder.toFile().deleteRecursively()
    }

    private fun resource(name: String) = String(MigrationTest::class.java.getResourceAsStream("/$name")!!.readBytes(), StandardCharsets.UTF_8)

    private fun files(): JvmFiles {
        val family = Family(FolderStorage(folder.resolve("Xena")), MemoryBoard(), JvmYaml)
        return JvmFiles(folder.resolve("xRewind"), family, emptyMap(), log)
    }

    private fun write(name: String, text: String): Path = folder.resolve("xRewind").resolve(name).also {
        Files.createDirectories(it.parent)
        Files.write(it, text.toByteArray(StandardCharsets.UTF_8))
    }

    private fun read(name: String) = String(Files.readAllBytes(folder.resolve("xRewind").resolve(name)), StandardCharsets.UTF_8)

    @Test
    fun theMessagesMovedAreTheOnes1xShipped() {
        val root = (JvmYaml.read(resource("config-1.1.1.yml.txt")) as Outcome.Ok).value as YamlNode.Mapping
        assertEquals(emptyMap(), Legacy.messages(root), "a message 1.x shipped would be carried as if edited")
        fun count(node: YamlNode?): Int = when (node) {
            is YamlNode.Mapping -> node.entries.sumOf { count(it.value) }
            null -> 0
            else -> 1
        }
        assertEquals(count(root["messages"]), Legacy.MOVED.size, "every message 1.x had has a place in 2.0")
    }

    @Test
    fun aDefault1xConfigCarriesForwardWithNothingInLang() {
        write("config.yml", resource("config-1.1.1.yml.txt"))
        val loaded = files().load()
        val text = read("config.yml")
        assertTrue("config-version: 2" in text)
        assertTrue(Files.exists(folder.resolve("xRewind/config.yml.bak-v1")))
        assertFalse("  lookup:" in text, "1.x's messages are still in config.yml")
        assertFalse("\ntheme:" in text)
        assertTrue("updates:" in text && "update-checker" !in text)
        assertFalse("language: \"en\"" in text)
        assertEquals(emptyList(), loaded.problems, loaded.problems.toString())
        assertEquals(XRewindConfig.defaults, loaded.config)
    }

    @Test
    fun anEdited1xConfigKeepsEveryEditAndItsMessagesGoToLang() {
        write("config.yml", resource("config-1.1.1-edited.yml.txt"))
        val loaded = files().load()
        val config = loaded.config
        assertTrue(config[XRewindConfig.log.chat])
        assertEquals(250, config[XRewindConfig.rollback.maxRadius])
        assertEquals(90, config[XRewindConfig.purge.keepDays])
        assertFalse(config[XRewindConfig.updates.check])
        val english = read("lang/en.yml")
        assertTrue("<gold>History:</gold> <count> changes" in english, english)
        assertTrue("Undone" in english, english)
        val text = read("config.yml")
        assertTrue("prefix: \"<gold>[Rewind]</gold> \"" in text, "the edited theme wasn't carried")
        val en = loaded.translations.languageFor(null)
        assertEquals("Undone", loaded.translations.render(XRewindMessages.verbRollback, en, emptyMap())!!.plain())
    }

    @Test
    fun a1xDatabaseIsReadAsItIsAndLeftAs1xMadeIt() {
        val file = folder.resolve("history.db")
        DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
            connection.createStatement().use { statement ->
                for (sql in resource("history-1.1.1.sql").lines().filter { it.isNotBlank() && !it.startsWith("--") }) statement.execute(sql)
            }
        }
        val before = schemaOf(file)
        val platform = TestPlatform().apply { start = Instant.parse("2026-06-01T00:10:00Z") }
        val h = Harness(folder, "log:\n  signs: true\n", platform).start()
        try {
            val all = platform.await(h.plugin.lookup(Query.builder().actions(*Action.entries.toTypedArray()).build()))
            assertEquals(
                listOf(Action.SIGN, Action.REMOVE, Action.BREAK, Action.BREAK, Action.JOIN),
                all.map { it.action }, "1.x's rows, newest first",
            )
            assertEquals("Steve", all[0].actor)
            assertEquals("Keep out | Steve's", all[0].target)
            assertEquals("#tnt", all[2].actor)
            // A rollback of 1.x's rows: the chest comes back with what it held, the diamonds go back in.
            val plan = platform.await(h.plugin.planRollback(Query.builder().time(86_400).build()))
            platform.await(h.plugin.apply(plan))
            assertEquals("minecraft:chest[facing=north,type=single,waterlogged=false]", h.server.state("world", 12, 64, 10))
            assertTrue(h.server.contents[Spot("world", 12, 64, 10)].contentEquals(byteArrayOf(0x49, 1, 2, 3)))
            assertEquals(5, h.server.items[Spot("world", 14, 64, 10)]?.get("minecraft:diamond"))
            // 2.0 writes as 1.x did: a new player, a known one renamed.
            val steve = co.xenastudios.core.api.PlayerRef.parseOrNull("5627dd98-e6be-3c21-b8a8-e92344183641")!!
            h.plugin.logRemoval(Actor.player(steve, "Steve2"), co.xenastudios.core.api.WorldRef.of("world"), 20, 64, 20, "minecraft:oak_log[axis=y]")
            h.flush()
        } finally {
            h.stop()
        }
        assertEquals(before, schemaOf(file), "every table 1.x made, as it made them")
        DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
            connection.createStatement().use { s ->
                s.executeQuery("SELECT value FROM xrewind_meta WHERE name = 'schema'").use { assertTrue(it.next()); assertEquals("1", it.getString(1)) }
                s.executeQuery("SELECT COUNT(*) FROM xrewind_block WHERE rolled_back = 1").use { assertTrue(it.next()); assertEquals(2, it.getInt(1)) }
                s.executeQuery("SELECT COUNT(*) FROM xrewind_user").use { assertTrue(it.next()); assertEquals(2, it.getInt(1), "Steve kept his id") }
                // 1.x's own lookup (Lookup.blocks), word for word, of the row 2.0 wrote.
                s.executeQuery(
                    "SELECT b.id, b.time, u.name, w.name, b.x, b.y, b.z, b.action, os.data, ns.data, b.rolled_back FROM xrewind_block b" +
                        " JOIN xrewind_user u ON u.id = b.user JOIN xrewind_world w ON w.id = b.world JOIN xrewind_state os ON os.id = b.old_state" +
                        " JOIN xrewind_state ns ON ns.id = b.new_state WHERE b.x = 20",
                ).use {
                    assertTrue(it.next())
                    assertEquals("Steve2", it.getString(3))
                    assertEquals("world", it.getString(4))
                    assertEquals(0, it.getInt(8))
                    assertEquals("minecraft:oak_log[axis=y]", it.getString(9))
                    assertEquals("minecraft:air", it.getString(10))
                    assertEquals(13046400 + 600, it.getInt(2), "seconds since 2026, as 1.x keeps them")
                }
            }
        }
    }

    private fun schemaOf(file: Path): List<String> = DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
        connection.createStatement().use { s ->
            s.executeQuery("SELECT sql FROM sqlite_master WHERE name LIKE 'xrewind_%' AND name <> 'xrewind_meta' ORDER BY name").use {
                val out = ArrayList<String>()
                while (it.next()) out += it.getString(1).orEmpty()
                out
            }
        }
    }
}
