package co.xenastudios.xstats

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.config.YamlNode
import co.xenastudios.core.common.family.Family
import co.xenastudios.core.common.family.MemoryBoard
import co.xenastudios.core.common.i18n.CoreMessages
import co.xenastudios.core.jvm.config.JvmYaml
import co.xenastudios.core.jvm.platform.FolderStorage
import co.xenastudios.core.testing.RecordingLog
import co.xenastudios.xstats.api.Period
import co.xenastudios.xstats.config.Legacy
import co.xenastudios.xstats.config.XStatsConfig
import co.xenastudios.xstats.data.Calendar
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 1.x's config and database, carried into 2.0, and left so the 1.x jar put back still reads them:
 * its config as a newer one (with its copy aside), its database exactly as it made it (schema 2, no
 * table or column added). 1.0.0 and 1.0.1 shipped the same config and schema. The configs are kept
 * as `.yml.txt`: their blank MySQL password is the setting owners fill in, which code scanning
 * would take for a credential.
 */
class MigrationTest {
    private val folder = Files.createTempDirectory("xstats-migration")
    private val log = RecordingLog()

    @AfterTest
    fun clean() {
        folder.toFile().deleteRecursively()
    }

    private fun resource(name: String) = String(MigrationTest::class.java.getResourceAsStream("/$name")!!.readBytes(), StandardCharsets.UTF_8)

    private fun files(): JvmFiles = JvmFiles(folder.resolve("xStats"), Family(FolderStorage(folder.resolve("Xena")), MemoryBoard(), JvmYaml), emptyMap(), log)

    private fun write(name: String, text: String): Path = folder.resolve("xStats").resolve(name).also {
        Files.createDirectories(it.parent)
        Files.write(it, text.toByteArray(StandardCharsets.UTF_8))
    }

    private fun read(name: String) = String(Files.readAllBytes(folder.resolve("xStats").resolve(name)), StandardCharsets.UTF_8)

    @Test
    fun theMessagesMovedAreTheOnes1xShipped() {
        val root = (JvmYaml.read(resource("config-1.0.1.yml.txt")) as Outcome.Ok).value as YamlNode.Mapping
        assertEquals(emptyMap(), Legacy.messages(root), "a message 1.x shipped would be carried as if edited")
        // Every message setting 1.x had has a place in 2.0: messages, the boards' three and the four period names.
        fun count(node: YamlNode?): Int = when (node) {
            is YamlNode.Mapping -> node.entries.sumOf { count(it.value) }
            null -> 0
            else -> 1
        }
        val boards = (root["leaderboards"] as YamlNode.Mapping)["boards"] as YamlNode.Mapping
        val expected = count(root["messages"]) + (boards.entries.size - 1) + 4
        assertEquals(expected, Legacy.MOVED.size)
    }

    @Test
    fun aDefault1xConfigCarriesForwardWithNothingInLang() {
        write("config.yml", resource("config-1.0.1.yml.txt"))
        val loaded = files().load()
        val text = read("config.yml")
        assertTrue("config-version: 2" in text)
        assertTrue(Files.exists(folder.resolve("xStats/config.yml.bak-v1")))
        assertFalse("\ntheme:" in text)
        assertFalse("profile-header" in text, "the messages are in lang/ now")
        assertFalse("names:" in text, "the period names are in lang/ now")
        assertTrue("updates:" in text && "update-checker" !in text)
        assertFalse("language: \"en\"" in text)
        assertEquals(emptyList(), loaded.problems, loaded.problems.toString())
        assertEquals(XStatsConfig.defaults, loaded.config)
    }

    @Test
    fun anEdited1xConfigKeepsEveryEditAndItsMessagesGoToLang() {
        write("config.yml", resource("config-1.0.1-edited.yml.txt"))
        val loaded = files().load()
        val config = loaded.config
        assertEquals(10, config[XStatsConfig.collecting.saveEveryMinutes])
        assertEquals(8899, config[XStatsConfig.web.port])
        assertFalse(config[XStatsConfig.updates.check])
        assertEquals(listOf("lb", "top"), config[XStatsConfig.commands.leaderboard.aliases])
        assertEquals(14, config[XStatsConfig.stats].size)
        val english = read("lang/en.yml")
        assertTrue("Nobody here yet!" in english, english)
        assertTrue("Since midnight" in english && "Be the first" in english, english)
        val text = read("config.yml")
        assertTrue("language: \"en\"" in text || "language: en" in text, text.substringAfter("messages:").take(300))
        assertTrue("highlight: \"<gold>\"" in text, "the edited theme wasn't carried")
        val en = loaded.translations.languageFor(null)
        assertEquals("Since midnight", loaded.translations.render(XStatsMessages.periodDaily, en, emptyMap())!!.plain())
        assertTrue(loaded.translations.render(CoreMessages.commandNoPermission, en, emptyMap()) != null)
    }

    private fun tables(file: Path): List<String> = DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT type, name, sql FROM sqlite_master WHERE name NOT LIKE '%changes%' ORDER BY name").use { rows ->
                buildList { while (rows.next()) add("${rows.getString(1)} ${rows.getString(2)} ${rows.getString(3)}") }
            }
        }
    }

    @Test
    fun a1xDatabaseIsReadAsItIsAndLeftAs1xMadeIt() {
        val h = Harness(folder)
        val file = folder.resolve("stats.db")
        val today = Calendar(co.xenastudios.xstats.data.Zones.UTC_ONLY.server, XStatsConfig.WeekDay.MONDAY).today(h.platform.clock.now().toEpochMilliseconds())
        DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
            connection.createStatement().use { statement ->
                for (sql in resource("stats-1.0.1.sql").lines().filter { it.isNotBlank() && !it.startsWith("--") }) {
                    statement.execute(sql.replace("%STEVE%", FakeStatsServer.ref("Steve").toString()).replace("%TODAY%", today.toString()))
                }
            }
        }
        val before = tables(file)
        h.start()
        val steve = FakeStatsServer.ref("Steve")
        assertEquals(42, h.plugin.value(steve, "kills", Period.ALL))
        assertEquals(5, h.plugin.value(steve, "kills", Period.DAILY))
        assertEquals(3600, h.plugin.value(steve, "playtime", Period.ALL))
        assertEquals("Steve", h.plugin.top("kills", Period.ALL, 5).single().name)
        assertEquals(listOf(1), h.plugin.boards.map { it.id })
        // Steve's counter where 1.x last read it: nothing new is counted, and the source isn't taken as changed.
        h.join("Steve").counters["player_kills"] = 42
        h.save()
        assertEquals(42, h.plugin.value(steve, "kills", Period.ALL))
        assertFalse(h.platform.log.lines.any { "source changed" in it.message }, h.platform.log.lines.toString())
        h.stop()
        // The 1.x jar put back finds its tables exactly as it made them, still at schema 2.
        assertEquals(before, tables(file))
        DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT value FROM xstats_meta WHERE name = 'schema'").use { assertTrue(it.next()); assertEquals("2", it.getString(1)) }
                statement.executeQuery("SELECT value, raw FROM xstats_totals WHERE stat = 3").use {
                    assertTrue(it.next(), "a stat 2.0 doesn't define keeps its row")
                    assertEquals(7, it.getLong(1))
                }
            }
        }
    }
}
