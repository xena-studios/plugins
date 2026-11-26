package co.xenastudios.xteleport

import co.xenastudios.core.api.Location
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.WorldRef
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.config.YamlNode
import co.xenastudios.core.common.db.DatabaseSettings
import co.xenastudios.core.common.db.Migrations
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
import co.xenastudios.xteleport.api.Home
import co.xenastudios.xteleport.config.Legacy
import co.xenastudios.xteleport.config.XTeleportConfig
import co.xenastudios.xteleport.store.Store
import co.xenastudios.xteleport.store.Tables
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
import kotlin.test.assertTrue

/**
 * 1.x's configs and databases, carried into 2.0, and left so the 1.x jar put back still reads
 * them: its config as a newer one (with its copy aside), its database exactly as it made it.
 * 1.0.0 and 1.0.1 shipped the same config and schema. The configs are kept as `.yml.txt`: as
 * shipped, their blank MySQL password is the setting owners fill in, which code scanning would
 * take for a credential.
 */
class MigrationTest {
    private val folder = Files.createTempDirectory("xteleport-migration")
    private val log = RecordingLog()

    @AfterTest
    fun clean() {
        folder.toFile().deleteRecursively()
    }

    private fun resource(name: String) = String(MigrationTest::class.java.getResourceAsStream("/$name")!!.readBytes(), StandardCharsets.UTF_8)

    private fun files(): JvmFiles {
        val family = Family(FolderStorage(folder.resolve("Xena")), MemoryBoard(), JvmYaml)
        return JvmFiles(folder.resolve("xTeleport"), family, emptyMap(), log)
    }

    private fun write(name: String, text: String): Path = folder.resolve("xTeleport").resolve(name).also {
        Files.createDirectories(it.parent)
        Files.write(it, text.toByteArray(StandardCharsets.UTF_8))
    }

    private fun read(name: String) = String(Files.readAllBytes(folder.resolve("xTeleport").resolve(name)), StandardCharsets.UTF_8)

    private fun <T> CompletableFuture<T>.await(): T = get(15, TimeUnit.SECONDS)

    @Test
    fun theMessagesMovedAreTheOnes1xShipped() {
        val root = (JvmYaml.read(resource("config-1.0.1.yml.txt")) as Outcome.Ok).value as YamlNode.Mapping
        assertEquals(emptyMap(), Legacy.messages(root), "a message 1.x shipped would be carried as if edited")
        // Every message setting 1.x had has a place in 2.0.
        val sections = listOf("error", "teleport", "homes", "tpa", "warps", "spawn", "rtp")
        val messages = (root["messages"] as YamlNode.Mapping)
        var count = 0
        for (section in sections) count += ((messages[section] as YamlNode.Mapping).entries.size)
        assertEquals(count, Legacy.MOVED.size)
    }

    @Test
    fun aDefault1xConfigCarriesForwardWithNothingInLang() {
        write("config.yml", resource("config-1.0.1.yml.txt"))
        val loaded = files().load()
        val text = read("config.yml")
        assertTrue("config-version: 2" in text)
        assertTrue(Files.exists(folder.resolve("xTeleport/config.yml.bak-v1")))
        assertFalse("messages:\n  error:" in text, "1.x's messages are still in config.yml")
        assertFalse("\ntheme:" in text)
        assertTrue("updates:" in text && "update-checker" !in text)
        // Nothing was edited, so players keep their own language.
        assertFalse("language: \"en\"" in text)
        assertEquals(emptyList(), loaded.problems, loaded.problems.toString())
        assertEquals(XTeleportConfig.defaults, loaded.config)
    }

