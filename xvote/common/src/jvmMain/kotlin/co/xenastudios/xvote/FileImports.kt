package co.xenastudios.xvote

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.onSettled
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.config.YamlNode
import co.xenastudios.core.common.db.Database
import co.xenastudios.core.common.db.DatabaseSettings
import co.xenastudios.core.common.db.Row
import co.xenastudios.core.common.db.SqlValue
import co.xenastudios.core.common.platform.Log
import co.xenastudios.core.jvm.db.Drivers
import co.xenastudios.core.jvm.db.JdbcDatabase
import co.xenastudios.xvote.receiver.NuVotifier
import co.xenastudios.xvote.receiver.Secrets
import co.xenastudios.xvote.receiver.Tokens
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * What `/xvote import` reads on the JVM, off the server's threads, as 1.x's `Imports`:
 *
 * - **NuVotifier**: its `rsa/` keys, `tokens:` and port, into xVote's folder; xVote's own are moved
 *   aside as `.before-import-<time>` files first, readable by the owner only.
 * - **VotingPlugin**: every player's all-time total and last vote from its SQLite `Users` table
 *   (`plugins/VotingPlugin/Users.db`, or `Data/Users.db`), read from a copy so its file is never
 *   opened, with the server's SQLite driver or one fetched for it.
 * - **SuperbVote**: its `votes.json`, or its MySQL table when its config says `database: mysql`.
 *
 * Neither plugin keeps single votes, so day, week and month totals start from the switch.
 */
/**
 * [FileImports], made with the first `/xvote import`, not as the server starts: what reads other
 * plugins' files is never loaded on a server that doesn't import.
 */
public class DeferredImports(
    private val plugins: Path,
    private val folder: Path,
    private val drivers: Drivers,
    private val log: Log,
    private val files: co.xenastudios.xvote.Files?,
    private val server: VoteServer? = null,
) : VoteImports {
    @Volatile
    private var made: FileImports? = null

    private fun imports(): FileImports = made ?: synchronized(this) { made ?: FileImports(plugins, folder, drivers, log, files, server).also { made = it } }

    override fun nuVotifier(): Outcome<String, String>? = imports().nuVotifier()

    override fun totals(source: String): Outcome<Pair<String, List<Imported>>, String> = imports().totals(source)
}

