package co.xenastudios.xbackup.backup

import co.xenastudios.core.common.family.FamilyBackups
import co.xenastudios.core.common.family.FamilyBoard
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Driver
import java.sql.SQLException
import java.util.Properties
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Copies a live SQLite database consistently. Copying the file's bytes while a plugin writes to it
 * can give a corrupt copy, so xBackup never does: the database's own plugin copies it, if it's one
 * of the family's ([Family]), and otherwise SQLite does ([Sqlite]).
 */
public fun interface Databases {
    /** Writes a consistent copy of [database] to [target], which must not exist. */
    public fun copy(database: Path, target: Path)

    /**
     * SQLite's own `VACUUM INTO` (3.27 and later), or on an older SQLite its online backup, through
     * [driver]. One read of one consistent moment: in WAL mode the database's plugin keeps writing
     * meanwhile; in the older journal mode its writes wait for the copy (up to the busy timeout).
     */
    public class Sqlite(private val connect: (String) -> java.sql.Connection?) : Databases {
        public constructor(driver: Driver) : this(Connect(driver))

        private class Connect(private val driver: Driver) : (String) -> java.sql.Connection? {
            override fun invoke(url: String): java.sql.Connection? = driver.connect(url, Properties())
        }

        override fun copy(database: Path, target: Path) {
            try {
                (connect("jdbc:sqlite:" + database.toAbsolutePath()) ?: throw SQLException("the SQLite driver doesn't open it")).use { connection ->
                    connection.createStatement().use { it.execute("PRAGMA busy_timeout=15000") }
                    try {
                        connection.prepareStatement("VACUUM INTO ?").use {
                            it.setString(1, target.toAbsolutePath().toString())
                            it.execute()
                        }
                    } catch (e: SQLException) {
                        // SQLite before 3.27 has no VACUUM INTO: the driver's online backup, which
                        // sqlite-jdbc has had since 3.6.
                        if (Files.exists(target) || '"' in target.toString()) throw e
                        connection.createStatement().use { it.executeUpdate("backup to \"${target.toAbsolutePath()}\"") }
                    }
                }
            } catch (e: SQLException) {
                Files.deleteIfExists(target)
                throw java.io.IOException("SQLite couldn't copy it (${e.message})", e)
            }
        }
    }

    /**
     * The family's: a database one of the rewritten Xena plugins has open is copied by that
     * plugin's own writer, between two of its batches (decisions/2026-11-27-the-familys-backup-seam.md),
     * so its writes queue behind the copy and none lands half in it. Any other database, or one its
     * plugin couldn't copy (it was closing), goes to [fallback].
     *
     * @param wait how long a plugin may take to copy its database before [fallback] is tried
     */
    public class Family(private val board: FamilyBoard, private val fallback: Databases?, private val waitMillis: Long = 300_000) : Databases {
        /** Which plugin's writer copied each database, by its real path, for the backup's record. */
        public val copiedBy: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap()

        override fun copy(database: Path, target: Path) {
            val real = realPath(database)
            var why: String? = null
            for (entry in entries(board)) {
                if (entry[FamilyBackups.KIND] != FamilyBackups.SQLITE) continue
                val where = entry[FamilyBackups.WHERE] as? String ?: continue
                if (realPath(path(where)) != real) continue
                val handle = entry[FamilyBackups.HANDLE] as? java.util.function.Function<*, *> ?: continue
                why = ask(handle, target)
                if (why == null && Files.isRegularFile(target)) {
                    copiedBy[real] = entry[FamilyBackups.PLUGIN] as? String ?: "?"
                    return
                }
                Files.deleteIfExists(target)
            }
            val other = fallback ?: throw java.io.IOException(why ?: "SQLite isn't available to copy it safely")
            other.copy(database, target)
        }

        // The plugin's answer: null once the copy is whole, or why there's none.
        private fun ask(handle: java.util.function.Function<*, *>, target: Path): String? {
            @Suppress("UNCHECKED_CAST")
            val answer = try {
                (handle as java.util.function.Function<Any?, Any?>).apply(arrayOf(FamilyBackups.SNAPSHOT, target.toAbsolutePath().toString()))
            } catch (e: RuntimeException) {
                return "its plugin's copy failed ($e)"
            }
            val future = answer as? Future<*> ?: return "its plugin didn't take the request ($answer)"
            return try {
                future.get(waitMillis, TimeUnit.MILLISECONDS) as? String
            } catch (e: TimeoutException) {
                "its plugin took longer than ${waitMillis / 1000} s to copy it"
            } catch (e: Exception) {
                "its plugin's copy failed (${e.cause ?: e})"
            }
        }

        public companion object {
            /** Every entry on the board's backup map, as it is now. */
            @JvmStatic
            public fun entries(board: FamilyBoard): List<Array<*>> {
                val map = board.map(FamilyBackups.MAP)
                return map.keys().mapNotNull { key -> (map[key] as? Array<*>)?.takeIf { it.size >= FamilyBackups.SIZE } }
            }

            /**
             * What a backup can't hold, from the board: each plugin keeping its data in MySQL, as
             * `xMoney: MySQL at db:3306/mc (xmoney_ tables)`, one line per plugin and place.
             */
            @JvmStatic
            public fun external(board: FamilyBoard): List<String> = entries(board)
                .filter { it[FamilyBackups.KIND] == FamilyBackups.MYSQL }
                .map { "${it[FamilyBackups.PLUGIN]}: MySQL at ${it[FamilyBackups.WHERE]} (${it[FamilyBackups.PREFIX]} tables)" }
                .distinct().sorted()

            /** The SQLite files the family has open, by real path: what a backup must copy through them. */
            @JvmStatic
            public fun sqliteFiles(board: FamilyBoard): Map<String, String> = entries(board)
                .filter { it[FamilyBackups.KIND] == FamilyBackups.SQLITE }
                .mapNotNull { entry -> (entry[FamilyBackups.WHERE] as? String)?.let { realPath(path(it)) to (entry[FamilyBackups.PLUGIN] as? String ?: "?") } }
                .toMap()

            internal fun realPath(file: Path): String = try {
                file.toRealPath().toString()
            } catch (e: java.io.IOException) {
                file.toAbsolutePath().normalize().toString()
            }
        }
    }
}
