package co.xenastudios.xbans

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.jvm.db.DriverLibrary
import co.xenastudios.core.jvm.db.Drivers
import co.xenastudios.core.jvm.db.IsolatedDriver
import co.xenastudios.core.jvm.db.rows
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.sql.Connection
import java.sql.SQLException
import java.util.Properties
import java.util.logging.Level
import java.util.logging.Logger

/**
 * LiteBans' and AdvancedBan's own local databases, for `/xbans import`: LiteBans' H2 file
 * (`plugins/LiteBans/litebans.mv.db`) and AdvancedBan's HSQLDB files
 * (`plugins/AdvancedBan/data/storage.*`). Servers have no driver for either, so the one a file
 * needs is fetched for the import, through the core's pinned on-demand libraries
 * ([IsolatedDriver]: the family's cache, checked against its pin), loaded in a class loader of
 * its own and let go once it's read. Nothing of this loads before an import of such a file.
 *
 * The source plugin's files are never opened by a driver: each is copied while a shared lock on
 * it is held (so a running H2 holding its own lock is noticed, and none starts mid-copy), and the
 * copy is read, then deleted. What's read goes through the same [FileImports.SqlSource.readFrom]
 * as MySQL's, so both paths map everything the same way.
 */
internal object LocalFiles {
    /** HSQLDB writes its `.lck` file every 10 seconds while a database is open. */
    private const val HEARTBEAT_MILLIS = 20_000L

    /** LiteBans' H2 file, read with the H2 its format needs. */
    fun liteBans(folder: Path, config: Path, drivers: Drivers, context: ImportContext, read: (FileImports.Tables) -> Haul): Haul {
        val modern = folder.resolve("litebans.mv.db")
        val file = if (Files.exists(modern)) modern else folder.resolve("litebans.h2.db").takeIf { Files.exists(it) }
            ?: return Haul.failed("LiteBans is on H2, and there's no plugins/LiteBans/litebans.mv.db to read.")
        val shown = "plugins/LiteBans/${file.fileName}"
        if (context.running("LiteBans")) return inUse("LiteBans", shown)
        val library = h2For(file) ?: return Haul.failed("$shown isn't an H2 database xBans can read: its header names no H2 file format it knows.")
        if (library == DriverLibrary.H2_FORMAT_3 && javaVersion() < 11) {
            return Haul.failed("$shown was written by H2 2.2 or later, whose driver needs Java 11 or newer, and this server runs Java ${System.getProperty("java.version")}. Import on a server with Java 11 or newer.")
        }
        // LiteBans' own login for the file, if it set one, then H2's defaults.
        val logins = listOf(
            FileImports.SqlSource.setting(config, "sql.username").orEmpty() to FileImports.SqlSource.setting(config, "sql.password").orEmpty(),
            "sa" to "", "" to "",
        ).distinct()
        val mvStore = file == modern
        return copied("LiteBans", shown, listOf(file)) { copy ->
            val base = copy.resolve(file.fileName.toString().removeSuffix(if (mvStore) ".mv.db" else ".h2.db")).toAbsolutePath()
            val url = "jdbc:h2:file:$base;IFEXISTS=TRUE;DB_CLOSE_ON_EXIT=FALSE" + if (mvStore) "" else ";MV_STORE=FALSE"
            withDriver(drivers, library, shown, context) { driver -> withConnection(driver, url, logins, shown, read) }
        }
    }

    /** AdvancedBan's HSQLDB files, `data/storage.*`, as AdvancedBan opens them (user `SA`, no password). */
    fun advancedBan(folder: Path, drivers: Drivers, context: ImportContext, read: (FileImports.Tables) -> Haul): Haul {
        val data = folder.resolve("data")
        val shown = "plugins/AdvancedBan/data/storage.script"
        if (!Files.exists(data.resolve("storage.script")) && !Files.exists(data.resolve("storage.properties"))) {
            return Haul.failed("AdvancedBan isn't on MySQL (UseMySQL: false), and there's no $shown to read.")
        }
        if (context.running("AdvancedBan")) return inUse("AdvancedBan", shown)
        // AdvancedBan turns HSQLDB's lock file off; another program with the files open may not have.
        val lock = data.resolve("storage.lck")
        if (Files.exists(lock) && System.currentTimeMillis() - Files.getLastModifiedTime(lock).toMillis() < HEARTBEAT_MILLIS) return inUse("AdvancedBan", shown)
        val files = Files.newDirectoryStream(data).use { all ->
            all.filter { Files.isRegularFile(it) && it.fileName.toString().let { name -> name.startsWith("storage.") && name != "storage.lck" } }
        }
        return copied("AdvancedBan", shown, files) { copy ->
            val url = "jdbc:hsqldb:file:${copy.resolve("storage").toAbsolutePath()};ifexists=true;shutdown=true;hsqldb.lock_file=false"
            hsqldb { withDriver(drivers, DriverLibrary.HSQLDB, shown, context) { driver -> withConnection(driver, url, listOf("SA" to ""), shown, read) } }
        }
    }

    /** The H2 driver for [file], from the format its header names (`format:1`, 2 or 3); `null` if it names none. */
    fun h2For(file: Path): DriverLibrary? {
        if (file.fileName.toString().endsWith(".h2.db")) return DriverLibrary.H2_FORMAT_1
        val header = ByteBuffer.allocate(4096)
        FileChannel.open(file, StandardOpenOption.READ).use {
            while (header.hasRemaining()) if (it.read(header) <= 0) break
        }
        val text = String(header.array(), 0, header.position(), StandardCharsets.ISO_8859_1)
        if (!text.startsWith("H:2,")) return null
        return when (text.substringAfter(",format:", "").substringBefore(',')) {
            "1" -> DriverLibrary.H2_FORMAT_1
            "2" -> DriverLibrary.H2_FORMAT_2
            "3" -> DriverLibrary.H2_FORMAT_3
            else -> null
        }
    }