public class FileImports(
    /** The plugins folder (Bukkit's, or the proxy's). */
    private val plugins: Path,
    /** xVote's own folder. */
    private val folder: Path,
    private val drivers: Drivers,
    private val log: Log,
    /** Where the receiver is set to NuVotifier's port (`config.yml`). */
    private val files: co.xenastudios.xvote.Files?,
    /** Names for imported uuids with none, from the server's user cache. */
    private val server: VoteServer? = null,
) : VoteImports {

    override fun nuVotifier(): Outcome<String, String>? {
        val found = NuVotifier.find(plugins) ?: return null
        return try {
            val read = NuVotifier.read(found)
            val stamp = System.currentTimeMillis().toString()
            // Only what NuVotifier has is replaced, so only that is moved aside.
            if (read.keys != null) backUp(folder.resolve("rsa"), folder.resolve("rsa.before-import-$stamp"))
            if (read.tokens != null) backUp(folder.resolve(Tokens.FILE), folder.resolve("${Tokens.FILE}.before-import-$stamp"))
            NuVotifier.write(read, folder)
            read.usePort(files)?.let { log.warn("Couldn't set receiver.port to NuVotifier's ${read.port}: $it") }
            Outcome.Ok(read.describe())
        } catch (e: IOException) {
            Outcome.Err(e.message ?: e.toString())
        }
    }

    // Moved aside, and kept owner-only: they hold the private key or the tokens.
    private fun backUp(from: Path, to: Path) {
        if (!Files.exists(from)) return
        Files.move(from, to, StandardCopyOption.REPLACE_EXISTING)
        if (Files.isDirectory(to)) Secrets.ownerOnly(to.resolve("private.key")) else Secrets.ownerOnly(to)
    }

    override fun totals(source: String): Outcome<Pair<String, List<Imported>>, String> = try {
        val read = if (source == "superbvote") superbVote() else votingPlugin()
        Outcome.Ok(read.first to read.second.map { if (it.name.isNotBlank()) it else Imported(it.player, server?.nameOf(it.player) ?: "", it.total, it.lastVote) })
    } catch (e: IOException) {
        Outcome.Err(e.message ?: e.toString())
    }

    // ---- SuperbVote ----

    private fun superbVote(): Pair<String, List<Imported>> {
        val dir = plugins.resolve("SuperbVote")
        val configFile = dir.resolve("config.yml")
        val config = if (Files.isRegularFile(configFile)) {
            Tokens.readYaml(configFile) as? YamlNode.Mapping ?: throw IOException("SuperbVote's config.yml isn't a YAML section.")
        } else {
            null
        }
        fun setting(path: String, default: String): String {
            var node: YamlNode = config ?: return default
            for (key in path.split('.')) node = (node as? YamlNode.Mapping)?.get(key) ?: return default
            return (node as? YamlNode.Scalar)?.content ?: default
        }
        if (setting("storage.database", "json").equals("mysql", ignoreCase = true)) {
            val host = setting("storage.mysql.host", "localhost")
            val port = setting("storage.mysql.port", "3306").trim().toIntOrNull() ?: 3306
            val table = setting("storage.mysql.table", "superbvote")
            if (!table.all { it.isLetterOrDigit() || it == '_' }) throw IOException("SuperbVote's storage.mysql.table isn't a plain table name.")
            val database = setting("storage.mysql.database", "superbvote")
            val settings = DatabaseSettings(
                DatabaseSettings.Type.MYSQL, "",
                DatabaseSettings.MySql(host, port, database, setting("storage.mysql.username", "root"), setting("storage.mysql.password", ""), "connectTimeout=10000&socketTimeout=60000"),
                1,
            )
            val rows = withDatabase(settings, java.nio.file.Paths.get("unused.db"), "SELECT uuid, last_name, votes, last_vote FROM $table WHERE votes > 0", "SuperbVote's MySQL table")
            val players = rows.mapNotNull { row ->
                val id = player(text(row, 0)) ?: return@mapNotNull null
                Imported(id, text(row, 1) ?: "", number(row, 2).toInt(), millis(row.values[3]))
            }
            return "MySQL at $host:$port, table $table" to players
        }
        val file = dir.resolve(setting("storage.json.file", "votes.json"))
        if (!Files.isRegularFile(file)) throw IOException("there's no plugins/SuperbVote/${dir.relativize(file)}.")
        return "plugins/SuperbVote/${dir.relativize(file)}" to superbVoteJson(String(Files.readAllBytes(file), Charsets.UTF_8))
    }

    // ---- VotingPlugin ----

    private fun votingPlugin(): Pair<String, List<Imported>> {
        val dir = plugins.resolve("VotingPlugin")
        val file = listOf(dir.resolve("Users.db"), dir.resolve("Data").resolve("Users.db")).firstOrNull(Files::isRegularFile)
            ?: throw IOException("there's no plugins/VotingPlugin/Users.db. Only VotingPlugin's SQLite storage can be imported; for MySQL, switch it to SQLite first, or ask us.")
        // A copy, so VotingPlugin's own file is never opened by a driver, whatever it does as it opens.
        val copy = Files.createTempFile(folder.also { Files.createDirectories(it) }, "votingplugin-", ".db")
        try {
            Files.copy(file, copy, StandardCopyOption.REPLACE_EXISTING)
            val settings = DatabaseSettings(DatabaseSettings.Type.SQLITE, "", DatabaseSettings.MySql(), 1)
            val tables = withDatabase(settings, copy, "SELECT name FROM sqlite_master WHERE type = 'table'", "VotingPlugin's ${file.fileName}")
            val table = tables.mapNotNull { text(it, 0) }.firstOrNull { it.equals("Users", true) || it.equals("VotingPlugin_Users", true) }
                ?: throw IOException("${file.fileName} has no Users table.")
            val rows = withDatabase(settings, copy, "SELECT * FROM \"$table\"", "VotingPlugin's ${file.fileName}")
            return "plugins/VotingPlugin/${dir.relativize(file)}" to votingPluginRows(rows)
        } finally {
            Files.deleteIfExists(copy)
        }
    }

    private fun withDatabase(settings: DatabaseSettings, file: Path, query: String, what: String): List<Row> {
        val db: Database = when (val opened = JdbcDatabase.open(settings, file, drivers, log, "xVote import")) {
            is Outcome.Ok -> opened.value
            is Outcome.Err -> throw IOException("couldn't open $what: ${opened.error.detail}")
        }
        try {
            val result = CompletableFuture<Outcome<List<Row>, String>>()
            db.read { session -> session.query(db.sql.raw(query)) }.onSettled { read, error ->
                result.complete(when (read) {
                    is Outcome.Ok -> Outcome.Ok(read.value)
                    is Outcome.Err -> Outcome.Err(read.error.detail)
                    null -> Outcome.Err(error.toString())
                })
            }
            return when (val rows = result.get(5, TimeUnit.MINUTES)) {
                is Outcome.Ok -> rows.value
                is Outcome.Err -> throw IOException("couldn't read $what: ${rows.error}")
            }
        } finally {
            db.close().get(15, TimeUnit.SECONDS)
        }
    }

    public companion object {
        /** SuperbVote's JSON: `{"version": 2, "records": {"<uuid>": {"lastKnownUsername", "votes", "lastVoted"}}}`, or 0.x's `{"<uuid>": votes}`. */
        @JvmStatic
        public fun superbVoteJson(json: String): List<Imported> {
            val players = ArrayList<Imported>()
            try {
                val root = Json.parse(json) as? Map<*, *> ?: throw IOException("SuperbVote's votes file isn't the JSON it writes: not an object")
                val records = root["records"] as? Map<*, *> ?: root
                for ((key, value) in records) {
                    val id = player(key?.toString()) ?: continue
                    // SuperbVote 0.x kept only a number per player.
                    if (value is Json.Number) {
                        players += Imported(id, "", value.toLongOrNull()?.toInt() ?: 0, 0)
                        continue
                    }
                    val record = value as? Map<*, *> ?: continue
                    val votes = (record["votes"] as? Json.Number)?.toLongOrNull()?.toInt() ?: 0
                    val last = (record["lastVoted"] as? Json.Number)?.toLongOrNull() ?: 0L
                    if (votes > 0) players += Imported(id, record["lastKnownUsername"] as? String ?: "", votes, last)
                }
            } catch (e: Json.Malformed) {
                throw IOException("SuperbVote's votes file isn't the JSON it writes: ${e.message}", e)
            }
            return players
        }

        /** VotingPlugin's `Users` rows: `uuid`, `PlayerName`, `AllTimeTotal` and `LastVotes`, found by name whatever their case. */
        @JvmStatic
        public fun votingPluginRows(rows: List<Row>): List<Imported> {
            val first = rows.firstOrNull() ?: return emptyList()
            val names = first.names
            val uuid = names.indexOf("uuid")
            val name = names.indexOf("playername")
            val total = names.indexOf("alltimetotal")
            val last = names.indexOf("lastvotes")
            if (uuid < 0 || total < 0) throw IOException("VotingPlugin's Users table has no uuid or AllTimeTotal column.")
            val players = ArrayList<Imported>()
            for (row in rows) {
                val id = player(text(row, uuid)) ?: continue
                val votes = number(row, total).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                if (votes > 0) players += Imported(id, if (name < 0) "" else text(row, name) ?: "", votes, if (last < 0) 0 else lastVote(text(row, last)))
            }
            return players
        }

        /**
         * The latest time in VotingPlugin's `LastVotes`: a list of `site//millis`, one per site, kept as
         * text joined with `%line%` (VotingPluginUser.setLastVotes, AdvancedCore's UserData). 0 if there's none.
         */
        @JvmStatic
        public fun lastVote(lastVotes: String?): Long {
            if (lastVotes.isNullOrBlank()) return 0
            var latest = 0L
            for (entry in lastVotes.split("%line%")) {
                val split = entry.lastIndexOf("//")
                if (split < 0) continue
                // A damaged entry: the others still count.
                entry.substring(split + 2).trim().toLongOrNull()?.let { latest = maxOf(latest, it) }
            }
            return latest
        }

        private fun player(text: String?): PlayerRef? = text?.trim()?.let { if (it.length == 36) PlayerRef.parseOrNull(it) else null }

        private fun text(row: Row, i: Int): String? = when (val v = row.values[i]) {
            is SqlValue.Text -> v.value
            is SqlValue.Integer -> v.value.toString()
            is SqlValue.Real -> v.value.toString()
            else -> null
        }

        // VotingPlugin keeps numbers as text.
        private fun number(row: Row, i: Int): Long = when (val v = row.values[i]) {
            is SqlValue.Integer -> v.value
            is SqlValue.Real -> v.value.toLong()
            is SqlValue.Text -> v.value.trim().toLongOrNull() ?: 0
            else -> 0
        }

        // SuperbVote's last_vote is a TIMESTAMP: the driver hands it back as text or a number.
        private fun millis(value: SqlValue): Long = when (value) {
            is SqlValue.Integer -> value.value
            is SqlValue.Text -> value.value.trim().toLongOrNull() ?: try {
                java.sql.Timestamp.valueOf(value.value.trim().substringBefore('.').let { if (it.length == 19) it else "$it:00" }).time
            } catch (e: IllegalArgumentException) {
                0
            }
            else -> 0
        }
    }
}
