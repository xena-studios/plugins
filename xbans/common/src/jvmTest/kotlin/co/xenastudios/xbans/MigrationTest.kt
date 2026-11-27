package co.xenastudios.xbans

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.config.YamlNode
import co.xenastudios.core.common.db.ChangeFeed
import co.xenastudios.core.common.db.DatabaseSettings
import co.xenastudios.core.common.db.migrate
import co.xenastudios.core.common.family.Family
import co.xenastudios.core.common.family.MemoryBoard
import co.xenastudios.core.common.i18n.CoreMessages
import co.xenastudios.core.common.text.Placeholder
import co.xenastudios.core.jvm.config.JvmYaml
import co.xenastudios.core.jvm.db.Drivers
import co.xenastudios.core.jvm.db.JdbcDatabase
import co.xenastudios.core.jvm.platform.FolderStorage
import co.xenastudios.core.testing.RecordingLog
import co.xenastudios.xbans.api.Punishment
import co.xenastudios.xbans.api.PunishmentType
import co.xenastudios.xbans.config.Legacy
import co.xenastudios.xbans.config.XBansConfig
import co.xenastudios.xbans.ip.IpKeys
import co.xenastudios.xbans.model.Active
import co.xenastudios.xbans.model.Entry
import co.xenastudios.xbans.store.Backend
import co.xenastudios.xbans.store.Tables
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * 1.x's config and database, carried into 2.0, and left so the 1.x jar put back still reads them:
 * its config as a newer one (with its copy aside), its database exactly as it made it. 1.0.0 and
 * 1.0.1 shipped the same config and schema. The configs are kept as `.yml.txt`: as shipped, their
 * blank MySQL password is the setting owners fill in, which code scanning would take for a credential.
 */
class MigrationTest {
    private val folder = Files.createTempDirectory("xbans-migration")
    private val log = RecordingLog()

    @AfterTest
    fun clean() {
        folder.toFile().deleteRecursively()
    }

    private fun resource(name: String) = String(MigrationTest::class.java.getResourceAsStream("/$name")!!.readBytes(), StandardCharsets.UTF_8)

    private fun files(): JvmFiles {
        val family = Family(FolderStorage(folder.resolve("Xena")), MemoryBoard(), JvmYaml)
        return JvmFiles(folder.resolve("xBans"), family, emptyMap(), log)
    }

    private fun write(name: String, text: String): Path = folder.resolve("xBans").resolve(name).also {
        Files.createDirectories(it.parent)
        Files.write(it, text.toByteArray(StandardCharsets.UTF_8))
    }

    private fun read(name: String) = String(Files.readAllBytes(folder.resolve("xBans").resolve(name)), StandardCharsets.UTF_8)

    private fun <T> CompletableFuture<T>.await(): T = get(15, TimeUnit.SECONDS)

    @Test
    fun theMessagesMovedAreTheOnes1xShipped() {
        val root = (JvmYaml.read(resource("config-1.0.1.yml.txt")) as Outcome.Ok).value as YamlNode.Mapping
        assertEquals(emptyMap(), Legacy.messages(root), "a message 1.x shipped would be carried as if edited")
        // Every message setting 1.x had has a place in 2.0: screens (but appeal-link), broadcasts, messages.
        fun count(node: YamlNode?): Int = when (node) {
            is YamlNode.Mapping -> node.entries.sumOf { count(it.value) }
            null -> 0
            else -> 1
        }
        val expected = count(root["screens"]) - 1 + count(root["broadcasts"]) + count(root["messages"])
        assertEquals(expected, Legacy.MOVED.size)
    }

    @Test
    fun aDefault1xConfigCarriesForwardWithNothingInLang() {
        write("config.yml", resource("config-1.0.1.yml.txt"))
        val loaded = files().load()
        val text = read("config.yml")
        assertTrue("config-version: 2" in text)
        assertTrue(Files.exists(folder.resolve("xBans/config.yml.bak-v1")))
        assertFalse("broadcasts:" in text, "1.x's messages are still in config.yml")
        assertFalse("\ntheme:" in text)
        assertTrue("updates:" in text && "update-checker" !in text)
        assertTrue("appeal-link:" in text)
        assertFalse("language: \"en\"" in text)
        assertEquals(emptyList(), loaded.problems, loaded.problems.toString())
        assertEquals(XBansConfig.defaults, loaded.config)
    }

    @Test
    fun anEdited1xConfigKeepsEveryEditAndItsMessagesGoToLang() {
        write("config.yml", resource("config-1.0.1-edited.yml.txt"))
        val loaded = files().load()
        val config = loaded.config
        assertEquals("https://forum.example.net/appeals", config[XBansConfig.screens.appealLink])
        assertEquals("Breaking the rules", config[XBansConfig.punishing.defaultReason])
        assertEquals(listOf("1w", "30d", "permanent"), config[XBansConfig.templates].getValue("hacking").ladder.map { it.typed() })
        assertEquals("1d", config[XBansConfig.staffLimits.groups].getValue("helper").ban.typed())
        val english = read("lang/en.yml")
        assertTrue("<red>Banned: <reason>, by <staff>. Appeal at <appeal>" in english, english)
        assertTrue("Hang on, xBans is loading." in english, english)
        val text = read("config.yml")
        assertTrue("language: \"en\"" in text || "language: en" in text, text.substringAfter("messages:").take(300))
        assertTrue("prefix: \"<gold>[Bans]</gold> \"" in text, "the edited theme wasn't carried")
        val en = loaded.translations.languageFor(null)
        // The owner's screen uses <staff>, which the English doesn't: still filled.
        val screen = loaded.translations.render(XBansMessages.screenBan, en, mapOf("reason" to Placeholder.text("x"), "staff" to Placeholder.text("Mod"), "appeal" to Placeholder.text("y")))!!.plain()
        assertEquals("Banned: x, by Mod. Appeal at y", screen)
        assertNotNull(loaded.translations.render(CoreMessages.commandNoPermission, en, emptyMap()))
        assertEquals(emptyList(), loaded.problems.filter { "lang" !in it }, loaded.problems.toString())
    }