    @Test
    fun anEdited1xConfigKeepsEveryEditAndItsMessagesGoToLang() {
        write("config.yml", resource("config-1.0.1-edited.yml.txt"))
        val loaded = files().load()
        val config = loaded.config
        assertEquals(listOf("tpyes", "tpok"), config[XTeleportConfig.commands.tpaccept.aliases])
        assertEquals(120, config[XTeleportConfig.commands.rtp.cooldown])
        assertEquals(25.5, config[XTeleportConfig.commands.rtp.cost])
        assertEquals(5, config[XTeleportConfig.teleports.warmup])
        assertEquals(12, config[XTeleportConfig.homes.limits].getValue("mvp").homes)
        assertEquals("ranks.mvp", config[XTeleportConfig.homes.limits].getValue("mvp").permission)
        assertFalse(config[XTeleportConfig.rtp.worlds].getValue("world_the_end").enabled)
        assertFalse(config[XTeleportConfig.updates.check])
        val english = read("lang/en.yml")
        assertTrue("<gold>Home <yellow><home></yellow> saved!" in english, english)
        assertTrue("<red>Nope." in english, english)
        val text = read("config.yml")
        assertTrue("language: \"en\"" in text || "language: en" in text, text.substringAfter("messages:").take(300))
        assertTrue("highlight: \"<gold>\"" in text, "the edited theme colour wasn't carried")
        val english2 = loaded.translations.languageFor(null)
        assertEquals("Home base saved!", loaded.translations.render(XTeleportMessages.homeSet, english2, mapOf("home" to Placeholder.text("base")))!!.plain())
        assertEquals("Nope.", loaded.translations.render(CoreMessages.commandNoPermission, english2, emptyMap())!!.plain())
        assertEquals(emptyList(), loaded.problems.filter { "lang" !in it }, loaded.problems.toString())
    }

    @Test
    fun a1xDatabaseIsReadAsItIsAndLeftAs1xMadeIt() {
        val file = folder.resolve("xteleport.db")
        DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
            connection.createStatement().use { statement ->
                for (sql in resource("teleport-1.0.1.sql").lines().filter { it.isNotBlank() && !it.startsWith("--") }) statement.execute(sql)
            }
        }
        val before = schemaOf(file)
        val database = (JdbcDatabase.open(DatabaseSettings(DatabaseSettings.Type.SQLITE, "xteleport_"), file, Drivers.serverOnly(javaClass.classLoader), log, "Test") as Outcome.Ok).value
        try {
            assertEquals(1, (database.migrate(Tables.MIGRATIONS).await() as Outcome.Ok).value)
            val store = Store(database)
            val steve = PlayerRef.parseOrNull("8667ba71-b85a-4004-af54-457a9734eed7")!!
            val alex = PlayerRef.parseOrNull("ec561538-f3fd-461d-aff5-086b22154bce")!!
            val homes = (store.loadPlayer(steve).await() as Outcome.Ok).value
            assertEquals(listOf("Base", "mine"), homes.homes.map { it.name })
            assertEquals(Location(WorldRef.of("world"), 100.5, 64.0, -20.5, 90f, 10f), homes.homes[0].location)
            assertFalse(homes.tpaOff)
            assertTrue((store.loadPlayer(alex).await() as Outcome.Ok).value.tpaOff)
            val server = (store.loadServer().await() as Outcome.Ok).value
            assertEquals(listOf("arena", "Shop"), server.warps.map { it.name })
            assertEquals(65.0, server.spawn!!.location.y)
            // 2.0 writes as 1.x did.
            assertTrue(store.saveHome(Home(steve, "Farm", Location(WorldRef.of("world"), 1.0, 2.0, 3.0)), 1).await() is Outcome.Ok)
            assertTrue(store.setTpaOff(alex, false).await() is Outcome.Ok)
            assertEquals(1, (database.session { Migrations.run(it, database.sql, Tables.MIGRATIONS) }.await() as Outcome.Ok).value)
        } finally {
            database.close().await()
        }
        // Every table 1.x made, as it made them; meta still says schema 1, so 1.x opens it.
        assertEquals(before, schemaOf(file))
        DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
            connection.createStatement().use { s ->
                s.executeQuery("SELECT value FROM xteleport_meta WHERE name = 'schema'").use { assertTrue(it.next()); assertEquals("1", it.getString(1)) }
                // 1.x's own read (Store.loadPlayer), word for word.
                s.executeQuery("SELECT name, world, x, y, z, yaw, pitch FROM xteleport_homes WHERE owner = '8667ba71-b85a-4004-af54-457a9734eed7' AND server = '' ORDER BY name_key").use {
                    val names = ArrayList<String>()
                    while (it.next()) names += it.getString(1)
                    assertEquals(listOf("Base", "Farm", "mine"), names)
                }
                s.executeQuery("SELECT tpa_off FROM xteleport_players WHERE uuid = 'ec561538-f3fd-461d-aff5-086b22154bce'").use { assertFalse(it.next()) }
            }
        }
    }

    private fun schemaOf(file: Path): List<String> = DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
        connection.createStatement().use { s ->
            s.executeQuery("SELECT sql FROM sqlite_master WHERE name LIKE 'xteleport_%' AND name <> 'xteleport_meta' ORDER BY name").use {
                val out = ArrayList<String>()
                while (it.next()) out += it.getString(1).orEmpty()
                out
            }
        }
    }
}
