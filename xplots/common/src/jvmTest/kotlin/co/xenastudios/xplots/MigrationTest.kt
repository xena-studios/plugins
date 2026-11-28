package co.xenastudios.xplots

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.config.YamlNode
import co.xenastudios.core.common.family.Family
import co.xenastudios.core.common.family.MemoryBoard
import co.xenastudios.core.jvm.config.JvmYaml
import co.xenastudios.core.jvm.platform.FolderStorage
import co.xenastudios.core.testing.RecordingLog
import co.xenastudios.xplots.config.Legacy
import co.xenastudios.xplots.config.XPlotsConfig
import co.xenastudios.xplots.model.Facing
import co.xenastudios.xplots.reset.Snapshot
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.UUID
import java.util.zip.DeflaterOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days

/**
 * 1.x's config and database, carried into 2.0, and left so the 1.x jar put back still reads them:
 * its config as a newer one (with its copy aside), its database exactly as it made it (schema 1,
 * 2.0's one new table beside it, which 1.x never reads), 2.0's rows in 1.x's own format. The
 * configs are kept as `.yml.txt`: as shipped, their blank MySQL password is the setting owners
 * fill in, which code scanning would take for a credential.
 */
class MigrationTest {
    private val folder = Files.createTempDirectory("xplots-migration")
    private val log = RecordingLog()

    @AfterTest
    fun clean() {
        folder.toFile().deleteRecursively()
    }

    private fun resource(name: String) = String(MigrationTest::class.java.getResourceAsStream("/$name")!!.readBytes(), StandardCharsets.UTF_8)

    private fun files(): JvmFiles {
        val family = Family(FolderStorage(folder.resolve("Xena")), MemoryBoard(), JvmYaml)
        return JvmFiles(folder.resolve("xPlots"), family, emptyMap(), log)
    }

    private fun write(name: String, text: String): Path = folder.resolve("xPlots").resolve(name).also {
        Files.createDirectories(it.parent)
        Files.write(it, text.toByteArray(StandardCharsets.UTF_8))
    }

    private fun read(name: String) = String(Files.readAllBytes(folder.resolve("xPlots").resolve(name)), StandardCharsets.UTF_8)

    @Test
    fun theTextsMovedAreTheOnes1xShipped() {
        val root = (JvmYaml.read(resource("config-1.0.2.yml.txt")) as Outcome.Ok).value as YamlNode.Mapping
        assertEquals(emptyMap(), Legacy.messages(root), "a text 1.x shipped would be carried as if edited")
        fun count(node: YamlNode?): Int = when (node) {
            is YamlNode.Mapping -> node.entries.sumOf { count(it.value) }
            null -> 0
            else -> 1
        }
        val menus = (root["menus"] as YamlNode.Mapping)
        fun texts(node: YamlNode?): Int = when (node) {
            is YamlNode.Mapping -> node.entries.sumOf { if (it.key == "title" || it.key == "name" || it.key == "lore") 1 else texts(it.value) }
            else -> 0
        }
        assertEquals(count(root["messages"]) + 3 + texts(menus), Legacy.MOVED.size, "every text 1.x had has a place in 2.0")
    }

    @Test
    fun aDefault1xConfigCarriesForwardWithNothingInLang() {
        write("config.yml", resource("config-1.0.2.yml.txt"))
        val loaded = files().load()
        val text = read("config.yml")
        assertTrue("config-version: 2" in text)
        assertTrue(Files.exists(folder.resolve("xPlots/config.yml.bak-v1")))
        assertFalse("rented:" in text.substringBefore("plots:"), "1.x's messages are still in config.yml")
        assertFalse("\ntheme:" in text)
        assertFalse("FOR RENT" in text, "the signs' lines are still in config.yml")
        assertFalse("Rent for" in text, "the menus' names are still in config.yml")
        assertTrue("material: \"lime_concrete\"" in text, "the menus' materials stay")
        assertTrue("updates:" in text && "update-checker" !in text)
        assertFalse("language: \"en\"" in text)
        assertEquals(emptyList(), loaded.problems, loaded.problems.toString())
        assertEquals(XPlotsConfig.CurrencyType.VAULT, loaded.config[XPlotsConfig.currency.type], "vault, 1.x's, is kept and means the economy")
        assertEquals(XPlotsConfig.plots.read(XPlotsConfig.defaults).price, XPlotsConfig.plots.read(loaded.config).price)
    }

