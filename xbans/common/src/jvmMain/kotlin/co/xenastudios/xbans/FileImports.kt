package co.xenastudios.xbans

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.onSettled
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.config.YamlNode
import co.xenastudios.core.common.db.Database
import co.xenastudios.core.common.db.DatabaseSettings
import co.xenastudios.core.common.db.Row
import co.xenastudios.core.common.db.SqlValue
import co.xenastudios.core.common.platform.Log
import co.xenastudios.core.jvm.config.JvmYaml
import co.xenastudios.core.jvm.db.Drivers
import co.xenastudios.core.jvm.db.JdbcDatabase
import co.xenastudios.xbans.api.Punishment
import co.xenastudios.xbans.api.PunishmentType
import co.xenastudios.xbans.api.Removal
import co.xenastudios.xbans.ip.IpKeys
import co.xenastudios.xbans.model.Entry
import co.xenastudios.xbans.store.Backend
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.time.Instant
import java.nio.file.Files as NioFiles

/**
 * `/xbans import`'s sources on the JVM: the server's own ban lists (`vanilla`), EssentialsX's
 * mutes and players (`essentials`), LiteBans and AdvancedBan from their MySQL or MariaDB
 * databases or their own local files (LiteBans' H2, AdvancedBan's HSQLDB: [LocalFiles]). [server]
 * is the server's folder (where `banned-players.json` is), [plugins] the plugins folder; an
 * adapter offers those its platform has ([ids]).
 */
