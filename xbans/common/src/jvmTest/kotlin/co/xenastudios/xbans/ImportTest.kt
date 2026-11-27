package co.xenastudios.xbans

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.config.YamlNode
import co.xenastudios.core.common.db.DatabaseSettings
import co.xenastudios.core.jvm.config.JvmYaml
import co.xenastudios.core.jvm.db.Drivers
import co.xenastudios.core.jvm.db.JdbcDatabase
import co.xenastudios.core.testing.RecordingLog
import co.xenastudios.xbans.api.Punishment
import co.xenastudios.xbans.api.PunishmentType
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `/xbans import`'s sources: LiteBans' and AdvancedBan's tables as their plugins write them (the
 * same SQL runs on MySQL; SQLite stands in here), the server's ban lists, EssentialsX's userdata;
 * then an import into xBans, done twice, which adds nothing the second time.
 */
class ImportTest {
    private val folder = Files.createTempDirectory("xbans-import")
    private val log = RecordingLog()
    private val steve = PlayerRef.parseOrNull("069a79f4-44e9-4726-a5be-fca90e38aaf5")!!
    private val alex = PlayerRef.parseOrNull("853c80ef-3c37-49fd-aa49-938b674adae6")!!
    private val now = System.currentTimeMillis()

    @AfterTest
    fun clean() {
        folder.toFile().deleteRecursively()
    }

    private fun database(file: Path) =
        (JdbcDatabase.open(DatabaseSettings(DatabaseSettings.Type.SQLITE, ""), file, Drivers.serverOnly(javaClass.classLoader), log, "Test") as Outcome.Ok).value

    private fun Connection.insert(sql: String, vararg values: Any?) {
        prepareStatement(sql).use { insert ->
            values.forEachIndexed { i, v -> insert.setObject(i + 1, v) }
            insert.executeUpdate()
        }
    }

    private val columns = "(id INTEGER PRIMARY KEY, uuid VARCHAR(36), ip VARCHAR(45), reason VARCHAR(2048), banned_by_uuid VARCHAR(36)," +
        " banned_by_name VARCHAR(128), removed_by_uuid VARCHAR(36), removed_by_name VARCHAR(128), removed_by_reason VARCHAR(2048)," +
        " removed_by_date TIMESTAMP, time BIGINT, until BIGINT, template INT, server_scope VARCHAR(32), server_origin VARCHAR(32)," +
        " silent BIT, ipban BIT, ipban_wildcard BIT, active BIT"
    private val row = "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"