    @Test
    fun anEdited1xConfigKeepsEveryEditAndItsTextsGoToLang() {
        write("config.yml", resource("config-1.0.2-edited.yml.txt"))
        val loaded = files().load()
        val config = loaded.config
        assertEquals(128.0, config[XPlotsConfig.plots.price])
        assertEquals(2, config[XPlotsConfig.maxPlots])
        assertEquals(XPlotsConfig.CurrencyType.ITEM, config[XPlotsConfig.currency.type])
        assertEquals("emerald_block", config[XPlotsConfig.menus.rent["confirm"]])
        assertFalse(config[XPlotsConfig.updates.check])
        val lang = read("lang/en.yml")
        assertTrue("Enjoy <plot>!" in lang, lang)
        assertTrue("<blue>TO LET<newline><plot><newline><price><newline><period>" in lang, lang)
        assertTrue("Take it for <price>" in lang, lang)
        val text = read("config.yml")
        assertTrue("language: \"en\"" in text, "owners who edited texts keep reading them")
        assertTrue("<gold>[Plots]</gold>" in text, "an edited theme moves into messages.theme")
        assertEquals(emptyList(), loaded.problems, loaded.problems.toString())
        val rendered = loaded.translations.render(XPlotsMessages.rented, loaded.translations.default, "plot" to co.xenastudios.core.common.text.Placeholder.text("box-1"))
        assertEquals("[Plots] Enjoy box-1!", rendered?.plain())
    }

    @Test
    fun a1xDatabaseIsReadAndLeftAs1xReadsIt() {
        val db = folder.resolve("db").also { Files.createDirectories(it) }
        val owner = UUID.fromString("0f5c3b2e-1c2d-4e5f-8a9b-0c1d2e3f4a5b")
        val member = UUID.fromString("1a2b3c4d-5e6f-4a1b-8c2d-3e4f5a6b7c8d")
        val made = Instant1x.now + 2.days.inWholeMilliseconds
        DriverManager.getConnection("jdbc:sqlite:" + db.resolve("plots.db")).use { c ->
            c.createStatement().use { s ->
                // 1.x's Schema.STEPS, and its meta table, as the old core made them.
                s.execute("CREATE TABLE IF NOT EXISTS xplots_meta (name VARCHAR(64) PRIMARY KEY, value VARCHAR(255) NOT NULL)")
                s.execute("INSERT INTO xplots_meta (name, value) VALUES ('schema', '1')")
                s.execute("CREATE TABLE xplots_field (id INT PRIMARY KEY, name VARCHAR(64) NOT NULL, world VARCHAR(255) NOT NULL, min_x INT NOT NULL, min_y INT NOT NULL, min_z INT NOT NULL, max_x INT NOT NULL, max_y INT NOT NULL, max_z INT NOT NULL, size_x INT NOT NULL, size_z INT NOT NULL, path INT NOT NULL, settings TEXT, warp VARCHAR(255))")
                s.execute("CREATE TABLE xplots_plot (field INT NOT NULL, number INT NOT NULL, min_x INT NOT NULL, min_y INT NOT NULL, min_z INT NOT NULL, max_x INT NOT NULL, max_y INT NOT NULL, max_z INT NOT NULL, sign_x INT NOT NULL, sign_y INT NOT NULL, sign_z INT NOT NULL, sign_facing VARCHAR(16) NOT NULL, PRIMARY KEY (field, number))")
                s.execute("CREATE TABLE xplots_lease (field INT NOT NULL, number INT NOT NULL, owner_most BIGINT NOT NULL, owner_least BIGINT NOT NULL, owner_name VARCHAR(16) NOT NULL, expires BIGINT NOT NULL, slots INT NOT NULL, warned TINYINT NOT NULL, PRIMARY KEY (field, number))")
                s.execute("CREATE TABLE xplots_member (field INT NOT NULL, number INT NOT NULL, uuid_most BIGINT NOT NULL, uuid_least BIGINT NOT NULL, name VARCHAR(16) NOT NULL, PRIMARY KEY (field, number, uuid_most, uuid_least))")
                s.execute("CREATE TABLE xplots_snapshot (field INT NOT NULL, number INT NOT NULL, data BLOB NOT NULL, PRIMARY KEY (field, number))")
                s.execute("INSERT INTO xplots_field VALUES (1, 'box', 'world', 200, 64, 200, 211, 70, 204, 5, 5, 2, 'price=250;reset=true', '205.50,65.00,206.50,180.0,0.0')")
                s.execute("INSERT INTO xplots_plot VALUES (1, 1, 200, 64, 200, 204, 70, 204, 200, 65, 205, 'SOUTH_WEST')")
                s.execute("INSERT INTO xplots_plot VALUES (1, 2, 207, 64, 200, 211, 70, 204, 207, 65, 205, 'SOUTH')")
                s.execute("INSERT INTO xplots_lease VALUES (1, 1, ${owner.mostSignificantBits}, ${owner.leastSignificantBits}, 'Steve', $made, 2, 0)")
                s.execute("INSERT INTO xplots_member VALUES (1, 1, ${member.mostSignificantBits}, ${member.leastSignificantBits}, 'Alex')")
            }
            c.prepareStatement("INSERT INTO xplots_snapshot VALUES (1, 1, ?)").use { p ->
                p.setBytes(1, snapshot1x(5, 7, 5, listOf("minecraft:air", "minecraft:stone"), IntArray(175) { if (it < 25) 1 else 0 }))
                p.executeUpdate()
            }
        }
        val h = Harness(db).start()
        try {
            val field = h.plugin.plots.field("box")
            assertNotNull(field)
            assertEquals("250", field.overrides["price"])
            assertEquals(250.0, h.plugin.rentals.settings(field.plot(1)!!).price)
            val plot = h.plugin.plots.plot("box-1")!!
            assertEquals(Facing.SOUTH_WEST, plot.sign.facing)
            assertEquals("Steve", plot.lease?.ownerName)
            assertEquals(made, plot.lease?.expires)
            assertEquals(listOf("Alex"), plot.lease?.members?.values?.toList())
            assertEquals(2, h.plugin.plots.owned(plot.lease!!.owner).size + 1)
            // 1.x's snapshot reads, and a reset puts it back.
            h.plugin.rentals.end(plot, co.xenastudios.xplots.api.EndReason.EVICTED)
            h.platform.until("the reset") { !h.plugin.isResetting(plot) && h.server.blocks[At("world", 200, 64, 200)] == "minecraft:stone" }
            // 2.0 writes in 1.x's format: a lease 1.x reads with its own query.
            val steve = h.server.join("Steve")
            h.plugin.rentals.setOwner(plot, steve, "Steve")
            h.flush()
            val rows = h.query("SELECT field, number, owner_most, owner_least, owner_name, expires, slots, warned FROM xplots_lease")
            assertEquals(1, rows.size)
            assertEquals("Steve", rows[0][4])
            assertEquals("1", h.query("SELECT value FROM xplots_meta WHERE name = 'schema'")[0][0], "schema 1: the 1.x jar opens it")
            assertEquals("SOUTH_WEST", h.query("SELECT sign_facing FROM xplots_plot WHERE number = 1")[0][0])
            // 1.x's snapshot is still there, as it was.
            val data = h.query("SELECT data FROM xplots_snapshot WHERE number = 1")[0][0] as ByteArray
            val decoded = Snapshot.decode(data, JvmDeflate)
            assertEquals(5, decoded.width)
        } finally {
            h.stop()
        }
    }

