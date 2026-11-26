package co.xenastudios.xmoney

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.xmoney.ledger.TestPlatform
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** The importers' readers and the import's plan, on files laid out as EssentialsX and CMI keep them. */
class ImportTest {
    private val plugins = Files.createTempDirectory("xmoney-import")
    private val platform = TestPlatform()

    @AfterTest
    fun clean() {
        platform.scheduler.close()
        plugins.toFile().deleteRecursively()
    }

    @Test
    fun essentialsUserdataIsReadByItsTopLevelKeys() {
        val userdata = Files.createDirectories(plugins.resolve("Essentials/userdata"))
        val steve = Uuid.random()
        val npc = Uuid.random()
        Files.write(userdata.resolve("$steve.yml"), "money: '1234.56'\nlast-account-name: Steve\nhomes:\n  money: 9\n".toByteArray(StandardCharsets.UTF_8))
        Files.write(userdata.resolve("$npc.yml"), "money: 5\nnpc: true\nlast-account-name: Shop\n".toByteArray(StandardCharsets.UTF_8))
        Files.write(userdata.resolve("notes.txt"), "money: 99".toByteArray(StandardCharsets.UTF_8))
        Files.write(userdata.resolve("${Uuid.random()}.yml"), "homes: {}\n".toByteArray(StandardCharsets.UTF_8))
        val read = EssentialsImport(plugins, platform).readNow()
        val found = read.found.associateBy { it.id.toString() }
        assertEquals(2, found.size)
        assertEquals("1234.56", found.getValue(steve.toString()).balance)
        assertEquals("Steve", found.getValue(steve.toString()).name)
        assertEquals(false, found.getValue(npc.toString()).player)
        assertTrue(EssentialsImport(Files.createTempDirectory("none"), platform).readNow().missing!!.contains("Essentials/userdata"))
    }

    @Test
    fun cmisSqliteIsReadByItsColumnNames() {
        Files.createDirectories(plugins.resolve("CMI"))
        val steve = Uuid.random()
        DriverManager.getConnection("jdbc:sqlite:" + plugins.resolve("CMI/cmi.sqlite.db")).use { c ->
            c.createStatement().use {
                it.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, player_uuid TEXT, username TEXT, Balance DOUBLE)")
                it.execute("INSERT INTO users (player_uuid, username, Balance) VALUES ('$steve', 'Steve', 10.5), ('not a uuid', 'X', 1)")
            }
        }
        val read = CmiImport(plugins, platform, ImportTest::class.java.classLoader).readNow()
        assertEquals(listOf(steve.toString() to "10.5"), read.found.map { it.id.toString() to it.balance })
    }

    @Test
    fun thePlanRoundsDownSkipsWhatItCantAndCountsNegativesAsZero() {
        val a = PlayerRef.of(Uuid.random())
        val found = listOf(
            BalanceSource.Found(a, "A", true, "10.00009"),
            BalanceSource.Found(a, "A again", true, "3"),
            BalanceSource.Found(PlayerRef.of(Uuid.random()), "B", true, "-5"),
            BalanceSource.Found(PlayerRef.of(Uuid.random()), "C", true, "unreadable"),
            BalanceSource.Found(PlayerRef.of(Uuid.random()), "D", true, "2000000000000"),
        )
        val plan = Imports.plan("EssentialsX", found, 1_000_000_000_000L * 10_000)
        assertEquals(listOf(100_000L, 0L), plan.lines.map { it.units })
        assertEquals(3, plan.skipped)
        assertEquals(100_000L, plan.total)
    }
}
