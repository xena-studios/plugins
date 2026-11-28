package co.xenastudios.xvote

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.config.YamlNode
import co.xenastudios.core.common.family.Family
import co.xenastudios.core.common.family.MemoryBoard
import co.xenastudios.core.common.i18n.CoreMessages
import co.xenastudios.core.jvm.config.JvmYaml
import co.xenastudios.core.jvm.platform.FolderStorage
import co.xenastudios.core.testing.RecordingLog
import co.xenastudios.xvote.api.Period
import co.xenastudios.xvote.config.Legacy
import co.xenastudios.xvote.config.XVoteConfig
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
 * as `.yml.txt`: as shipped, their blank MySQL password is the setting owners fill in, which code
 * scanning would take for a credential.
 */
class MigrationTest {
    private val folder = Files.createTempDirectory("xvote-migration")
    private val log = RecordingLog()

    @AfterTest
    fun clean() {
        folder.toFile().deleteRecursively()
    }

    private fun resource(name: String) = String(MigrationTest::class.java.getResourceAsStream("/$name")!!.readBytes(), StandardCharsets.UTF_8)

    private fun files(): JvmFiles = JvmFiles(folder.resolve("xVote"), Family(FolderStorage(folder.resolve("Xena")), MemoryBoard(), JvmYaml), emptyMap(), log)

    private fun write(name: String, text: String): Path = folder.resolve("xVote").resolve(name).also {
        Files.createDirectories(it.parent)
        Files.write(it, text.toByteArray(StandardCharsets.UTF_8))
    }

    private fun read(name: String) = String(Files.readAllBytes(folder.resolve("xVote").resolve(name)), StandardCharsets.UTF_8)

    @Test
    fun theMessagesMovedAreTheOnes1xShipped() {
        val root = (JvmYaml.read(resource("config-1.0.1.yml.txt")) as Outcome.Ok).value as YamlNode.Mapping
        assertEquals(emptyMap(), Legacy.messages(root), "a message 1.x shipped would be carried as if edited")
        // Every message setting 1.x had has a place in 2.0: messages, party.started and progress, reminders.message.
        fun count(node: YamlNode?): Int = when (node) {
            is YamlNode.Mapping -> node.entries.sumOf { count(it.value) }
            null -> 0
            else -> 1
        }
        val expected = count(root["messages"]) + 3
        assertEquals(expected, Legacy.MOVED.size)
    }

    @Test
    fun aDefault1xConfigCarriesForwardWithNothingInLang() {
        write("config.yml", resource("config-1.0.1.yml.txt"))
        val loaded = files().load()
        val text = read("config.yml")
        assertTrue("config-version: 2" in text)
        assertTrue(Files.exists(folder.resolve("xVote/config.yml.bak-v1")))
        assertFalse("\ntheme:" in text)
        assertFalse("started:" in text, "party.started is in lang/ now")
        assertTrue("updates:" in text && "update-checker" !in text)
        assertFalse("language: \"en\"" in text)
        assertEquals(emptyList(), loaded.problems, loaded.problems.toString())
        assertEquals(XVoteConfig.defaults, loaded.config)
    }

    @Test
    fun anEdited1xConfigKeepsEveryEditAndItsMessagesGoToLang() {
        write("config.yml", resource("config-1.0.1-edited.yml.txt"))
        val loaded = files().load()
        val config = loaded.config
        assertEquals(8299, config[XVoteConfig.receiver.port])
        assertFalse(config[XVoteConfig.updates.check])
        assertEquals(listOf("planetminecraft", "minecraft-mp"), config[XVoteConfig.sites].keys.toList())
        assertEquals(listOf("diamond 1"), config[XVoteConfig.rewards].getValue("vote").items)
        val english = read("lang/en.yml")
        assertTrue("Vote for us! <available> of <sites> open:" in english, english)
        assertTrue("PARTY TIME" in english, english)
        val text = read("config.yml")
        assertTrue("language: \"en\"" in text || "language: en" in text, text.substringAfter("messages:").take(300))
        assertTrue("highlight: \"<gold>\"" in text, "the edited theme wasn't carried")
        val en = loaded.translations.languageFor(null)
        assertEquals("PARTY TIME", loaded.translations.render(XVoteMessages.partyStarted, en, emptyMap())!!.plain())
        assertTrue(loaded.translations.render(CoreMessages.commandNoPermission, en, emptyMap()) != null)
    }

    private fun tables(file: Path): List<String> = DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT type, name, sql FROM sqlite_master ORDER BY name").use { rows ->
                buildList { while (rows.next()) add("${rows.getString(1)} ${rows.getString(2)} ${rows.getString(3)}") }
            }
        }
    }

    @Test
    fun a1xDatabaseIsReadAsItIsAndLeftAs1xMadeIt() {
        val h = Harness(folder)
        val file = folder.resolve("votes.db")
        DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
            connection.createStatement().use { statement ->
                for (sql in resource("votes-1.0.1.sql").lines().filter { it.isNotBlank() && !it.startsWith("--") }) statement.execute(sql)
            }
        }
        val before = tables(file)
        h.start()
        val steve = FakeVoteServer.ref("Steve")
        val stats = h.plugin.stats(steve)
        assertEquals(3, stats.total, h.platform.log.lines.toString() + " " + FakeVoteServer.ref("Steve"))
        assertEquals(2, stats.bestStreak)
        assertEquals(2, stats.streak, "voted yesterday: the streak stands")
        assertEquals(3, h.plugin.party().votes)
        assertEquals("Steve", h.plugin.top(Period.ALL, 5).single().name)
        // Steve's unpaid vote is paid when he joins, and Alex's waiting one becomes his.
        val player = h.join("Steve")
        h.platform.until("Steve's unpaid vote paid") { player.items.isNotEmpty() }
        h.join("Alex")
        h.platform.until("Alex's vote matched") { h.plugin.stats(FakeVoteServer.ref("Alex")).total == 1 }
        // The held item, as 1.x kept it, is handed out.
        h.run("vote claim", h.server.source("Steve")) { player.received.any { "Here are your" in it } }
        h.stop()
        assertEquals(before, tables(file), "no table, column or index added: the 1.x jar reads it as its own")
        DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT value FROM xvote_meta WHERE name = 'schema'").use { assertTrue(it.next()); assertEquals("2", it.getString(1)) }
                // Every vote 2.0 took is paid, as 1.x would read it: a claim and a time.
                statement.executeQuery("SELECT COUNT(*) FROM xvote_votes WHERE claim IS NULL").use { assertTrue(it.next()); assertEquals(0, it.getInt(1)) }
            }
        }
    }
}
