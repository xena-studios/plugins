package co.xenastudios.xmoney

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.config.YamlNode
import co.xenastudios.core.common.db.Migrations
import co.xenastudios.core.common.family.Family
import co.xenastudios.core.jvm.config.JvmYaml
import co.xenastudios.core.jvm.platform.FolderStorage
import co.xenastudios.core.testing.RecordingLog
import co.xenastudios.xmoney.config.Legacy
import co.xenastudios.xmoney.config.XMoneyConfig
import co.xenastudios.xmoney.ledger.Bench
import co.xenastudios.xmoney.ledger.CAUSE
import co.xenastudios.xmoney.ledger.TestPlatform
import co.xenastudios.xmoney.ledger.await
import co.xenastudios.xmoney.ledger.ok
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
 * 1.x's configs and databases, carried into 2.0, and left so the 1.x jar put back still reads
 * them: its config as a newer one (with its copy aside), its database exactly as it made it.
 */
class MigrationTest {
    private val folder = Files.createTempDirectory("xmoney-migration")
    private val log = RecordingLog()

    @AfterTest
    fun clean() {
        folder.toFile().deleteRecursively()
    }

    private fun resource(name: String) = String(MigrationTest::class.java.getResourceAsStream("/$name")!!.readBytes(), StandardCharsets.UTF_8)

    private fun files(): JvmFiles {
        val family = Family(FolderStorage(folder.resolve("Xena")), co.xenastudios.core.common.family.MemoryBoard(), JvmYaml)
        return JvmFiles(folder.resolve("xMoney"), family, emptyMap(), log)
    }

    private fun write(name: String, text: String): Path = folder.resolve("xMoney").resolve(name).also {
        Files.createDirectories(it.parent)
        Files.write(it, text.toByteArray(StandardCharsets.UTF_8))
    }

    @Test
    fun theMessagesMovedAreTheOnes1xShipped() {
        for (release in listOf("config-1.0.0.yml", "config-1.0.1.yml")) {
            val root = (JvmYaml.read(resource(release)) as co.xenastudios.core.common.Outcome.Ok).value as YamlNode.Mapping
            assertEquals(emptyMap(), Legacy.messages(root), "$release: a message 1.x shipped would be carried as if edited")
            assertEquals(1, Legacy.version(root))
        }
    }

    @Test
    fun aDefault1xConfigCarriesForwardWithNothingInLang() {
        write("config.yml", resource("config-1.0.1.yml"))
        val loaded = files().load()
        val config = loaded.config
        assertTrue("config-version: 2" in String(Files.readAllBytes(folder.resolve("xMoney/config.yml"))))
        assertTrue(Files.exists(folder.resolve("xMoney/config.yml.bak-v1")))
        val text = String(Files.readAllBytes(folder.resolve("xMoney/config.yml")))
        assertFalse("messages:\n  error:" in text, "1.x's messages are still in config.yml")
        assertFalse("\ntheme:" in text)
        assertTrue("updates:" in text && "update-checker" !in text)
        assertEquals(emptyList(), loaded.problems, loaded.problems.toString())
    }

    @Test
    fun anEdited1xConfigKeepsEveryEditAndItsMessagesGoToLang() {
        write("config.yml", resource("config-1.0.1-edited.yml"))
        val loaded = files().load()
        val config = loaded.config
        assertEquals("€", config[XMoneyConfig.currency.symbol])
        assertEquals(255_000L, config[XMoneyConfig.startingBalance].units)
        assertEquals(listOf("bal", "coins"), config[XMoneyConfig.commands.balance.aliases])
        assertEquals(5, config[XMoneyConfig.network.checkEvery])
        assertFalse(config[XMoneyConfig.updates.check])
        val english = String(Files.readAllBytes(folder.resolve("xMoney/lang/en.yml")), StandardCharsets.UTF_8)
        assertTrue("<gold>Your purse: <yellow><balance>" in english, english)
        val text = String(Files.readAllBytes(folder.resolve("xMoney/config.yml")))
        assertTrue("language: \"en\"" in text || "language: en" in text, text.substringAfter("messages:").take(300))
        assertTrue("highlight: \"<gold>\"" in text, "the edited theme colour wasn't carried")
        assertEquals("Your purse: 12", loaded.translations.render(XMoneyMessages.balanceOwn, loaded.translations.languageFor(null), mapOf("balance" to co.xenastudios.core.common.text.Placeholder.text("12")))!!.plain())
    }

    @Test
    fun a1xDatabaseIsReadAsItIsAndLeftAs1xMadeIt() {
        val file = folder.resolve("money.db")
        DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
            connection.createStatement().use { statement ->
                for (sql in resource("money-1.0.1.sql").lines().filter { it.isNotBlank() && !it.startsWith("--") }) statement.execute(sql)
            }
        }
        val schema = { schemaOf(file) }
        val before = schema()
        val platform = TestPlatform()
        val database = Bench.sqlite(file, platform)
        try {
            val ledger = Bench.ledger(database, platform)
            val steve = ledger.accounts[PlayerRef.parseOrNull("8667ba71-b85a-4004-af54-457a9734eed7")!!]!!
            assertEquals(1_250_000, steve.balance)
            assertEquals(steve, ledger.accounts.named("steve"))
            assertFalse(ledger.accounts.named("town-Spawn")!!.player)
            assertEquals(listOf("Steve", "Alex"), ledger.accounts.top(1, 10).map { it.account.name })
            // 2.0 writes as 1.x did: a balance and its ledger row.
            ledger.deposit(steve, 10_000, CAUSE).await()
            ledger.flush().await()
            assertEquals(1, database.session { Migrations.run(it, database.sql, co.xenastudios.xmoney.ledger.Tables.MIGRATIONS) }.await().ok())
        } finally {
            database.close().await()
            platform.scheduler.close()
        }
        // Every table and index 1.x made, as it made them; meta still says schema 1, so 1.x opens it.
        assertEquals(before, schema())
        DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
            connection.createStatement().use { s ->
                s.executeQuery("SELECT value FROM xmoney_meta WHERE name = 'schema'").use { assertTrue(it.next()); assertEquals("1", it.getString(1)) }
                // 1.x's own reads (Store.readAll, Store.history), word for word.
                s.executeQuery("SELECT uuid, balance FROM xmoney_balances WHERE currency = 'default'").use { var n = 0; while (it.next()) n++; assertEquals(3, n) }
                s.executeQuery("SELECT uuid, name, player FROM xmoney_accounts").use { var n = 0; while (it.next()) n++; assertEquals(3, n) }
                s.executeQuery("SELECT id, time, amount, balance, kind, source, reason, other FROM xmoney_transactions WHERE uuid = '8667ba71-b85a-4004-af54-457a9734eed7' ORDER BY id DESC LIMIT 1 OFFSET 0").use {
                    assertTrue(it.next())
                    assertEquals(10_000, it.getLong(3))
                    assertEquals(1_260_000, it.getLong(4))
                }
            }
        }
    }

    private fun schemaOf(file: Path): List<String> = DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
        connection.createStatement().use { s ->
            s.executeQuery("SELECT sql FROM sqlite_master WHERE name LIKE 'xmoney_%' AND name <> 'xmoney_meta' ORDER BY name").use {
                val out = ArrayList<String>()
                while (it.next()) out += it.getString(1).orEmpty()
                out
            }
        }
    }
}