    @Test
    fun liteBansBansMutesWarningsKicksAndLogins() {
        val file = folder.resolve("litebans.db")
        DriverManager.getConnection("jdbc:sqlite:$file").use { c ->
            c.createStatement().use { s ->
                for (table in listOf("bans", "mutes", "kicks")) s.execute("CREATE TABLE litebans_$table $columns)")
                s.execute("CREATE TABLE litebans_warnings $columns, warned BIT)")
                s.execute("CREATE TABLE litebans_history (id INTEGER PRIMARY KEY, date TIMESTAMP, name VARCHAR(16), uuid VARCHAR(36), ip VARCHAR(45))")
            }
            val mod = "61699b2e-d327-4a01-9f1e-0ea8c3f06bc6"
            // A permanent ban in force, by a moderator.
            c.insert("INSERT INTO litebans_bans VALUES $row", 1, steve.toString(), "203.0.113.9", "Hacking", mod, "Mod", null, null, null, null, now - 1000, -1, 0, "*", "lobby", 0, 0, 0, 1)
            // A temporary IP ban that was lifted early.
            c.insert("INSERT INTO litebans_bans VALUES $row", 2, alex.toString(), "198.51.100.7", "Alts", "CONSOLE", "Console", mod, "Mod", "Appealed", 1700000000000L, now - 5000, now + 86_400_000L, 0, "survival", "survival", 1, 1, 0, 0)
            // A wildcard IP ban, left out.
            c.insert("INSERT INTO litebans_bans VALUES $row", 3, null, "192.168.1.*", "Range", "CONSOLE", "Console", null, null, null, null, now - 5000, -1, 0, "*", "lobby", 0, 1, 1, 1)
            // A mute that ran out on its own.
            c.insert("INSERT INTO litebans_mutes VALUES $row", 1, steve.toString(), null, "Spam", "CONSOLE", "Console", null, "#expired", null, null, now - 100_000, now - 50_000, 0, "global", "lobby", 0, 0, 0, 0)
            c.insert("INSERT INTO litebans_warnings VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", 1, steve.toString(), null, "Language", "CONSOLE", "Console", null, null, null, null, now - 1000, 0, 0, "*", "lobby", 0, 0, 0, 1, 1)
            c.insert("INSERT INTO litebans_kicks VALUES $row", 1, alex.toString(), null, "Calm down", "CONSOLE", "Console", null, null, null, null, now - 1000, 0, 0, "*", "lobby", 0, 0, 0, 0)
            c.insert("INSERT INTO litebans_history VALUES (?, ?, ?, ?, ?)", 1, now - 2000, "Steve", steve.toString(), "203.0.113.9")
            c.insert("INSERT INTO litebans_history VALUES (?, ?, ?, ?, ?)", 2, now - 3000, "Alex", alex.toString(), "203.0.113.9")
        }
        val db = database(file)
        val haul = try {
            FileImports.LiteBans(folder, Drivers.serverOnly(javaClass.classLoader), log).readFrom(db, "litebans_", Punishment.GLOBAL, now)
        } finally {
            db.close().get(15, TimeUnit.SECONDS)
        }
        assertNull(haul.failed)
        assertEquals(2, haul.count(PunishmentType.BAN))
        assertEquals(1, haul.wildcards)
        assertEquals(listOf(1L, 1L, 1L), listOf(haul.count(PunishmentType.MUTE), haul.count(PunishmentType.WARN), haul.count(PunishmentType.KICK)))
        assertEquals(2, haul.players())
        val lifted = haul.rows.first { it.key == "litebans:bans:2" }.entry
        assertEquals("Mod", lifted.punishment.removal!!.staffName)
        assertEquals("survival", lifted.punishment.scope)
        assertTrue(lifted.punishment.ipBased)
        val ban = haul.rows.first { it.key == "litebans:bans:1" }.entry
        assertNull(ban.punishment.expires)
        assertEquals("Steve", ban.punishment.targetName)
        assertNull(haul.rows.first { it.key == "litebans:mutes:1" }.entry.punishment.removal, "an expired mute ended on its own")

        // Into xBans: what's in force is in force, and importing again adds nothing.
        val h = Harness().start()
        try {
            val backend = h.backend
            val first = h.platform.await(Importing(h.plugin, backend, haul).write())
            assertEquals(5L to 0L, first)
            assertNotNull(h.plugin.banOnJoin(steve, null))
            assertEquals(0L to 5L, h.platform.await(Importing(h.plugin, backend, haul).write()))
        } finally {
            h.stop()
            h.folder.toFile().deleteRecursively()
        }
    }

