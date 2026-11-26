package co.xenastudios.xmoney

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.newPending
import co.xenastudios.core.api.resolve
import co.xenastudios.core.common.platform.Platform
import co.xenastudios.core.common.scheduler.async
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Driver
import java.util.Properties

/**
 * EssentialsX's balances: `plugins/Essentials/userdata/<uuid>.yml`, read by a line scanner for
 * three top-level keys (`money`, `last-account-name`, `npc`), off the server's threads.
 */
public class EssentialsImport(plugins: Path, private val platform: Platform) : BalanceSource {
    private val userdata = plugins.resolve("Essentials").resolve("userdata")

    override val name: String get() = "EssentialsX"

    override fun read(): Pending<BalanceSource.Read> {
        val done = newPending<BalanceSource.Read>()
        platform.scheduler.async { done.resolve(readNow()) }
        return done
    }

    /** What [read] reads, here and now. */
    public fun readNow(): BalanceSource.Read {
        if (!Files.isDirectory(userdata)) return BalanceSource.Read(emptyList(), missing = "there's no plugins/Essentials/userdata folder.")
        val found = ArrayList<BalanceSource.Found>()
        try {
            Files.list(userdata).use { files -> for (file in files) file(file)?.let(found::add) }
        } catch (e: java.io.IOException) {
            return BalanceSource.Read(emptyList(), failed = "couldn't list plugins/Essentials/userdata: ${e.message}")
        }
        return BalanceSource.Read(found)
    }

    private fun file(file: Path): BalanceSource.Found? {
        val fileName = file.fileName.toString()
        if (!fileName.endsWith(".yml")) return null
        val id = PlayerRef.parseOrNull(fileName.removeSuffix(".yml")) ?: return null
        var money: String? = null
        var name: String? = null
        var npc = false
        try {
            Files.newBufferedReader(file, StandardCharsets.UTF_8).use { reader ->
                for (line in reader.lineSequence()) {
                    // Only top-level keys: homes and the rest are indented.
                    if (line.isEmpty() || line[0].isWhitespace()) continue
                    val colon = line.indexOf(':')
                    if (colon < 0) continue
                    val value = unquote(line.substring(colon + 1).trim())
                    when (line.substring(0, colon)) {
                        "money" -> money = value
                        "last-account-name", "lastAccountName" -> name = value
                        "npc" -> npc = value.equals("true", ignoreCase = true)
                    }
                }
            }
        } catch (e: java.io.IOException) {
            return BalanceSource.Found(id, "", true, "unreadable")
        }
        return money?.let { BalanceSource.Found(id, name.orEmpty(), !npc, it) }
    }

    private fun unquote(value: String): String =
        if (value.length >= 2 && (value[0] == '\'' || value[0] == '"') && value.last() == value[0]) value.substring(1, value.length - 1) else value
}

/**
 * CMI's balances, from its SQLite file (`plugins/CMI/cmi.sqlite.db`), read-only, by column names,
 * since its layout couldn't be checked against a real install. The server's SQLite driver,
 * through [loader].
 */
public class CmiImport(plugins: Path, private val platform: Platform, private val loader: ClassLoader) : BalanceSource {
    private val file = plugins.resolve("CMI").resolve("cmi.sqlite.db")

    override val name: String get() = "CMI"

    override fun read(): Pending<BalanceSource.Read> {
        val done = newPending<BalanceSource.Read>()
        platform.scheduler.async { done.resolve(readNow()) }
        return done
    }

    /** What [read] reads, here and now. */
    public fun readNow(): BalanceSource.Read {
        if (!Files.isRegularFile(file)) {
            return BalanceSource.Read(emptyList(), missing = "there's no plugins/CMI/cmi.sqlite.db. If CMI keeps its data in MySQL, import with /xmoney import vault while CMI is still installed.")
        }
        val driver = try {
            Class.forName("org.sqlite.JDBC", true, loader).getDeclaredConstructor().newInstance() as Driver
        } catch (e: Exception) {
            return BalanceSource.Read(emptyList(), failed = "this server has no SQLite driver to read it with.")
        }
        val properties = Properties()
        properties.setProperty("open_mode", "1") // Read-only: CMI's file is never changed.
        // CMI may be writing to it right now: wait for its lock a while rather than fail at once.
        properties.setProperty("busy_timeout", "5000")
        return try {
            driver.connect("jdbc:sqlite:" + file.toAbsolutePath(), properties).use { connection ->
                connection.createStatement().use { statement ->
                    val columns = LinkedHashMap<String, String>()
                    statement.executeQuery("PRAGMA table_info(users)").use { while (it.next()) it.getString("name").let { c -> columns[c.lowercase()] = c } }
                    if (columns.isEmpty()) return BalanceSource.Read(emptyList(), failed = "cmi.sqlite.db has no users table.")
                    val uuid = pick(columns, "player_uuid", "uuid")
                    val name = pick(columns, "username", "name", "player_name")
                    val balance = pick(columns, "balance", "money")
                    if (uuid == null || balance == null) {
                        return BalanceSource.Read(emptyList(), failed = "CMI's users table doesn't have the columns expected (a uuid and a balance). It has: ${columns.values.joinToString(", ")}")
                    }
                    val found = ArrayList<BalanceSource.Found>()
                    val query = "SELECT \"$uuid\", ${if (name == null) "NULL" else "\"$name\""}, CAST(\"$balance\" AS TEXT) FROM users"
                    statement.executeQuery(query).use { rows ->
                        while (rows.next()) {
                            val id = rows.getString(1)?.let(PlayerRef::parseOrNull) ?: continue
                            found += BalanceSource.Found(id, rows.getString(2).orEmpty(), true, rows.getString(3) ?: "0")
                        }
                    }
                    BalanceSource.Read(found)
                }
            }
        } catch (e: java.sql.SQLException) {
            BalanceSource.Read(emptyList(), failed = "couldn't read cmi.sqlite.db: ${e.message}")
        }
    }

    private fun pick(columns: Map<String, String>, vararg wanted: String): String? = wanted.firstNotNullOfOrNull { columns[it] }
}