    @Test
    fun a1xDatabaseIsReadAsItIsAndLeftAs1xMadeIt() {
        val file = folder.resolve("punishments.db")
        DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
            connection.createStatement().use { statement ->
                for (sql in resource("bans-1.0.1.sql").lines().filter { it.isNotBlank() && !it.startsWith("--") }) statement.execute(sql)
            }
        }
        val before = schemaOf(file)
        val database = (JdbcDatabase.open(DatabaseSettings(DatabaseSettings.Type.SQLITE, "xbans_"), file, Drivers.serverOnly(javaClass.classLoader), log, "Test") as Outcome.Ok).value
        val steve = PlayerRef.parseOrNull("5627dd98-e6be-3c21-b8a8-e92344183641")!!
        val alex = PlayerRef.parseOrNull("7a1e3a7d-1b2c-3d4e-8f90-112233445566")!!
        try {
            assertEquals(1, (database.migrate(Tables.MIGRATIONS).await() as Outcome.Ok).value)
            val backend = Backend(database, ChangeFeed.off(), Active(), IpKeys.raw())
            val now = 1763600000000
            val inForce = (database.read { backend.inForce(it, now) }.await() as Outcome.Ok).value
            assertEquals(listOf(1L, 4L), inForce.map { it.id }.sorted(), "Steve's ban and the IP ban; Alex's mute was lifted")
            inForce.forEach(backend.active::add)
            assertNotNull(backend.active.ban(steve, null, "server", now))
            assertNotNull(backend.active.ban(alex, "198.51.100.7", "server", now), "the IP ban reaches anyone on it")
            assertNull(backend.active.mute(alex, null, "server", now))
            val lifted = (backend.find(2).await() as Outcome.Ok).value!!
            assertEquals("appealed", lifted.punishment.removal!!.reason)
            assertEquals(10, lifted.staffWeight)
            // 2.0 writes as 1.x did.
            val warn = Entry(Punishment(0, PunishmentType.WARN, alex, "Alex", false, "Language", null, "Console",
                Instant.fromEpochMilliseconds(now), Instant.fromEpochMilliseconds(now + 86_400_000), "*", false, null, null), null, Entry.CONSOLE)
            assertTrue(backend.insert(warn, emptyList(), null, "server", "").await() is Outcome.Ok)
            assertTrue(backend.seen(steve, "Steve", "203.0.113.10", 5, false, now).await() is Outcome.Ok)
        } finally {
            database.close().await()
        }
        // Every table 1.x made, as it made them; meta still says schema 1, so 1.x opens it.
        assertEquals(before, schemaOf(file))
        DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
            connection.createStatement().use { s ->
                s.executeQuery("SELECT value FROM xbans_meta WHERE name = 'schema'").use { assertTrue(it.next()); assertEquals("1", it.getString(1)) }
                // 1.x's own read (Rows.SELECT), word for word, of the warning 2.0 wrote.
                s.executeQuery("SELECT p.id, p.type, p.uuid, p.name, p.ip, p.ip_based, p.reason, p.staff_uuid, p.staff_name, p.created, p.expires, p.scope, p.silent, p.template, r.staff_uuid, r.staff_name, r.removed, r.reason, p.staff_weight FROM xbans_punishments p LEFT JOIN xbans_removals r ON r.punishment = p.id WHERE p.id = 5").use {
                    assertTrue(it.next())
                    assertEquals(3, it.getInt(2))
                    assertEquals("7a1e3a7d-1b2c-3d4e-8f90-112233445566", it.getString(3))
                    assertEquals("Language", it.getString(7))
                    assertEquals("*", it.getString(12))
                    assertEquals(Int.MAX_VALUE, it.getInt(19))
                }
                s.executeQuery("SELECT weight FROM xbans_players WHERE uuid = '5627dd98-e6be-3c21-b8a8-e92344183641'").use { assertTrue(it.next()); assertEquals(5, it.getInt(1)) }
                s.executeQuery("SELECT COUNT(*) FROM xbans_ips WHERE uuid = '5627dd98-e6be-3c21-b8a8-e92344183641'").use { assertTrue(it.next()); assertEquals(2, it.getInt(1)) }
            }
        }
    }

    private fun schemaOf(file: Path): List<String> = DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
        connection.createStatement().use { s ->
            s.executeQuery("SELECT sql FROM sqlite_master WHERE name LIKE 'xbans_%' AND name <> 'xbans_meta' ORDER BY name").use {
                val out = ArrayList<String>()
                while (it.next()) out += it.getString(1).orEmpty()
                out
            }
        }
    }
}