public class FileImports(
    private val server: Path,
    private val plugins: Path,
    private val drivers: Drivers,
    private val log: Log,
    override val ids: List<String> = listOf("vanilla", "essentials", "litebans", "advancedban"),
) : Imports {
    override fun make(id: String): Importer? = when (id.lowercase()) {
        "vanilla" -> Vanilla(server)
        "essentials" -> Essentials(plugins.resolve("Essentials"))
        "litebans" -> LiteBans(folder("LiteBans", "litebans"), drivers, log)
        "advancedban" -> AdvancedBan(folder("AdvancedBan", "advancedban"), drivers, log)
        else -> null
    }?.takeIf { id.lowercase() in ids }

    // Velocity's plugins use lower-case folders, Bukkit's and BungeeCord's their names.
    private fun folder(vararg names: String): Path = names.map(plugins::resolve).firstOrNull { NioFiles.isDirectory(it) } ?: plugins.resolve(names[0])

    /**
     * The server's own lists, `banned-players.json` and `banned-ips.json`, which vanilla's `/ban`
     * and EssentialsX's both write.
     */
    public class Vanilla(private val server: Path) : Importer {
        override val name: String get() = "vanilla"

        override fun read(scope: String, now: Long, context: ImportContext): Haul {
            val players = server.resolve("banned-players.json")
            val ips = server.resolve("banned-ips.json")
            if (!NioFiles.exists(players) && !NioFiles.exists(ips)) return Haul.failed("there's no banned-players.json or banned-ips.json in the server's folder.")
            val haul = Haul()
            var index = 0
            for (ban in list(players) ?: return Haul.failed("couldn't read banned-players.json.")) {
                index++
                val id = PlayerRef.parseOrNull(ban["uuid"] ?: continue)?.takeIf { ban["uuid"]!!.length == 36 } ?: continue
                add(haul, ban, id, ban["name"] ?: id.toString(), null, scope, now, "vanilla:player:$id:${ban["created"]}")
            }
            for (ban in list(ips) ?: return Haul.failed("couldn't read banned-ips.json.")) {
                index++
                val ip = ban["ip"]?.takeIf(IpKeys::punishable) ?: continue
                add(haul, ban, null, IpKeys.label(ip), ip, scope, now, Backend.VANILLA_IP + Haul.IP + ":" + ban["created"])
            }
            if (index == 0) return Haul.failed("the server's ban lists are empty.")
            return haul
        }

        private fun add(haul: Haul, ban: Map<String, String?>, id: PlayerRef?, name: String, ip: String?, scope: String, now: Long, key: String) {
            val created = date(ban["created"]) ?: now
            val expiresText = ban["expires"]
            val expires = if (expiresText == null || expiresText.equals("forever", true)) null else date(expiresText)
            // Ended: vanilla forgets these too.
            if (expires != null && expires <= now) return
            val punishment = Punishment(
                0, PunishmentType.BAN, id, name, ip != null, ban["reason"] ?: "Banned by an operator.", null, ban["source"] ?: "Server",
                Instant.fromEpochMilliseconds(created), expires?.let { Instant.fromEpochMilliseconds(it) }, scope, false, null, null,
            )
            haul.add(Entry(punishment, ip), key)
        }

        private fun list(file: Path): List<Map<String, String?>>? {
            if (!NioFiles.exists(file)) return emptyList()
            return try {
                Json.objects(String(NioFiles.readAllBytes(file), StandardCharsets.UTF_8))
            } catch (e: Exception) {
                null
            }
        }

        private fun date(text: String?): Long? = try {
            text?.let { ZonedDateTime.parse(it, DATE).toInstant().toEpochMilli() }
        } catch (e: Exception) {
            null
        }

        private companion object {
            val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z")
        }
    }

    /**
     * EssentialsX's mutes, and each player's last name and IP, from
     * `plugins/Essentials/userdata/<uuid>.yml`. EssentialsX keeps bans in the server's own lists:
     * import those with `vanilla`.
     */
    public class Essentials(private val folder: Path) : Importer {
        override val name: String get() = "essentials"

        override fun read(scope: String, now: Long, context: ImportContext): Haul {
            val users = folder.resolve("userdata")
            if (!NioFiles.isDirectory(users)) return Haul.failed("there's no Essentials/userdata folder in plugins/.")
            val haul = Haul()
            NioFiles.list(users).use { files ->
                for (file in files) {
                    val fileName = file.fileName.toString()
                    if (!fileName.endsWith(".yml")) continue
                    val id = PlayerRef.parseOrNull(fileName.removeSuffix(".yml"))?.takeIf { fileName.length == 40 } ?: continue
                    val root = (JvmYaml.read(String(NioFiles.readAllBytes(file), StandardCharsets.UTF_8)) as? Outcome.Ok)?.value as? YamlNode.Mapping ?: continue
                    read(haul, root, id, scope, now)
                }
            }
            return haul
        }

        internal fun read(haul: Haul, data: YamlNode.Mapping, id: PlayerRef, scope: String, now: Long) {
            val name = text(data, "last-account-name") ?: text(data, "lastAccountName") ?: id.toString()
            val last = maxOf(number(data, "timestamps.login"), number(data, "timestamps.logout"))
            val ip = (text(data, "ip-address") ?: text(data, "ipAddress"))?.takeIf { it.isNotBlank() }
            haul.seen(id, name, ip, if (last > 0) last else now, if (last > 0) last else now)
            if (text(data, "muted")?.toBoolean() != true) return
            val until = number(data, "timestamps.mute")
            if (until in 1..now) return
            val reason = (text(data, "mute-reason") ?: text(data, "muteReason"))?.takeIf { it.isNotBlank() } ?: "Muted"
            val mute = Punishment(
                0, PunishmentType.MUTE, id, name, false, reason, null, "EssentialsX", Instant.fromEpochMilliseconds(now),
                if (until > 0) Instant.fromEpochMilliseconds(until) else null, scope, false, null, null,
            )
            haul.add(Entry(mute, null), "essentials:mute:$id")
        }

        private fun node(data: YamlNode.Mapping, path: String): YamlNode? {
            var node: YamlNode = data
            for (key in path.split('.')) node = (node as? YamlNode.Mapping)?.get(key) ?: return null
            return node
        }

        private fun text(data: YamlNode.Mapping, path: String): String? = (node(data, path) as? YamlNode.Scalar)?.content

        private fun number(data: YamlNode.Mapping, path: String): Long = text(data, path)?.trim()?.toLongOrNull() ?: 0
    }

    /**
     * A source's tables, by name, however they're read: through the core's database layer from
     * MySQL or MariaDB, or straight from a local file's connection ([LocalFiles]). Each source's
     * reading ([SqlSource.readFrom]) is the same on both.
     */
    public fun interface Tables {
        /** Every row of [table] (ordered by its `id` if [byId]), `null` if there's no such table, or why not. */
        public fun rows(table: String, byId: Boolean): Outcome<List<Row>, String>?
    }

    /** A plugin whose punishments are in a MySQL or MariaDB database it names in its config, or in a local file of its own. */
    public abstract class SqlSource(protected val drivers: Drivers, private val log: Log) : Importer {
        /** Opens the database at [host]:[port], reads it with [read], and closes it. */
        protected fun withDatabase(host: String, port: Int, database: String, user: String, password: String, read: (Database) -> Haul): Haul {
            val settings = DatabaseSettings(DatabaseSettings.Type.MYSQL, "", DatabaseSettings.MySql(host, port, database, user, password, "characterEncoding=utf8"), 1)
            val db = when (val opened = JdbcDatabase.open(settings, java.nio.file.Paths.get("unused.db"), drivers, log, "xBans import")) {
                is Outcome.Ok -> opened.value
                is Outcome.Err -> return Haul.failed("couldn't open $name's database at $host:$port/$database: ${opened.error.detail}")
            }
            try {
                return read(db)
            } finally {
                db.close().get(15, TimeUnit.SECONDS)
            }
        }

        /** Reads [haul] from a database already open, as the tests open one. */
        public fun readFrom(db: Database, prefix: String, scope: String, now: Long): Haul =
            readFrom(Tables { table, byId -> rows(db, table, if (byId) " ORDER BY id" else "") }, prefix, scope, now)

        /** Reads what [tables] hold. */
        public abstract fun readFrom(tables: Tables, prefix: String, scope: String, now: Long): Haul

        public companion object {
            /** Every row of `SELECT * FROM [table]` on [db], or `null` if there's no such table, or why not. */
            internal fun rows(db: Database, table: String, order: String): Outcome<List<Row>, String>? {
                val result = CompletableFuture<Outcome<List<Row>, String>?>()
                db.read { session ->
                    when (val found = session.query(db.sql.raw("SELECT * FROM $table$order"))) {
                        is Outcome.Ok -> Outcome.Ok(found.value)
                        is Outcome.Err -> found
                    }
                }.onSettled { read, error ->
                    result.complete(when (read) {
                        is Outcome.Ok -> Outcome.Ok(read.value)
                        is Outcome.Err -> {
                            val detail = read.error.detail
                            if (read.error.code == 1146 || read.error.sqlState == "42S02" || "no such table" in detail || "doesn't exist" in detail) null
                            else Outcome.Err("couldn't read $table: $detail")
                        }
                        null -> Outcome.Err("couldn't read $table: $error")
                    })
                }
                return result.get(5, TimeUnit.MINUTES)
            }

            internal fun text(row: Row, name: String): String? = when (val value = row.value(name)) {
                is SqlValue.Text -> value.value.takeIf { it.isNotEmpty() }
                is SqlValue.Integer -> value.value.toString()
                is SqlValue.Real -> value.value.toString()
                else -> null
            }

            internal fun flag(row: Row, name: String): Boolean = when (val value = row.value(name)) {
                is SqlValue.Integer -> value.value != 0L
                is SqlValue.Text -> value.value == "1" || value.value.equals("true", true)
                else -> false
            }

            // A time, written as epoch milliseconds or as a timestamp, depending on the column.
            internal fun millis(row: Row, name: String): Long = when (val value = row.value(name)) {
                is SqlValue.Integer -> value.value
                is SqlValue.Real -> value.value.toLong()
                is SqlValue.Text -> value.value.toLongOrNull() ?: try {
                    LocalDateTime.parse(value.value.trim().replace(' ', 'T').substringBefore('.')).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                } catch (e: Exception) {
                    0
                }
                else -> 0
            }

            internal fun player(text: String?): PlayerRef? = text?.let { if (it.length == 32 || it.length == 36) PlayerRef.parseOrNull(it) else null }

            /** A YAML file's scalar at [path], or `null`. */
            internal fun setting(file: Path, path: String): String? {
                val root = (JvmYaml.read(String(NioFiles.readAllBytes(file), StandardCharsets.UTF_8)) as? Outcome.Ok)?.value ?: return null
                var node: YamlNode = root
                for (key in path.split('.')) node = (node as? YamlNode.Mapping)?.get(key) ?: return null
                return (node as? YamlNode.Scalar)?.content
            }

            internal val PREFIX: Regex = Regex("[A-Za-z0-9_]*")
        }
    }

    /**
     * LiteBans' database: its bans, mutes, warnings and kicks with who revoked them, and its login
     * history for names, IPs and alts, read with the settings in `plugins/LiteBans/config.yml`:
     * from MySQL or MariaDB, or from its default H2 file, `litebans.mv.db` in its folder, with the
     * H2 that file's format needs ([LocalFiles.liteBans]).
     */
    public class LiteBans(private val folder: Path, drivers: Drivers, log: Log) : SqlSource(drivers, log) {
        override val name: String get() = "LiteBans"

        override fun read(scope: String, now: Long, context: ImportContext): Haul {
            val file = folder.resolve("config.yml")
            if (!NioFiles.exists(file)) return Haul.failed("there's no plugins/LiteBans/config.yml.")
            val driver = setting(file, "sql.driver") ?: "H2"
            val prefix = setting(file, "sql.table_prefix") ?: "litebans_"
            if (!PREFIX.matches(prefix)) return Haul.failed("LiteBans' table_prefix has characters xBans won't put in a query.")
            if (driver.equals("h2", true)) {
                return LocalFiles.liteBans(folder, file, drivers, context) { readFrom(it, prefix, scope, now) }
            }
            if (!driver.equals("mysql", true) && !driver.equals("mariadb", true)) {
                return Haul.failed("LiteBans uses $driver. xBans reads LiteBans from H2, MySQL or MariaDB: move it to one with LiteBans' own import first.")
            }
            val address = setting(file, "sql.address") ?: "localhost:3306"
            val host = address.substringBefore(':')
            val port = address.substringAfter(':', "3306").toIntOrNull() ?: 3306
            return withDatabase(host, port, setting(file, "sql.database") ?: "litebans", setting(file, "sql.username") ?: "root", setting(file, "sql.password") ?: "") {
                readFrom(it, prefix, scope, now)
            }
        }

        override fun readFrom(tables: Tables, prefix: String, scope: String, now: Long): Haul {
            val haul = Haul()
            val names = HashMap<PlayerRef, String>()
            val newest = HashMap<PlayerRef, Long>()
            val uses = HashMap<Pair<PlayerRef, String>, LongArray>()
            val withIp = HashSet<PlayerRef>()
            when (val history = tables.rows(prefix + "history", false)) {
                is Outcome.Err -> return Haul.failed(history.error)
                is Outcome.Ok -> for (row in history.value) {
                    val id = player(text(row, "uuid")) ?: continue
                    val name = text(row, "name") ?: continue
                    val at = millis(row, "date")
                    if (at >= (newest[id] ?: Long.MIN_VALUE)) {
                        newest[id] = at
                        names[id] = name
                    }
                    val ip = text(row, "ip")
                    if (ip != null && IpKeys.punishable(ip)) {
                        withIp += id
                        val span = uses.getOrPut(id to ip) { longArrayOf(at, at) }
                        span[0] = minOf(span[0], at)
                        span[1] = maxOf(span[1], at)
                    }
                }
                null -> Unit
            }
            for ((key, span) in uses) haul.seen(key.first, names[key.first], key.second, span[0], span[1])
            for ((id, name) in names) if (id !in withIp) haul.seen(id, name, null, newest[id]!!, newest[id]!!)
            for ((table, type) in listOf("bans" to PunishmentType.BAN, "mutes" to PunishmentType.MUTE, "warnings" to PunishmentType.WARN, "kicks" to PunishmentType.KICK)) {
                when (val rows = tables.rows(prefix + table, true)) {
                    is Outcome.Err -> return Haul.failed(rows.error)
                    is Outcome.Ok -> for (row in rows.value) {
                        // 192.168.1.*: xBans matches whole addresses only, so it would match nobody.
                        if (flag(row, "ipban_wildcard")) {
                            haul.wildcards++
                            continue
                        }
                        haul.add(row(row, type, names, now), "litebans:$table:${text(row, "id")}")
                    }
                    null -> Unit
                }
            }
            return haul
        }

        private fun row(row: Row, type: PunishmentType, names: Map<PlayerRef, String>, now: Long): Entry {
            val target = player(text(row, "uuid"))
            val ip = text(row, "ip")
            val ipBan = flag(row, "ipban") && ip != null && IpKeys.punishable(ip)
            val name = if (target != null) names[target] ?: target.toString() else IpKeys.label(ip)
            val time = millis(row, "time")
            val until = millis(row, "until")
            val created = if (time > 0) time else now
            val expires = if (type == PunishmentType.KICK || until <= 0) null else until
            // LiteBans' '*' is every server: global here too, whatever this server's default-scope.
            val scope = text(row, "server_scope").let { if (it == null || it == "*" || it.equals("global", true)) Punishment.GLOBAL else it }
            var removal: Removal? = null
            if (type.lasts) {
                val active = row.value("active") == SqlValue.Null || row.names.none { it == "active" } || flag(row, "active")
                val removedBy = text(row, "removed_by_name")
                val expired = removedBy == "#expired"
                val wouldBeActive = expires == null || expires > now
                if (!active && !expired && (removedBy != null || wouldBeActive)) {
                    val removed = millis(row, "removed_by_date")
                    removal = Removal(player(text(row, "removed_by_uuid")), removedBy ?: "LiteBans", Instant.fromEpochMilliseconds(if (removed > 0) removed else time), text(row, "removed_by_reason") ?: "")
                }
            }
            val punishment = Punishment(
                0, type, target, name, ipBan, text(row, "reason") ?: "", player(text(row, "banned_by_uuid")), text(row, "banned_by_name") ?: "Console",
                Instant.fromEpochMilliseconds(created), expires?.let { Instant.fromEpochMilliseconds(it) }, scope, flag(row, "silent"), null, removal,
            )
            return Entry(punishment, if (ipBan) ip else null)
        }
    }

    /**
     * AdvancedBan's database: every punishment in its `PunishmentHistory`, those still in
     * `Punishments` in force, from MySQL or MariaDB with the settings in
     * `plugins/AdvancedBan/MySQL.yml` (`UseMySQL: true` in its config), or from its default
     * HSQLDB file, `data/storage.*` in its folder ([LocalFiles.advancedBan]).
     */
    public class AdvancedBan(private val folder: Path, drivers: Drivers, log: Log) : SqlSource(drivers, log) {
        override val name: String get() = "AdvancedBan"

        override fun read(scope: String, now: Long, context: ImportContext): Haul {
            val config = folder.resolve("config.yml")
            if (!NioFiles.exists(config)) return Haul.failed("there's no plugins/AdvancedBan/config.yml.")
            if (setting(config, "UseMySQL")?.toBoolean() != true) {
                return LocalFiles.advancedBan(folder, drivers, context) { readFrom(it, "", scope, now) }
            }
            val mysql = folder.resolve("MySQL.yml")
            if (!NioFiles.exists(mysql)) return Haul.failed("there's no plugins/AdvancedBan/MySQL.yml.")
            return withDatabase(
                setting(mysql, "MySQL.IP") ?: "localhost", setting(mysql, "MySQL.Port")?.toIntOrNull() ?: 3306, setting(mysql, "MySQL.DB-Name") ?: "advancedban",
                setting(mysql, "MySQL.Username") ?: "root", setting(mysql, "MySQL.Password") ?: "",
            ) { readFrom(it, "", scope, now) }
        }

        override fun readFrom(tables: Tables, prefix: String, scope: String, now: Long): Haul {
            val haul = Haul()
            // What's still in force, by who, what and when it started: Punishments' ids aren't its history's.
            val inForce = HashSet<String>()
            when (val active = tables.rows(prefix + "Punishments", false)) {
                is Outcome.Err -> return Haul.failed(active.error)
                is Outcome.Ok -> for (row in active.value) inForce += key(row)
                null -> return Haul.failed("AdvancedBan's database has no Punishments table.")
            }
            val names = HashMap<PlayerRef, String>()
            val history = when (val read = tables.rows(prefix + "PunishmentHistory", true)) {
                is Outcome.Err -> return Haul.failed(read.error)
                is Outcome.Ok -> read.value
                null -> return Haul.failed("AdvancedBan's database has no PunishmentHistory table.")
            }
            for (row in history) {
                val kind = text(row, "punishmenttype")?.uppercase() ?: continue
                val type = when (kind) {
                    "BAN", "TEMP_BAN", "IP_BAN", "TEMP_IP_BAN" -> PunishmentType.BAN
                    "MUTE", "TEMP_MUTE" -> PunishmentType.MUTE
                    "WARNING", "TEMP_WARNING" -> PunishmentType.WARN
                    "KICK" -> PunishmentType.KICK
                    else -> continue // NOTE: not a punishment.
                }
                val ipBan = kind.contains("IP")
                val raw = text(row, "uuid") ?: continue
                val target = if (ipBan) null else player(raw) ?: continue
                if (ipBan && !IpKeys.punishable(raw)) continue
                val name = text(row, "name") ?: target?.toString() ?: IpKeys.label(raw)
                target?.let { names[it] = name }
                val start = millis(row, "start")
                val end = millis(row, "end")
                val expires = if (type == PunishmentType.KICK || end <= 0) null else end
                val operator = text(row, "operator") ?: "Console"
                // In its history but no longer in force, though it hadn't ended: someone lifted it.
                val lifted = type.lasts && key(row) !in inForce && (expires == null || expires > now)
                val removal = if (lifted) Removal(null, "AdvancedBan", Instant.fromEpochMilliseconds(maxOf(start, 0)), "") else null
                val punishment = Punishment(
                    0, type, target, if (target == null) IpKeys.label(raw) else name, ipBan, text(row, "reason") ?: "", null,
                    if (operator.equals("CONSOLE", true)) "Console" else operator, Instant.fromEpochMilliseconds(if (start > 0) start else now),
                    expires?.let { Instant.fromEpochMilliseconds(it) }, Punishment.GLOBAL, false, null, removal,
                )
                haul.add(Entry(punishment, if (ipBan) raw else null), "advancedban:history:${text(row, "id")}")
            }
            for ((id, name) in names) haul.seen(id, name, null, now, now)
            return haul
        }

        private fun key(row: Row): String = "${text(row, "uuid")}|${text(row, "punishmenttype")}|${millis(row, "start")}"
    }
}

