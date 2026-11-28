package co.xenastudios.xrewind

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.config.YamlNode
import co.xenastudios.core.common.platform.Log
import co.xenastudios.core.jvm.config.JvmYaml
import co.xenastudios.core.jvm.db.DriverLibrary
import co.xenastudios.core.jvm.db.Drivers
import co.xenastudios.core.jvm.db.IsolatedDriver
import co.xenastudios.xrewind.api.Actor
import co.xenastudios.xrewind.log.Actions
import co.xenastudios.xrewind.log.Record
import co.xenastudios.xrewind.log.StoredTime
import co.xenastudios.xrewind.log.TextState
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.Driver
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.util.Properties

/**
 * CoreProtect's history (SQLite or MySQL, wherever its config in `plugins/CoreProtect/` points),
 * read and handed to xRewind's writer, so the writer stays the only thing that writes (1.x's
 * `CoreProtectImport`). It waits whenever the queue is long, so logging carries on alongside.
 *
 * What comes across: block changes (with their rolled-back flags), kills, container changes,
 * joins and quits, sign text, chat and commands. Container items come across as their type only.
 * The database is read with the server's driver, or one fetched for the read if it has none
 * (xBans' on-demand drivers): a Spigot 1.13 server's SQLite and MySQL drivers read either.
 * CoreProtect's file is opened read-only, never changed.
 */
