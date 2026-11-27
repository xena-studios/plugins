package co.xenastudios.core.jvm.db

import co.xenastudios.core.common.db.Dialect
import co.xenastudios.core.common.db.SqlVersion
import co.xenastudios.core.common.db.values
import co.xenastudios.core.common.db.write
import co.xenastudios.core.common.family.FamilyBackups
import co.xenastudios.core.jvm.family.JvmFamilyBoard
import co.xenastudios.core.testing.RecordingLog
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Function
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The family's backup seam (decisions/2026-11-27-the-familys-backup-seam.md): a database puts
 * itself on the board as it opens, takes itself off as it closes, and its handle writes a whole,
 * consistent copy while the plugin keeps writing.
 */
class FamilyBackupsTest {
    private val folder: Path = Files.createTempDirectory("xena-backups")
    private val file = folder.resolve("data/test.db")
    private val open = ArrayList<JdbcDatabase>()

    @AfterTest
    fun close() {
        open.forEach { it.close().await() }
        JvmFamilyBoard.server = null
        folder.toFile().deleteRecursively()
    }

    private fun board(): JvmFamilyBoard = JvmFamilyBoard(JvmFamilyBoard.newRoot()).also { JvmFamilyBoard.server = it }

    private fun entries(board: JvmFamilyBoard): List<Array<*>> {
        val map = board.map(FamilyBackups.MAP)
        return map.keys().mapNotNull { map[it] as? Array<*> }
    }

    private fun handle(entry: Array<*>): Function<Any?, Any?> {
        @Suppress("UNCHECKED_CAST")
        return entry[FamilyBackups.HANDLE] as Function<Any?, Any?>
    }

    private fun rows(file: Path, table: String = "t_ledger"): Int = sqlite(file).use { c ->
        c.createStatement().use { s -> s.executeQuery("SELECT COUNT(*) FROM $table").use { it.next(); it.getInt(1) } }
    }

    private fun intact(file: Path): Boolean = sqlite(file).use { c ->
        c.createStatement().use { s -> s.executeQuery("PRAGMA integrity_check").use { it.next() && it.getString(1) == "ok" } }
    }