/** Just enough JSON for the server's ban lists: an array of objects of text, numbers and nulls. */
internal object Json {
    fun objects(text: String): List<Map<String, String?>> {
        val parser = Parser(text)
        parser.space()
        if (parser.peek() != '[') throw IllegalArgumentException("isn't a list")
        @Suppress("UNCHECKED_CAST")
        return (parser.value() as List<Any?>).filterIsInstance<Map<String, String?>>()
    }

    private class Parser(val text: String) {
        var at = 0

        fun peek(): Char = if (at < text.length) text[at] else '\u0000'

        fun space() {
            while (at < text.length && text[at].isWhitespace()) at++
        }

        fun value(): Any? {
            space()
            return when (peek()) {
                '[' -> {
                    at++
                    val out = ArrayList<Any?>()
                    space()
                    if (peek() == ']') return out.also { at++ }
                    while (true) {
                        out += value()
                        space()
                        when (text[at++]) {
                            ',' -> continue
                            ']' -> return out
                            else -> throw IllegalArgumentException("bad list at $at")
                        }
                    }
                    @Suppress("UNREACHABLE_CODE")
                    out
                }
                '{' -> {
                    at++
                    val out = LinkedHashMap<String, String?>()
                    space()
                    if (peek() == '}') return out.also { at++ }
                    while (true) {
                        space()
                        val key = string()
                        space()
                        if (text[at++] != ':') throw IllegalArgumentException("bad object at $at")
                        val value = value()
                        out[key] = value?.toString()
                        space()
                        when (text[at++]) {
                            ',' -> continue
                            '}' -> return out
                            else -> throw IllegalArgumentException("bad object at $at")
                        }
                    }
                    @Suppress("UNREACHABLE_CODE")
                    out
                }
                '"' -> string()
                else -> {
                    val start = at
                    while (at < text.length && text[at] !in ",]} \t\r\n") at++
                    val word = text.substring(start, at)
                    if (word == "null") null else word
                }
            }
        }

        fun string(): String {
            if (text[at++] != '"') throw IllegalArgumentException("expected text at $at")
            val out = StringBuilder()
            while (true) {
                val c = text[at++]
                when (c) {
                    '"' -> return out.toString()
                    '\\' -> when (val e = text[at++]) {
                        'n' -> out.append('\n')
                        't' -> out.append('\t')
                        'r' -> out.append('\r')
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'u' -> {
                            out.append(text.substring(at, at + 4).toInt(16).toChar())
                            at += 4
                        }
                        else -> out.append(e)
                    }
                    else -> out.append(c)
                }
            }
        }
    }
}