    @Test
    fun advancedBansHistoryWithWhatsStillInForce() {
        val file = folder.resolve("advancedban.db")
        val cols = "(id INTEGER PRIMARY KEY, name VARCHAR(16), uuid VARCHAR(35), reason VARCHAR(100), operator VARCHAR(16), punishmentType VARCHAR(16), start BIGINT, end BIGINT, calculation VARCHAR(50))"
        DriverManager.getConnection("jdbc:sqlite:$file").use { c ->
            c.createStatement().use { s ->
                s.execute("CREATE TABLE Punishments $cols")
                s.execute("CREATE TABLE PunishmentHistory $cols")
            }
            val dashless = steve.toString().replace("-", "")
            // A ban in force, in both tables; a mute lifted early (only in the history); a note.
            for (table in listOf("Punishments", "PunishmentHistory")) {
                c.insert("INSERT INTO $table (name, uuid, reason, operator, punishmentType, start, end, calculation) VALUES (?, ?, ?, ?, ?, ?, ?, ?)", "Steve", dashless, "Xray", "Mod", "BAN", now - 1000, -1, null)
            }
            c.insert("INSERT INTO PunishmentHistory (name, uuid, reason, operator, punishmentType, start, end, calculation) VALUES (?, ?, ?, ?, ?, ?, ?, ?)", "Steve", dashless, "Caps", "CONSOLE", "TEMP_MUTE", now - 2000, now + 3_600_000, null)
            c.insert("INSERT INTO PunishmentHistory (name, uuid, reason, operator, punishmentType, start, end, calculation) VALUES (?, ?, ?, ?, ?, ?, ?, ?)", "Steve", dashless, "Watch him", "Mod", "NOTE", now - 3000, -1, null)
            c.insert("INSERT INTO PunishmentHistory (name, uuid, reason, operator, punishmentType, start, end, calculation) VALUES (?, ?, ?, ?, ?, ?, ?, ?)", "198.51.100.7", "198.51.100.7", "Proxy", "CONSOLE", "IP_BAN", now - 4000, -1, null)
        }
        val db = database(file)
        val haul = try {
            FileImports.AdvancedBan(folder, Drivers.serverOnly(javaClass.classLoader), log).readFrom(db, "", Punishment.GLOBAL, now)
        } finally {
            db.close().get(15, TimeUnit.SECONDS)
        }
        assertNull(haul.failed)
        assertEquals(2, haul.count(PunishmentType.BAN))
        assertEquals(1, haul.count(PunishmentType.MUTE))
        val ban = haul.rows.first { it.entry.type == PunishmentType.BAN && !it.entry.punishment.ipBased }.entry
        assertEquals(steve, ban.punishment.target)
        assertNull(ban.punishment.removal, "still in Punishments, so in force")
        assertEquals("Mod", ban.punishment.staffName)
        assertNotNull(haul.rows.first { it.entry.type == PunishmentType.MUTE }.entry.punishment.removal, "gone from Punishments before it ended: lifted")
        val ip = haul.rows.first { it.entry.punishment.ipBased }.entry
        assertEquals("198.51.100.7", ip.ip)
        assertEquals("198.51.x.x", ip.punishment.targetName)
    }

    @Test
    fun theServersBanListsAndEssentialsUserdata() {
        Files.write(folder.resolve("banned-players.json"), """
            [{"uuid": "$steve", "name": "Steve", "created": "2025-01-01 10:00:00 +0000", "source": "Server", "expires": "forever", "reason": "Griefing"},
             {"uuid": "$alex", "name": "Alex", "created": "2025-01-01 10:00:00 +0000", "source": "Server", "expires": "2025-01-02 10:00:00 +0000", "reason": "Over"}]
        """.trimIndent().toByteArray(StandardCharsets.UTF_8))
        Files.write(folder.resolve("banned-ips.json"), """[{"ip": "198.51.100.7", "created": "2025-01-01 10:00:00 +0000", "source": "Console", "expires": "forever", "reason": "VPN"}]""".toByteArray(StandardCharsets.UTF_8))
        val vanilla = FileImports.Vanilla(folder).read(Punishment.GLOBAL, now, ImportContext())
        assertNull(vanilla.failed)
        assertEquals(listOf("Griefing", "VPN"), vanilla.rows.map { it.entry.punishment.reason }, "Alex's ban had ended")
        assertTrue(vanilla.rows[1].key.startsWith("vanilla:ip:{ip}:"))

        val users = Files.createDirectories(folder.resolve("Essentials/userdata"))
        Files.write(users.resolve("$steve.yml"), "last-account-name: Steve\nip-address: 203.0.113.9\nmuted: true\nmute-reason: Spam\ntimestamps:\n  login: ${now - 1000}\n  mute: ${now + 3_600_000}\n".toByteArray(StandardCharsets.UTF_8))
        Files.write(users.resolve("$alex.yml"), "lastAccountName: Alex\nmuted: false\n".toByteArray(StandardCharsets.UTF_8))
        val essentials = FileImports.Essentials(folder.resolve("Essentials")).read(Punishment.GLOBAL, now, ImportContext())
        assertEquals(1, essentials.count(PunishmentType.MUTE))
        assertEquals(2, essentials.players())
        assertEquals("203.0.113.9", essentials.seen.first { it.id == steve }.ip)
        assertEquals("there's no plugins/LiteBans/config.yml.", FileImports.LiteBans(folder.resolve("nope"), Drivers.serverOnly(javaClass.classLoader), log).read("*", now, ImportContext()).failed)
        // YAML is read with the core's parser, so this is what a server's file looks like to it.
        assertTrue((JvmYaml.read("a: 1") as Outcome.Ok).value is YamlNode.Mapping)
    }
}