    private fun sqlite(file: Path): Connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath())

    private fun entry(n: Int) = values(Ledger) { it[Ledger.n] = n }

    @Test
    fun aDatabaseIsOnTheBoardWhileItsOpen() {
        val board = board()
        val database = DbFixtures.sqlite(file).also { open += it }
        val entry = entries(board).single()
        assertEquals(FamilyBackups.SQLITE, entry[FamilyBackups.KIND])
        assertEquals("Test", entry[FamilyBackups.PLUGIN])
        assertEquals(file.toAbsolutePath().normalize().toString(), entry[FamilyBackups.WHERE])
        assertEquals("t_", entry[FamilyBackups.PREFIX])
        assertEquals(database.description, entry[FamilyBackups.DESCRIPTION])
        assertTrue(entry[FamilyBackups.HANDLE] is Function<*, *>)
        assertEquals(FamilyBackups.SIZE, entry.size)

        database.close().await()
        open -= database
        assertTrue(entries(board).isEmpty(), "closing takes it off the board")
    }

    @Test
    fun noBoardNoEntry() {
        JvmFamilyBoard.server = null
        DbFixtures.sqlite(file).also { open += it }
        // Nothing to look at: opening without a board must simply work, as in a test or a tool.
    }

    @Test
    fun theHandleCopiesWhileThePluginWrites() {
        val board = board()
        val database = DbFixtures.sqlite(file).also { open += it }
        val written = AtomicInteger()
        val stop = AtomicBoolean()
        val writer = Thread {
            var n = 0
            while (!stop.get()) {
                database.write(database.sql.insert(entry(n++))).await().ok()
                written.incrementAndGet()
            }
        }
        writer.start()
        try {
            while (written.get() < 200) Thread.sleep(5)
            val target = folder.resolve("copy/test.db")
            val before = written.get()
            val answer = handle(entries(board).single()).apply(arrayOf(FamilyBackups.SNAPSHOT, target.toString()))
            @Suppress("UNCHECKED_CAST")
            val done = (answer as CompletableFuture<Any?>).get(30, TimeUnit.SECONDS)
            val after = written.get()
            assertNull(done, "the copy is whole")
            assertTrue(intact(target), "the copy passes SQLite's integrity check")
            val copied = rows(target)
            // One moment between the request and its answer: every write before it, none half.
            assertTrue(copied in before..after, "$copied rows, between $before and $after")
        } finally {
            stop.set(true)
            writer.join(10_000)
        }
    }

    @Test
    fun aClosedDatabaseSaysSo() {
        val board = board()
        val database = DbFixtures.sqlite(file).also { open += it }
        val handle = handle(entries(board).single())
        database.close().await()
        open -= database
        val target = folder.resolve("copy.db")
        @Suppress("UNCHECKED_CAST")
        val done = (handle.apply(arrayOf(FamilyBackups.SNAPSHOT, target.toString())) as CompletableFuture<Any?>).get(10, TimeUnit.SECONDS)
        assertTrue(done is String, "a reason, not a copy: $done")
        assertFalse(Files.exists(target))
    }

    @Test
    fun anythingElseIsUnsupported() {
        val board = board()
        DbFixtures.sqlite(file).also { open += it }
        val handle = handle(entries(board).single())
        assertEquals(FamilyBackups.UNSUPPORTED, handle.apply("pause"))
        assertEquals(FamilyBackups.UNSUPPORTED, handle.apply(arrayOf("pause", "x")))
        assertEquals(FamilyBackups.UNSUPPORTED, handle.apply(arrayOf(FamilyBackups.SNAPSHOT)))
    }

    @Test
    fun mysqlSaysWhereItIsAndHasNoHandle() {
        val board = board()
        val mysql = JdbcDatabase(Dialect.MySql, "p_", "MySQL at db.example.com:3306/mc", { throw java.sql.SQLException("unused") },
            RecordingLog(), JdbcLimits(), "xMoney", "db.example.com:3306/mc").start()
        open += mysql
        val entry = entries(board).single()
        assertEquals(FamilyBackups.MYSQL, entry[FamilyBackups.KIND])
        assertEquals("db.example.com:3306/mc", entry[FamilyBackups.WHERE])
        assertNull(entry[FamilyBackups.HANDLE])
    }

    @Test
    fun twoPluginsOnTheFamilysFileEachHaveAnEntry() {
        val board = board()
        val shared = folder.resolve("Xena/xena.db")
        val first = DbFixtures.sqlite(shared, prefix = "a_").also { open += it }
        val second = DbFixtures.sqlite(shared, prefix = "b_").also { open += it }
        assertEquals(1, entries(board).size, "the same plugin name and file is one entry, the latest")
        first.close().await()
        open -= first
        // The first's close mustn't take the second's entry: it's only removed if it's still its own.
        assertEquals(1, entries(board).size)
        second.close().await()
        open -= second
        assertTrue(entries(board).isEmpty())
    }

    // ---- older SQLite: the online backup, and the locked copy ----

    /** A WAL database with [count] rows still in its log, and the connection holding them there. */
    private fun withLog(file: Path, count: Int): Connection {
        Files.createDirectories(file.parent)
        val c = sqlite(file)
        c.createStatement().use {
            it.execute("PRAGMA journal_mode = WAL")
            it.execute("PRAGMA wal_autocheckpoint = 0")
            it.execute("CREATE TABLE t_ledger (n INTEGER)")
        }
        c.prepareStatement("INSERT INTO t_ledger (n) VALUES (?)").use { s ->
            repeat(count) {
                s.setInt(1, it)
                s.executeUpdate()
            }
        }
        assertTrue(Files.size(file.resolveSibling(file.fileName.toString() + "-wal")) > 0, "the rows are in the log")
        return c
    }

    @Test
    fun theDriversOnlineBackup() {
        val source = folder.resolve("old/online.db")
        withLog(source, 500).use { c ->
            val target = folder.resolve("old/online-copy.db")
            assertTrue(DatabaseSnapshot.onlineBackup(c, target))
            assertEquals(500, rows(target))
            assertTrue(intact(target))
        }
    }

    @Test
    fun theLockedCopyFoldsTheLogIn() {
        val source = folder.resolve("old/locked.db")
        withLog(source, 500).use { c ->
            val target = folder.resolve("old/locked-copy.db")
            DatabaseSnapshot.lockedCopy(c, source, target)
            assertFalse(Files.exists(target.resolveSibling("locked-copy.db-wal")), "one file")
            assertEquals(500, rows(target))
            assertTrue(intact(target))
            // The source's connection still works: the lock went.
            c.createStatement().use { it.executeUpdate("INSERT INTO t_ledger (n) VALUES (1)") }
        }
    }

    @Test
    fun anOldSqliteGetsACopyToo() {
        val source = folder.resolve("old/version.db")
        withLog(source, 100).use { c ->
            val target = folder.resolve("old/version-copy.db")
            DatabaseSnapshot.copy(c, SqlVersion(3, 7, 2), source, target).ok()
            assertEquals(100, rows(target))
        }
    }
}