    private fun inUse(source: String, shown: String): Haul =
        Haul.failed("$source is still using $shown.", XBansMessages.importInUse, "source" to source, "file" to shown)

    private fun javaVersion(): Int = System.getProperty("java.specification.version").removePrefix("1.").substringBefore('.').toIntOrNull() ?: 8

    // Copies [files] into a folder of its own, each under a shared lock held while it's copied,
    // runs [read] on the copy and deletes it. A file another program holds a lock on (H2 while it's
    // open, here or elsewhere) is in use: nothing is copied or read.
    private fun copied(source: String, shown: String, files: List<Path>, read: (Path) -> Haul): Haul {
        val copy = Files.createTempDirectory("xbans-import-")
        try {
            for (file in files) {
                FileChannel.open(file, StandardOpenOption.READ).use { channel ->
                    val lock: FileLock? = try {
                        channel.tryLock(0, Long.MAX_VALUE, true) ?: return inUse(source, shown)
                    } catch (e: OverlappingFileLockException) {
                        return inUse(source, shown)
                    } catch (e: IOException) {
                        null // A file system without locks: there's nothing to ask.
                    }
                    try {
                        FileChannel.open(copy.resolve(file.fileName.toString()), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { out ->
                            val size = channel.size()
                            var at = 0L
                            while (at < size) at += channel.transferTo(at, size - at, out)
                        }
                    } finally {
                        lock?.release()
                    }
                }
            }
            return read(copy)
        } finally {
            copy.toFile().deleteRecursively()
        }
    }

    private fun withDriver(drivers: Drivers, library: DriverLibrary, shown: String, context: ImportContext, read: (IsolatedDriver) -> Haul): Haul {
        context.tell(XBansMessages.importReading, "file" to shown, "driver" to library.coordinates)
        val driver = when (val opened = IsolatedDriver.open(drivers.fetcher, library)) {
            is Outcome.Ok -> opened.value
            is Outcome.Err -> return Haul.failed(
                "couldn't get ${library.coordinates} to read $shown: ${opened.error.detail}", XBansMessages.importNoDriver,
                "driver" to library.coordinates, "file" to shown, "reason" to opened.error.detail,
            )
        }
        return driver.use(read)
    }

    // Tries each login in turn: H2 and HSQLDB say which failures are a wrong one (SQLSTATE 28...).
    private fun withConnection(driver: IsolatedDriver, url: String, logins: List<Pair<String, String>>, shown: String, read: (FileImports.Tables) -> Haul): Haul {
        var refused: SQLException? = null
        for ((user, password) in logins) {
            val connection = try {
                driver.connect(url, Properties().apply { setProperty("user", user); setProperty("password", password) })
            } catch (e: SQLException) {
                if (e.sqlState?.startsWith("28") == true) {
                    refused = e
                    continue
                }
                return Haul.failed("couldn't open the copy of $shown: ${e.message?.lineSequence()?.first()}")
            }
            return connection.use { read(tables(it)) }
        }
        return Haul.failed("$shown wants a user name and password xBans doesn't know (${refused?.message?.lineSequence()?.first()}). For LiteBans, set them as sql.username and sql.password in its config.yml.")
    }

    // A connection's tables in its PUBLIC schema, found by name whatever its case (H2 and HSQLDB
    // keep unquoted names upper-case, quoted ones as written), each read whole.
    private fun tables(connection: Connection): FileImports.Tables {
        val names = HashMap<String, String>()
        connection.metaData.getTables(null, null, "%", null).use {
            while (it.next()) {
                val name = it.getString("TABLE_NAME")
                if (it.getString("TABLE_SCHEM").equals("public", true)) names.putIfAbsent(name.lowercase(), name)
            }
        }
        return FileImports.Tables { table, byId ->
            val name = names[table.lowercase()] ?: return@Tables null
            try {
                // Both plugins' tables start with their id.
                val sql = "SELECT * FROM \"PUBLIC\".\"${name.replace("\"", "\"\"")}\"" + if (byId) " ORDER BY 1" else ""
                Outcome.Ok(connection.createStatement().use { it.executeQuery(sql).use { result -> result.rows() } })
            } catch (e: SQLException) {
                Outcome.Err("couldn't read $table: ${e.message?.lineSequence()?.first()}")
            }
        }
    }

    // HSQLDB's settings that are the JVM's: it would otherwise reset the server's logging
    // (reconfig_logging) and let a database's SQL call any static Java method (method_class_names,
    // as HSQLDB 2.7.1 and later default to). Its INFO lines as it opens and closes stay out of the console.
    private inline fun hsqldb(read: () -> Haul): Haul {
        if (System.getProperty("hsqldb.reconfig_logging") == null) System.setProperty("hsqldb.reconfig_logging", "false")
        val methods = System.getProperty(METHODS)
        if (methods == null) System.setProperty(METHODS, "java.lang.Math")
        val logger = Logger.getLogger("hsqldb") // hsqldb.db.<database>.ENGINE
        val level = logger.level
        logger.level = Level.WARNING
        try {
            return read()
        } finally {
            logger.level = level
            if (methods == null) System.clearProperty(METHODS)
        }
    }

    private const val METHODS = "hsqldb.method_class_names"
}
