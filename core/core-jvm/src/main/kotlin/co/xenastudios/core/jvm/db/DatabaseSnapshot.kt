package co.xenastudios.core.jvm.db

import co.xenastudios.core.api.onSettled
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.db.DbError
import co.xenastudios.core.common.db.Dialect
import co.xenastudios.core.common.db.SqlVersion
import co.xenastudios.core.common.family.FamilyBackups
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.CompletableFuture

/**
 * What a [JdbcDatabase]'s backup handle does ([FamilyBackups]): a consistent copy of its SQLite
 * file, written by its own writer between two batches, so the plugin's writes wait behind it
 * (queued, never on a game thread) and none lands half in the copy. Loaded only when a backup
 * asks: a server that never backs up never loads it.
 *
 * How the copy is made depends on the SQLite the server has:
 * - **3.27 and later:** `VACUUM INTO`, one read of one moment of the file, compacted.
 * - **Older** (Spigot 1.8.8 ships 3.7.2): the driver's online backup (`backup to`), and if the
 *   driver has none, the file and its write-ahead log, copied while this connection holds the
 *   write lock, then folded into one file.
 */
internal object DatabaseSnapshot {
    /** The first SQLite with `VACUUM INTO`. */
    private val VACUUM_INTO = SqlVersion(3, 27, 0)

    /**
     * The handle's answer to [request], for the database at [where]: a `CompletableFuture` that
     * completes with `null` once the copy is whole, or a `String` saying why there's none; or
     * [FamilyBackups.UNSUPPORTED] at once for a request it doesn't know.
     */
    fun answer(database: JdbcDatabase, where: String?, request: Any?): Any? {
        val asked = request as? Array<*>
        val target = asked?.getOrNull(1) as? String
        if (asked == null || asked.size != 2 || asked[0] != FamilyBackups.SNAPSHOT || target == null || where == null) {
            return FamilyBackups.UNSUPPORTED
        }
        val done = CompletableFuture<Any?>()
        val dialect = database.dialect
        if (dialect !is Dialect.Sqlite) {
            done.complete("${database.description} isn't a SQLite file")
            return done
        }
        val source = Paths.get(where)
        val to = Paths.get(target)
        database.session { session -> copy((session as JdbcSession).connection, dialect.version, source, to) }.onSettled { value, error ->
            done.complete(
                when {
                    error != null -> "the copy failed: $error"
                    value is Outcome.Err -> value.error.detail
                    else -> null
                },
            )
        }
        return done
    }

    /** Writes a consistent copy of [source], open on [connection], to [target]; on failure, leaves nothing there. */
    fun copy(connection: Connection, version: SqlVersion, source: Path, target: Path): Outcome<Unit, DbError> = try {
        Files.deleteIfExists(target)
        target.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        if (version >= VACUUM_INTO) {
            connection.prepareStatement("VACUUM INTO ?").use {
                it.setString(1, target.toAbsolutePath().toString())
                it.execute()
            }
        } else if (!onlineBackup(connection, target)) {
            lockedCopy(connection, source, target)
        }
        Outcome.Ok(Unit)
    } catch (e: SQLException) {
        failed(target, "SQLite couldn't copy it (${e.message})")
    } catch (e: IOException) {
        failed(target, "couldn't write the copy (${e.message})")
    } catch (e: ReflectiveOperationException) {
        failed(target, "no SQLite driver to fold the copy's log in (${e.message})")
    }

    /**
     * The SQLite driver's online backup (`backup to <file>`, sqlite-jdbc's own statement), which
     * copies every page as one read. `false` if this driver has no such statement.
     */
    fun onlineBackup(connection: Connection, target: Path): Boolean {
        val path = target.toAbsolutePath().toString()
        if (path.contains('"')) return false
        return try {
            connection.createStatement().use { it.executeUpdate("backup to \"$path\"") }
            Files.isRegularFile(target)
        } catch (e: SQLException) {
            Files.deleteIfExists(target)
            false
        }
    }

    /**
     * The file itself, copied while this connection holds SQLite's write lock (`BEGIN
     * IMMEDIATE`), so no connection, the family's on a shared file included, can commit until it
     * goes: the file and its write-ahead log are then one committed moment, and nothing changes
     * them (a checkpoint only follows a commit). Both are copied, then the copy's log is folded
     * into the copy by a connection of its own, which leaves one file.
     */
    fun lockedCopy(connection: Connection, source: Path, target: Path) {
        val wal = sibling(source, "-wal")
        val copiedWal = sibling(target, "-wal")
        val part = sibling(target, ".part")
        connection.createStatement().use { it.execute("BEGIN IMMEDIATE") }
        try {
            Files.copy(source, part, StandardCopyOption.REPLACE_EXISTING)
            Files.deleteIfExists(sibling(part, "-wal"))
            if (Files.exists(wal) && Files.size(wal) > 0) Files.copy(wal, sibling(part, "-wal"))
        } finally {
            connection.createStatement().use { it.execute("ROLLBACK") }
        }
        if (Files.exists(sibling(part, "-wal"))) {
            val driver = Class.forName("org.sqlite.JDBC", true, connection.javaClass.classLoader).getDeclaredConstructor().newInstance() as java.sql.Driver
            val copy = driver.connect("jdbc:sqlite:" + part.toAbsolutePath(), java.util.Properties())
                ?: throw SQLException("the SQLite driver doesn't open " + part.fileName)
            // Closing the last connection folds the log into the file and deletes it.
            copy.use { c -> c.createStatement().use { it.execute("PRAGMA wal_checkpoint") } }
            Files.deleteIfExists(sibling(part, "-wal"))
            Files.deleteIfExists(sibling(part, "-shm"))
        }
        Files.deleteIfExists(copiedWal)
        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun sibling(path: Path, suffix: String): Path = path.resolveSibling(path.fileName.toString() + suffix)

    private fun failed(target: Path, why: String): Outcome<Unit, DbError> {
        try {
            Files.deleteIfExists(target)
        } catch (ignored: IOException) {
            // The backup that asked deletes what it finds of a failed copy.
        }
        return Outcome.Err(DbError(DbError.Kind.OTHER, why))
    }
}
