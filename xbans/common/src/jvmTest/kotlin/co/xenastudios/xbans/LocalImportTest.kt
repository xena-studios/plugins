package co.xenastudios.xbans

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.db.DbError
import co.xenastudios.core.jvm.db.DriverFetcher
import co.xenastudios.core.jvm.db.DriverLibrary
import co.xenastudios.core.jvm.db.Drivers
import co.xenastudios.core.jvm.db.IsolatedDriver
import co.xenastudios.core.jvm.db.OnDemandDriverFetcher
import co.xenastudios.core.loader.Library
import co.xenastudios.core.loader.LibraryCache
import co.xenastudios.core.loader.OnDemandLibraries
import co.xenastudios.core.loader.Sha256
import co.xenastudios.core.testing.RecordingLog
import co.xenastudios.xbans.api.Punishment
import co.xenastudios.xbans.api.PunishmentType
import java.io.File
import java.lang.ref.WeakReference
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.sql.Connection
import java.sql.Driver
import java.sql.Timestamp
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `/xbans import` from LiteBans' H2 file and AdvancedBan's HSQLDB files, as those plugins leave
 * them: written by the drivers they ship (H2 1.4.199, 2.0.206 and 2.2.224 for H2's three file
 * formats, HSQLDB 2.4.0, AdvancedBan's) with their own tables, then read the way a server does,
 * with the driver each needs loaded in a class loader of its own (the build hands every jar over
 * by path, `xbans.test.drivers`). The source's files are left as they were, importing twice adds
 * nothing, a file in use isn't read, and the drivers are let go afterwards.
 */
class LocalImportTest {
    private val folder = Files.createTempDirectory("xbans-local-import")
    private val log = RecordingLog()
    private val steve = PlayerRef.parseOrNull("069a79f4-44e9-4726-a5be-fca90e38aaf5")!!
    private val alex = PlayerRef.parseOrNull("853c80ef-3c37-49fd-aa49-938b674adae6")!!
    private val now = System.currentTimeMillis()

    @AfterTest
    fun clean() {
        folder.toFile().deleteRecursively()
    }

    @Test
    fun liteBansH2InEachFileFormat() {
        for ((writer, reader) in listOf(FORMAT_1 to DriverLibrary.H2_FORMAT_1, FORMAT_2 to DriverLibrary.H2_FORMAT_2, FORMAT_3 to DriverLibrary.H2_FORMAT_3)) {
            val plugins = Files.createDirectories(folder.resolve(writer))
            val file = liteBans(plugins.resolve("LiteBans"), writer)
            assertEquals(reader, LocalFiles.h2For(file), "$writer's file")
            val before = Sha256.of(file)
            val reading = ArrayList<String>()
            val haul = FileImports.LiteBans(plugins.resolve("LiteBans"), Drivers(javaClass.classLoader, jars), log)
                .read(Punishment.GLOBAL, now, ImportContext(tell = { _, placeholders -> reading += placeholders.joinToString { "${it.first}=${it.second}" } }))
            assertNull(haul.failed, "$writer: ${haul.failed}")
            assertLiteBans(haul)
            assertEquals(listOf("file=plugins/LiteBans/litebans.mv.db, driver=${reader.coordinates}"), reading)
            assertEquals(before, Sha256.of(file), "LiteBans' own file isn't changed")
            assertEquals(setOf("config.yml", "litebans.mv.db"), names(plugins.resolve("LiteBans")), "nor anything added beside it")
        }
    }

    @Test
    fun liteBansOlderPageStoreFile() {
        val file = liteBans(folder.resolve("LiteBans"), FORMAT_1, pageStore = true)
        assertEquals("litebans.h2.db", file.fileName.toString())
        val haul = FileImports.LiteBans(folder.resolve("LiteBans"), Drivers(javaClass.classLoader, jars), log).read(Punishment.GLOBAL, now, ImportContext())
        assertNull(haul.failed, haul.failed)
        assertLiteBans(haul)
    }

    @Test
    fun throughTheCommandTwiceAddsNothingTheSecondTime() {
        liteBans(folder.resolve("LiteBans"), FORMAT_2)
        advancedBan(folder.resolve("AdvancedBan"))
        val h = Harness(imports = FileImports(folder, folder, Drivers(javaClass.classLoader, jars), log)).start()
        try {
            assertEquals(
                listOf(
                    "Reading plugins/LiteBans/litebans.mv.db with com.h2database:h2:2.1.214…",
                    "Found in LiteBans: 2 bans, 1 mutes, 1 warnings, 1 kicks and 2 players. Nothing was imported yet: run it again with confirm on the end.",
                    "1 wildcard IP bans (such as 192.168.1.*) aren't imported: xBans bans whole addresses only.",
                ),
                import(h, "xbans import litebans", ::wildcards),
            )
            assertEquals("Imported 5 punishments from LiteBans (0 already there).", import(h, "xbans import litebans confirm", ::wildcards).first { it.startsWith("Imported") })
            assertNotNull(h.plugin.banOnJoin(steve, null), "a ban in force is enforced")
            assertEquals("Imported 0 punishments from LiteBans (5 already there).", import(h, "xbans import litebans confirm", ::wildcards).first { it.startsWith("Imported") })

            assertEquals(
                listOf(
                    "Reading plugins/AdvancedBan/data/storage.script with org.hsqldb:hsqldb:2.5.2…",
                    "Found in AdvancedBan: 2 bans, 1 mutes, 0 warnings, 1 kicks and 1 players. Nothing was imported yet: run it again with confirm on the end.",
                ),
                import(h, "xbans import advancedban"),
            )
            assertEquals("Imported 4 punishments from AdvancedBan (0 already there).", import(h, "xbans import advancedban confirm").last())
            assertEquals("Imported 0 punishments from AdvancedBan (4 already there).", import(h, "xbans import advancedban confirm").last())
        } finally {
            h.stop()
            h.folder.toFile().deleteRecursively()
        }
    }

    @Test
    fun advancedBanHsqldbAsAdvancedBanLeavesIt() {
        val data = advancedBan(folder.resolve("AdvancedBan"))
        val before = names(data).associateWith { Sha256.of(data.resolve(it)) }
        // HSQLDB's INFO lines as it opens and closes the copy stay out of the server's console.
        val logged = java.util.Collections.synchronizedList(ArrayList<String>())
        val handler = object : java.util.logging.Handler() {
            override fun publish(record: java.util.logging.LogRecord) {
                if (record.loggerName.orEmpty().startsWith("hsqldb")) logged += record.message
            }
            override fun flush() = Unit
            override fun close() = Unit
        }
        val root = java.util.logging.Logger.getLogger("")
        root.addHandler(handler)
        val haul = try {
            FileImports.AdvancedBan(folder.resolve("AdvancedBan"), Drivers(javaClass.classLoader, jars), log).read(Punishment.GLOBAL, now, ImportContext())
        } finally {
            root.removeHandler(handler)
        }
        assertEquals(emptyList(), logged.toList())
        assertNull(haul.failed, haul.failed)
        assertEquals(2, haul.count(PunishmentType.BAN))
        assertEquals(1, haul.count(PunishmentType.MUTE))
        assertEquals(1, haul.count(PunishmentType.KICK))
        val ban = haul.rows.first { it.entry.type == PunishmentType.BAN && !it.entry.punishment.ipBased }.entry
        assertEquals(steve, ban.punishment.target)
        assertEquals("Mod", ban.punishment.staffName)
        assertNull(ban.punishment.removal, "still in Punishments, so in force")
        assertNotNull(haul.rows.first { it.entry.type == PunishmentType.MUTE }.entry.punishment.removal, "gone from Punishments before it ended: lifted")
        assertEquals("198.51.100.7", haul.rows.first { it.entry.punishment.ipBased }.entry.ip)
        assertEquals(before, names(data).associateWith { Sha256.of(data.resolve(it)) }, "AdvancedBan's files are as they were")
        assertEquals(null, System.getProperty("hsqldb.method_class_names"), "the JVM's HSQLDB setting is put back")
    }

    @Test
    fun aFileInUseIsntRead() {
        // H2 holds a lock on its file while it's open, here as LiteBans would on this server.
        val file = liteBans(folder.resolve("LiteBans"), FORMAT_1)
        val writer = writer(FORMAT_1, "org.h2.Driver")
        writer.connect("jdbc:h2:file:${file.parent.resolve("litebans").toAbsolutePath()}", login()).use {
            val haul = FileImports.LiteBans(folder.resolve("LiteBans"), Drivers(javaClass.classLoader, jars), log).read(Punishment.GLOBAL, now, ImportContext())
            assertEquals(XBansMessages.importInUse, haul.message)
            assertEquals(listOf("source" to "LiteBans", "file" to "plugins/LiteBans/litebans.mv.db"), haul.placeholders)
        }
        // A plugin running here is asked about first, without touching its files.
        advancedBan(folder.resolve("AdvancedBan"))
        val running = ImportContext(running = { it == "AdvancedBan" })
        assertEquals(XBansMessages.importInUse, FileImports.AdvancedBan(folder.resolve("AdvancedBan"), Drivers(javaClass.classLoader, jars), log).read("*", now, running).message)
        // HSQLDB's lock file, kept fresh while another program has it open.
        Files.write(folder.resolve("AdvancedBan/data/storage.lck"), byteArrayOf(1))
        assertEquals(XBansMessages.importInUse, FileImports.AdvancedBan(folder.resolve("AdvancedBan"), Drivers(javaClass.classLoader, jars), log).read("*", now, ImportContext()).message)

        // Through the command, from what the server says is running.
        val h = Harness(imports = FileImports(folder, folder, Drivers(javaClass.classLoader, jars), log), about = { mapOf("Plugins" to "LiteBans 2.17.4, Vault 1.7.3 (off)") }).start()
        try {
            assertEquals(
                listOf("LiteBans is still using plugins/LiteBans/litebans.mv.db. Stop it first: move its jar out of the plugins folder (keep its folder), restart, then import again."),
                import(h, "xbans import litebans"),
            )
        } finally {
            h.stop()
            h.folder.toFile().deleteRecursively()
        }
    }

    @Test
    fun offlineSaysWhichJarToPutWhereAndThenWorks() {
        liteBans(folder.resolve("LiteBans"), FORMAT_1)
        val jar = jar(DriverLibrary.H2_FORMAT_1)
        val pinned = Library("com.h2database", "h2", "1.4.200", Sha256.of(jar), "org.h2.Driver")
        val cache = LibraryCache(folder.resolve("Xena/libraries"), emptyList(), "Set a mirror in plugins/Xena/libraries.yml.")
        val offline = Drivers(javaClass.classLoader, OnDemandDriverFetcher(OnDemandLibraries(listOf(pinned), cache)))
        val haul = FileImports.LiteBans(folder.resolve("LiteBans"), offline, log).read(Punishment.GLOBAL, now, ImportContext())
        assertEquals(XBansMessages.importNoDriver, haul.message)
        val reason = haul.placeholders.toMap()["reason"].toString()
        val path = cache.location(pinned)
        assertTrue("Or put h2-1.4.200.jar from Maven Central at $path." in reason, reason)
        // Put there by hand, it's used without a download.
        Files.createDirectories(path.parent)
        Files.copy(jar, path)
        assertNull(FileImports.LiteBans(folder.resolve("LiteBans"), offline, log).read(Punishment.GLOBAL, now, ImportContext()).failed)
    }

    @Test
    fun eachDriverIsLetGoAfterwards() {
        val file = liteBans(folder.resolve("LiteBans"), FORMAT_2)
        val data = advancedBan(folder.resolve("AdvancedBan"))
        val copy = Files.createDirectories(folder.resolve("copy"))
        Files.copy(file, copy.resolve("litebans.mv.db"))
        for (name in names(data)) Files.copy(data.resolve(name), copy.resolve(name))
        for ((library, url, user) in listOf(
            Triple(DriverLibrary.H2_FORMAT_2, "jdbc:h2:file:${copy.resolve("litebans").toAbsolutePath()};IFEXISTS=TRUE;DB_CLOSE_ON_EXIT=FALSE", "sa"),
            Triple(DriverLibrary.HSQLDB, "jdbc:hsqldb:file:${copy.resolve("storage").toAbsolutePath()};ifexists=true;shutdown=true;hsqldb.lock_file=false", "SA"),
        )) {
            val loader = opened(library, url, user)
            repeat(50) {
                if (loader.get() == null) return@repeat
                System.gc()
                ByteArray(1 shl 20)
                Thread.sleep(20)
            }
            assertNull(loader.get(), "${library.coordinates}'s class loader is collected once closed")
        }
    }

    // Opens, reads and closes, handing back only a weak reference to the driver's class loader.
    private fun opened(library: DriverLibrary, url: String, user: String): WeakReference<ClassLoader> {
        val driver = (IsolatedDriver.open(jars, library) as Outcome.Ok).value
        val loader = driver.connect(url, Properties().apply { setProperty("user", user); setProperty("password", "") }).use { connection ->
            connection.createStatement().use { it.executeQuery("SELECT 1 FROM INFORMATION_SCHEMA.TABLES").use { result -> result.next() } }
            WeakReference(connection.javaClass.classLoader)
        }
        driver.close()
        return loader
    }

    private fun assertLiteBans(haul: Haul) {
        assertEquals(2, haul.count(PunishmentType.BAN))
        assertEquals(1, haul.wildcards)
        assertEquals(listOf(1L, 1L, 1L), listOf(haul.count(PunishmentType.MUTE), haul.count(PunishmentType.WARN), haul.count(PunishmentType.KICK)))
        assertEquals(2, haul.players())
        val lifted = haul.rows.first { it.key == "litebans:bans:2" }.entry
        assertEquals("Mod", lifted.punishment.removal!!.staffName)
        assertEquals("Appealed", lifted.punishment.removal!!.reason)
        assertEquals("survival", lifted.punishment.scope)
        assertTrue(lifted.punishment.ipBased)
        assertEquals("198.51.100.7", lifted.ip)
        val ban = haul.rows.first { it.key == "litebans:bans:1" }.entry
        assertNull(ban.punishment.expires)
        assertEquals("Steve", ban.punishment.targetName)
        assertEquals("Hacking", ban.punishment.reason)
        assertEquals(Punishment.GLOBAL, ban.punishment.scope)
        assertEquals(now - 1000, ban.punishment.created.toEpochMilliseconds())
        assertTrue(haul.rows.first { it.key == "litebans:bans:2" }.entry.punishment.silent)
        assertNull(haul.rows.first { it.key == "litebans:mutes:1" }.entry.punishment.removal, "an expired mute ended on its own")
        val seen = haul.seen.first { it.id == steve }
        assertEquals("203.0.113.9", seen.ip)
        assertTrue(kotlin.math.abs(seen.last - (now - 2000)) < 1000, "the login's time, from H2's TIMESTAMP")
    }

    private fun names(dir: Path): Set<String> = Files.newDirectoryStream(dir).use { all -> all.map { it.fileName.toString() }.toSet() }

    // Each import into a harness, waited on until it says how it went, or until its [last] line when
    // more follow that (LiteBans' wildcard bans are counted on the line after, from the same callback,
    // so a fixed wait could read between the two); what it said, without the prefix.
    private fun import(h: Harness, line: String, last: (String) -> Boolean = ::ended): List<String> {
        val before = h.replies(h.console).size
        h.run(line) { h.replies(h.console).drop(before).any(last) }
        h.platform.pump()
        return h.replies(h.console).drop(before).map { it.removePrefix("» ") }
    }

    private fun ended(reply: String): Boolean =
        reply.contains("Found in") || reply.contains("Couldn't") || reply.contains("Imported") || reply.contains("still using")

    private fun wildcards(reply: String): Boolean = reply.contains("wildcard IP bans")

    // ---- the fixtures, written as the plugins write them ----

    /**
     * LiteBans' tables in H2, as LiteBans makes them (its MySQL schema, in H2's types), with a
     * ban in force, an IP ban lifted early, a wildcard IP ban, a mute that ran out, a warning, a
     * kick and two logins; and LiteBans' config, on H2. Written by [version], closed as LiteBans
     * leaves it when it stops.
     */
    internal fun liteBans(dir: Path, version: String, pageStore: Boolean = false, now: Long = this.now): Path {
        Files.createDirectories(dir)
        Files.write(dir.resolve("config.yml"), "sql:\n  driver: H2\n  table_prefix: 'litebans_'\n".toByteArray(StandardCharsets.UTF_8))
        val url = "jdbc:h2:file:${dir.resolve("litebans").toAbsolutePath()}" + if (pageStore) ";MV_STORE=FALSE" else ""
        writer(version, "org.h2.Driver").connect(url, login()).use { c ->
            val columns = "(id BIGINT PRIMARY KEY, uuid VARCHAR(36), ip VARCHAR(45), reason VARCHAR(2048) NOT NULL, banned_by_uuid VARCHAR(36) NOT NULL," +
                " banned_by_name VARCHAR(128), removed_by_uuid VARCHAR(36), removed_by_name VARCHAR(128), removed_by_reason VARCHAR(2048)," +
                " removed_by_date TIMESTAMP, time BIGINT NOT NULL, until BIGINT NOT NULL, template TINYINT NOT NULL DEFAULT 127, server_scope VARCHAR(32)," +
                " server_origin VARCHAR(32), silent BOOLEAN NOT NULL, ipban BOOLEAN NOT NULL, ipban_wildcard BOOLEAN NOT NULL DEFAULT FALSE, active BOOLEAN NOT NULL"
            c.createStatement().use { s ->
                for (table in listOf("bans", "mutes", "kicks")) s.execute("CREATE TABLE litebans_$table $columns)")
                s.execute("CREATE TABLE litebans_warnings $columns, warned BOOLEAN NOT NULL DEFAULT FALSE)")
                s.execute("CREATE TABLE litebans_history (id BIGINT PRIMARY KEY, date TIMESTAMP, name VARCHAR(16), uuid VARCHAR(36), ip VARCHAR(45))")
            }
            val row = "(id, uuid, ip, reason, banned_by_uuid, banned_by_name, removed_by_uuid, removed_by_name, removed_by_reason, removed_by_date," +
                " time, until, template, server_scope, server_origin, silent, ipban, ipban_wildcard, active) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
            val mod = "61699b2e-d327-4a01-9f1e-0ea8c3f06bc6"
            c.insert("INSERT INTO litebans_bans $row", 1, steve.toString(), "203.0.113.9", "Hacking", mod, "Mod", null, null, null, null, now - 1000, -1, 0, "*", "lobby", false, false, false, true)
            c.insert("INSERT INTO litebans_bans $row", 2, alex.toString(), "198.51.100.7", "Alts", "CONSOLE", "Console", mod, "Mod", "Appealed", Timestamp(now - 4000), now - 5000, now + 86_400_000L, 0, "survival", "survival", true, true, false, false)
            c.insert("INSERT INTO litebans_bans $row", 3, null, "192.168.1.*", "Range", "CONSOLE", "Console", null, null, null, null, now - 5000, -1, 0, "*", "lobby", false, true, true, true)
            c.insert("INSERT INTO litebans_mutes $row", 1, steve.toString(), null, "Spam", "CONSOLE", "Console", null, "#expired", null, null, now - 100_000, now - 50_000, 0, "global", "lobby", false, false, false, false)
            c.insert("INSERT INTO litebans_warnings $row", 1, steve.toString(), null, "Language", "CONSOLE", "Console", null, null, null, null, now - 1000, 0, 0, "*", "lobby", false, false, false, true)
            c.insert("INSERT INTO litebans_kicks $row", 1, alex.toString(), null, "Calm down", "CONSOLE", "Console", null, null, null, null, now - 1000, 0, 0, "*", "lobby", false, false, false, false)
            c.insert("INSERT INTO litebans_history VALUES (?, ?, ?, ?, ?)", 1, Timestamp(now - 2000), "Steve", steve.toString(), "203.0.113.9")
            c.insert("INSERT INTO litebans_history VALUES (?, ?, ?, ?, ?)", 2, Timestamp(now - 3000), "Alex", alex.toString(), "203.0.113.9")
        }
        return dir.resolve(if (pageStore) "litebans.h2.db" else "litebans.mv.db")
    }

    /**
     * AdvancedBan's tables in HSQLDB 2.4.0, made with AdvancedBan's own statements
     * (`SQLQuery`'s HSQLDB ones) and URL: a ban in force, a mute lifted early, a note, an IP ban,
     * a kick; and its config, not on MySQL.
     */
    internal fun advancedBan(dir: Path, now: Long = this.now): Path {
        val data = Files.createDirectories(dir.resolve("data"))
        Files.write(dir.resolve("config.yml"), "UseMySQL: false\n".toByteArray(StandardCharsets.UTF_8))
        val url = "jdbc:hsqldb:file:${data.toAbsolutePath()}/storage;hsqldb.lock_file=false"
        writer(FIXTURE_HSQLDB, "org.hsqldb.jdbc.JDBCDriver").connect(url, login("SA")).use { c ->
            c.createStatement().use { s ->
                for (table in listOf("Punishments", "PunishmentHistory")) {
                    s.execute(
                        "CREATE TABLE IF NOT EXISTS $table (id INTEGER IDENTITY PRIMARY KEY, name VARCHAR(16), uuid VARCHAR(35), reason VARCHAR(255)," +
                            " operator VARCHAR(16), punishmentType VARCHAR(16), start BIGINT, end BIGINT, calculation VARCHAR(50))",
                    )
                }
            }
            val insert = "(name, uuid, reason, operator, punishmentType, start, end, calculation) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
            val dashless = steve.toString().replace("-", "")
            for (table in listOf("Punishments", "PunishmentHistory")) c.insert("INSERT INTO $table $insert", "Steve", dashless, "Xray", "Mod", "BAN", now - 1000, -1L, null)
            c.insert("INSERT INTO PunishmentHistory $insert", "Steve", dashless, "Caps", "CONSOLE", "TEMP_MUTE", now - 2000, now + 3_600_000, null)
            c.insert("INSERT INTO PunishmentHistory $insert", "Steve", dashless, "Watch him", "Mod", "NOTE", now - 3000, -1L, null)
            c.insert("INSERT INTO PunishmentHistory $insert", "198.51.100.7", "198.51.100.7", "Proxy", "CONSOLE", "IP_BAN", now - 4000, -1L, null)
            c.insert("INSERT INTO PunishmentHistory $insert", "Steve", dashless, "Calm down", "CONSOLE", "KICK", now - 5000, -1L, null)
            // AdvancedBan's pool closes without SHUTDOWN; a server stopping ends it with the JVM.
            c.createStatement().use { it.execute("SHUTDOWN") }
        }
        return data
    }

    private fun Connection.insert(sql: String, vararg values: Any?) {
        prepareStatement(sql).use { insert ->
            values.forEachIndexed { i, v -> insert.setObject(i + 1, v) }
            insert.executeUpdate()
        }
    }

    private fun login(user: String = "sa") = Properties().apply { setProperty("user", user); setProperty("password", "") }

    // A fixture's writer: that version's driver, in a class loader of its own.
    private fun writer(version: String, driverClass: String): Driver {
        val loader = URLClassLoader(arrayOf(jar(version).toUri().toURL()), ClassLoader.getSystemClassLoader().parent)
        return Class.forName(driverClass, true, loader).getDeclaredConstructor().newInstance() as Driver
    }

    @Test
    fun writesTheBootChecksSeed() {
        // ./gradlew :xbans:common:jvmTest -PupdateBootSeed: the files xbans/bukkit/import-checks.txt imports on a real server.
        val seed = System.getProperty("xbans.test.bootSeed") ?: return
        val plugins = Paths.get(seed).resolve("plugins")
        plugins.toFile().deleteRecursively()
        // Fixed times, a year before the boot test's: the bans are permanent, so in force whenever it runs.
        val then = 1_764_547_200_000L
        liteBans(plugins.resolve("LiteBans"), FORMAT_1, now = then)
        advancedBan(plugins.resolve("AdvancedBan"), now = then)
        Files.deleteIfExists(plugins.resolve("AdvancedBan/data/storage.tmp"))
        plugins.resolve("AdvancedBan/data/storage.tmp").toFile().deleteRecursively()
    }

    companion object {
        const val FORMAT_1 = "h2-1.4.199"
        const val FORMAT_2 = "h2-2.0.206"
        const val FORMAT_3 = "h2-2.2.224"
        const val FIXTURE_HSQLDB = "hsqldb-2.4.0"

        private val jarFiles: List<Path> = System.getProperty("xbans.test.drivers").orEmpty().split(File.pathSeparator).filter { it.isNotEmpty() }.map { Paths.get(it) }

        /** A driver's jar from the build, by `artifact-version`. */
        fun jar(name: String): Path = jarFiles.firstOrNull { it.fileName.toString() == "$name.jar" }
            ?: error("the build hands the tests no $name.jar (xbans.test.drivers: $jarFiles)")

        /** The jar the build resolved for [library]: its version is the one pinned for servers. */
        fun jar(library: DriverLibrary): Path = library.coordinates.split(':').let { (_, artifact, version) -> jar("$artifact-$version") }

        /** The build's jars, as a server's fetcher hands over the pinned ones. */
        val jars: DriverFetcher = DriverFetcher { library ->
            try {
                Outcome.Ok(jar(library))
            } catch (e: IllegalStateException) {
                Outcome.Err(DbError(DbError.Kind.NO_DRIVER, e.message ?: "no jar"))
            }
        }
    }
}