public class CoreProtectImport(
    private val plugins: Path,
    private val drivers: Drivers,
    private val loader: ClassLoader,
    private val log: Log,
    /** A plain stack of one of the item as this server's bytes, or `null` if it has no such item. */
    private val itemBytes: (String) -> ByteArray?,
) : HistoryImport {
    /** Where CoreProtect keeps its history. */
    internal class Source(val description: String, val url: String, val properties: Properties, val prefix: String, val mysql: Boolean)

    internal fun source(): Outcome<Source, String> {
        val folder = plugins.resolve("CoreProtect")
        val config = folder.resolve("config.yml")
        if (!Files.isRegularFile(config)) return Outcome.Err("there's no plugins/CoreProtect/config.yml.")
        val root = (JvmYaml.read(String(Files.readAllBytes(config), StandardCharsets.UTF_8)) as? Outcome.Ok)?.value as? YamlNode.Mapping
            ?: return Outcome.Err("plugins/CoreProtect/config.yml can't be read.")
        fun text(key: String, default: String): String = ((root[key] as? YamlNode.Scalar)?.content)?.takeIf { it.isNotEmpty() } ?: default
        val prefix = text("table-prefix", "co_")
        if (!PREFIX.matches(prefix)) return Outcome.Err("CoreProtect's table-prefix isn't a plain name.")
        val properties = Properties()
        if (text("use-mysql", "false").equals("true", ignoreCase = true)) {
            val host = text("mysql-host", "127.0.0.1")
            val port = text("mysql-port", "3306").toIntOrNull() ?: 3306
            val database = text("mysql-database", "database")
            properties.setProperty("user", text("mysql-username", "root"))
            properties.setProperty("password", (root["mysql-password"] as? YamlNode.Scalar)?.content ?: "")
            return Outcome.Ok(Source("MySQL at $host:$port/$database", "jdbc:mysql://$host:$port/$database?characterEncoding=utf8", properties, prefix, true))
        }
        val file = folder.resolve("database.db")
        if (!Files.isRegularFile(file)) return Outcome.Err("there's no plugins/CoreProtect/database.db.")
        properties.setProperty("open_mode", "1") // SQLITE_OPEN_READONLY
        return Outcome.Ok(Source("plugins/CoreProtect/database.db", "jdbc:sqlite:" + file.toAbsolutePath(), properties, prefix, false))
    }

    override fun find(): Outcome<String, String> = when (val found = source()) {
        is Outcome.Ok -> Outcome.Ok(found.value.description)
        is Outcome.Err -> found
    }

    override fun run(add: (Record) -> Unit, waiting: () -> Int, running: () -> Boolean): Outcome<Long, String> {
        val source = when (val found = source()) {
            is Outcome.Ok -> found.value
            is Outcome.Err -> return found
        }
        val library = if (source.mysql) DriverLibrary.MYSQL else DriverLibrary.SQLITE
        val serverDriver = serverDriver(if (source.mysql) listOf(library.driverClass, "com.mysql.jdbc.Driver") else listOf(library.driverClass))
        var isolated: IsolatedDriver? = null
        val driver: (String, Properties) -> Connection = if (serverDriver != null) {
            { url, p -> serverDriver.connect(url, p) ?: throw SQLException("the driver doesn't take $url") }
        } else {
            isolated = when (val opened = IsolatedDriver.open(drivers.fetcher, library)) {
                is Outcome.Ok -> opened.value
                is Outcome.Err -> return Outcome.Err("there's no driver to read it with: ${opened.error.detail}")
            }
            isolated::connect
        }
        return try {
            driver(source.url, source.properties).use { Reading(source, it, add, waiting, running).all() }
        } catch (e: SQLException) {
            Outcome.Err(e.message ?: e.toString())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Outcome.Err("the server stopped it")
        } finally {
            isolated?.close()
        }
    }

    private fun serverDriver(names: List<String>): Driver? {
        for (name in names) {
            try {
                return Class.forName(name, true, loader).getDeclaredConstructor().newInstance() as Driver
            } catch (e: ReflectiveOperationException) {
            } catch (e: LinkageError) {
            } catch (e: ClassCastException) {
            }
        }
        return null
    }

    private inner class Reading(
        private val source: Source,
        private val connection: Connection,
        private val add: (Record) -> Unit,
        private val waiting: () -> Int,
        private val running: () -> Boolean,
    ) {
        private val users = HashMap<Int, Actor>()
        private val worlds = HashMap<Int, String>()
        private val materials = HashMap<Int, String>()
        private val blockData = HashMap<Int, String>()
        private val entities = HashMap<Int, String>()
        private val items = HashMap<String, ByteArray?>()
        private var imported = 0L
        private var total = 0L
        private var lastReport = System.nanoTime()

        private fun table(name: String) = source.prefix + name

        fun all(): Outcome<Long, String> {
            loadNames()
            for (name in listOf("block", "container", "session", "sign", "chat", "command")) total += count(name)
            log.info("Importing $total changes from CoreProtect (${source.description}).")
            blocks()
            containers()
            sessions()
            signs()
            texts("chat", Actions.CHAT)
            texts("command", Actions.COMMAND)
            if (!running()) return Outcome.Err("the server stopped it")
            return Outcome.Ok(imported)
        }

        private fun loadNames() {
            connection.createStatement().use { statement ->
                // MySQL's tables number rows in rowid; SQLite's user table in id.
                statement.executeQuery("SELECT ${if (source.mysql) "rowid" else "id"}, user, uuid FROM ${table("user")}").use { rows ->
                    while (rows.next()) {
                        val name = rows.getString(2) ?: continue
                        val uuid = rows.getString(3)?.trim()?.let(PlayerRef::parseOrNull)
                        users[rows.getInt(1)] = try {
                            if (uuid != null && !name.startsWith("#")) Actor.player(uuid, name) else Actor.source(name)
                        } catch (e: IllegalArgumentException) {
                            continue
                        }
                    }
                }
                map(statement, "SELECT id, world FROM ${table("world")}", worlds)
                map(statement, "SELECT id, material FROM ${table("material_map")}", materials)
                map(statement, "SELECT id, data FROM ${table("blockdata_map")}", blockData)
                map(statement, "SELECT id, entity FROM ${table("entity_map")}", entities)
            }
        }

        private fun map(statement: Statement, sql: String, into: MutableMap<Int, String>) {
            try {
                statement.executeQuery(sql).use { rows -> while (rows.next()) into[rows.getInt(1)] = rows.getString(2) ?: continue }
            } catch (e: SQLException) {
                // An old CoreProtect without this table.
            }
        }

        private fun blocks() = stream("SELECT time, user, wid, x, y, z, type, blockdata, action, rolled_back FROM ${table("block")}") { rows ->
            val actor = users[rows.getInt(2)] ?: return@stream
            val world = worlds[rows.getInt(3)] ?: return@stream
            val action = rows.getInt(9)
            val time = StoredTime.fromUnixSeconds(rows.getLong(1))
            val x = rows.getInt(4)
            val y = rows.getInt(5)
            val z = rows.getInt(6)
            if (action == 3) {
                // A kill: the type is an entity.
                entities[rows.getInt(7)]?.let { add(Record.Kill(time, actor, world, x, y, z, key(it))) }
                return@stream
            }
            val state = state(rows.getInt(7), rows.getBytes(8)) ?: return@stream
            if (action !in 0..2) return@stream
            val ours = when (action) {
                0 -> Actions.BREAK
                1 -> Actions.PLACE
                else -> Actions.CLICK
            }
            val before = if (action == 1) AIR else state
            val after = if (action == 0) AIR else state
            add(Record.Block(time, actor, world, x, y, z, ours, TextState(before), TextState(after), null, rows.getInt(10) != 0))
        }

        private fun containers() = stream("SELECT time, user, wid, x, y, z, type, amount, action, rolled_back FROM ${table("container")}") { rows ->
            val actor = users[rows.getInt(2)] ?: return@stream
            val world = worlds[rows.getInt(3)] ?: return@stream
            val material = materials[rows.getInt(7)]?.let(::key) ?: return@stream
            val item = items.getOrPut(material) { itemBytes(material) } ?: return@stream
            add(Record.Container(StoredTime.fromUnixSeconds(rows.getLong(1)), actor, world, rows.getInt(4), rows.getInt(5), rows.getInt(6),
                if (rows.getInt(9) == 1) Actions.ADD else Actions.REMOVE, material, null, item, rows.getInt(8), rows.getInt(10) != 0))
        }

        private fun sessions() = stream("SELECT time, user, wid, x, y, z, action FROM ${table("session")}") { rows ->
            val actor = users[rows.getInt(2)] ?: return@stream
            val world = worlds[rows.getInt(3)] ?: return@stream
            add(Record.Session(StoredTime.fromUnixSeconds(rows.getLong(1)), actor, world, rows.getInt(4), rows.getInt(5), rows.getInt(6),
                if (rows.getInt(7) == 1) Actions.JOIN else Actions.QUIT))
        }

        private fun signs() = stream("SELECT time, user, wid, x, y, z, line_1, line_2, line_3, line_4, line_5, line_6, line_7, line_8 FROM ${table("sign")}") { rows ->
            val actor = users[rows.getInt(2)] ?: return@stream
            val world = worlds[rows.getInt(3)] ?: return@stream
            val lines = (7..14).mapNotNull { rows.getString(it)?.trim()?.takeIf(String::isNotEmpty) }
            if (lines.isEmpty()) return@stream
            add(Record.Text(StoredTime.fromUnixSeconds(rows.getLong(1)), actor, world, rows.getInt(4), rows.getInt(5), rows.getInt(6), Actions.SIGN, lines.joinToString(" | ")))
        }

        private fun texts(name: String, kind: Int) = stream("SELECT time, user, wid, x, y, z, message FROM ${table(name)}") { rows ->
            val actor = users[rows.getInt(2)] ?: return@stream
            val world = worlds[rows.getInt(3)] ?: return@stream
            val message = rows.getString(7) ?: return@stream
            add(Record.Text(StoredTime.fromUnixSeconds(rows.getLong(1)), actor, world, rows.getInt(4), rows.getInt(5), rows.getInt(6), kind, message))
        }

        // A block state as Minecraft writes it, from CoreProtect's material and property ids.
        private fun state(type: Int, data: ByteArray?): String? {
            val material = materials[type]?.let(::key) ?: return null
            if (data == null || data.isEmpty()) return material
            val properties = String(data, StandardCharsets.UTF_8).split(',').mapNotNull { id -> id.trim().toIntOrNull()?.let(blockData::get) }
            return if (properties.isEmpty()) material else material + "[" + properties.joinToString(",") + "]"
        }

        // Results read as they come, not all at once: MySQL needs asking for that. A table an old
        // CoreProtect doesn't have is skipped.
        private fun stream(sql: String, each: (ResultSet) -> Unit) {
            if (!running()) return
            val statement = connection.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)
            statement.use {
                it.fetchSize = if (source.mysql) Int.MIN_VALUE else 1_000
                val rows = try {
                    it.executeQuery(sql)
                } catch (e: SQLException) {
                    return
                }
                rows.use { r ->
                    while (r.next()) {
                        each(r)
                        if (!running()) return
                    }
                }
            }
        }

        private fun add(record: Record) {
            while (waiting() > QUEUE_ROOM && running()) Thread.sleep(20)
            addRecord(record)
        }

        private fun addRecord(record: Record) {
            this.add.invoke(record)
            imported++
            if (System.nanoTime() - lastReport > 10_000_000_000L) {
                lastReport = System.nanoTime()
                log.info("Imported $imported of $total changes from CoreProtect...")
            }
        }

        private fun count(name: String): Long = try {
            connection.createStatement().use { s -> s.executeQuery("SELECT COUNT(*) FROM ${table(name)}").use { if (it.next()) it.getLong(1) else 0 } }
        } catch (e: SQLException) {
            0 // An old CoreProtect without this table.
        }
    }

    private companion object {
        /** The queue is kept below this while importing, so live changes never wait behind it. */
        const val QUEUE_ROOM = 50_000
        const val AIR = "minecraft:air"
        val PREFIX = Regex("[A-Za-z0-9_]*")

        fun key(name: String): String = if (':' in name) name else "minecraft:$name"
    }
}