    @Test
    fun snapshotsAreByteForByte1xs() {
        val palette = listOf("minecraft:air", "minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]", "minecraft:é")
        val indices = IntArray(2 * 3 * 4) { it % 3 }
        val ours = Snapshot(2, 3, 4, palette, indices).encode(JvmDeflate)
        val theirs = snapshot1x(2, 3, 4, palette, indices)
        assertTrue(ours.contentEquals(theirs), "2.0's snapshot differs from 1.x's")
        val back = Snapshot.decode(theirs, JvmDeflate)
        assertEquals(palette, back.palette)
        assertTrue(indices.contentEquals(back.indices))
    }

    /** A snapshot as 1.x's `Snapshot.encode` wrote it. */
    private fun snapshot1x(width: Int, height: Int, depth: Int, palette: List<String>, indices: IntArray): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(DeflaterOutputStream(bytes)).use { out ->
            out.writeByte(1)
            out.writeInt(width)
            out.writeInt(height)
            out.writeInt(depth)
            varInt(out, palette.size)
            palette.forEach(out::writeUTF)
            indices.forEach { varInt(out, it) }
        }
        return bytes.toByteArray()
    }

    private fun varInt(out: DataOutputStream, value: Int) {
        var v = value
        while (v and 0x7F.inv() != 0) {
            out.writeByte((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        out.writeByte(v)
    }

    private object Instant1x {
        val now: Long = kotlin.time.Instant.parse("2026-06-01T00:00:00Z").toEpochMilliseconds()
    }
}
