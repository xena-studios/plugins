package co.xenastudios.xstats

import co.xenastudios.core.api.onSettled
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.db.Database
import co.xenastudios.core.common.db.DatabaseSettings
import co.xenastudios.core.common.db.SqlValue
import co.xenastudios.core.common.family.StorageType
import co.xenastudios.core.jvm.db.Drivers
import co.xenastudios.core.jvm.db.JdbcDatabase
import co.xenastudios.xstats.store.Moving
import co.xenastudios.xstats.store.Q
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.uuid.Uuid

/**
 * `/xstats migrate`: every table moved, ids and all, meta's flags with them, saves paused for the
 * copy, then the plugin on the new database at once. From SQLite to a second SQLite file standing
 * in for MySQL, and to MariaDB when `XR_MYSQL` is set, as CI sets it.
 */
class MoveTest {
    private val folder: Path = Files.createTempDirectory("xstats-move")
    private val opened = ArrayList<Database>()
    private var harness: Harness? = null

    @AfterTest
    fun close() {
        harness?.stop()
        for (db in opened) db.close()
    }

    private fun sqlite(file: String): Database {
        val opened = JdbcDatabase.open(
            DatabaseSettings(DatabaseSettings.Type.SQLITE, "xstats_"), folder.resolve(file), Drivers.serverOnly(javaClass.classLoader),
            co.xenastudios.core.testing.RecordingLog(), "Test",
        )
        return (opened as? Outcome.Ok)?.value?.also { this.opened += it } ?: fail("couldn't open $file: $opened")
    }

    // A server with history: two players' totals, today's numbers, a board, and the import's flag.
    private fun played(target: (DatabaseSettings.Type) -> Database): Harness {
        val h = Harness(folder, target = target).start()
        harness = h
        h.join("Steve").counters["player_kills"] = 40
        h.join("Alex").counters["player_kills"] = 12
        assertTrue(h.save())
        h.player("Steve").counters["player_kills"] = 55
        assertTrue(h.save())
        h.plugin.onDatabase { q -> q.exec(q.insertIgnore + " INTO {p}meta (name, value) VALUES (?, '1')", "imported:main") }
        h.settle()
        return h
    }

    private fun Harness.player(name: String) = server.player(name)

    private fun <T> Harness.wait(pending: co.xenastudios.core.api.Pending<T>): T {
        val future = java.util.concurrent.CompletableFuture<T>()
        pending.onSettled { value, error -> if (error != null) future.completeExceptionally(error) else @Suppress("UNCHECKED_CAST") future.complete(value as T) }
        return platform.await(future)
    }

    /** Every row of every table xStats keeps, and meta's flags, as text. */
    private fun contents(db: Database, h: Harness): Map<String, List<String>> {
        val read = h.wait(db.read { session ->
            Q.run(session, db.sql) { q ->
                val all = LinkedHashMap<String, List<String>>()
                for (table in Moving.TABLES) {
                    val order = table.primaryKey.joinToString(", ") { it.name }
                    all[table.tableName] = q.rows("SELECT * FROM {p}${table.tableName} ORDER BY $order").map { row -> row.values.joinToString("|") { text(it) } }
                }
                all["meta"] = q.rows("SELECT name, value FROM {p}meta WHERE name LIKE 'imported:%' ORDER BY name").map { row -> row.values.joinToString("|") { text(it) } }
                all
            }
        })
        return (read as? Outcome.Ok)?.value ?: fail("couldn't read ${db.description}: $read")
    }

    private fun text(value: SqlValue): String = when (value) {
        is SqlValue.Integer -> value.value.toString()
        is SqlValue.Real -> value.value.toString().removeSuffix(".0")
        is SqlValue.Text -> value.value
        SqlValue.Null -> "null"
        else -> value.toString()
    }

    private fun migrate(h: Harness, to: String) {
        h.run("xstats migrate $to")
        h.run("xstats migrate $to") { h.console.replies.any { "moved" in it.lowercase() || "failed" in it.lowercase() || "already" in it.lowercase() } }
    }

    @Test
    fun movesEveryRowThenUsesTheNewDatabase() {
        var made: Database? = null
        val h = played { type ->
            assertEquals(DatabaseSettings.Type.MYSQL, type)
            sqlite("target.db").also { made = it }
        }
        val old = h.plugin.database!!
        val before = contents(old, h)
        assertEquals(listOf("imported:main|1"), before["meta"])
        assertEquals(2, before.getValue("players").size)

        migrate(h, "mysql")
        assertTrue(h.console.replies.any { "moved" in it.lowercase() }, h.console.replies.toString())
        assertEquals(listOf(StorageType.MYSQL), h.storageWrites)
        val now = h.plugin.database!!
        assertNotSame(old, now)
        assertEquals(before, contents(now, h))
        assertTrue(made != null)

        // Saves run again, into the new database, and add up from what was moved.
        h.player("Steve").counters["player_kills"] = 60
        assertTrue(h.save())
        assertEquals(60L, h.plugin.value(FakeStatsServer.ref("Steve"), "kills", co.xenastudios.xstats.api.Period.ALL))
        val after = contents(now, h)
        assertTrue(after.getValue("totals").any { it.contains("|60|") }, after.toString())
    }

    @Test
    fun aSaveWaitsForTheMoveAndAFailedMoveLetsSavesRunAgain() {
        // The target already has stats: refused, nothing switched, and saves aren't left paused.
        val full = sqlite("full.db")
        val h = played { full }
        h.wait(full.session { s ->
            Q.run(s, full.sql) { q ->
                q.exec("CREATE TABLE IF NOT EXISTS {p}servers (id INTEGER PRIMARY KEY, name VARCHAR(64) NOT NULL UNIQUE)")
                q.exec("INSERT INTO {p}servers (name) VALUES ('other')")
            }
        })
        val old = h.plugin.database
        migrate(h, "mysql")
        assertTrue(h.console.replies.any { "already has data" in it }, h.console.replies.toString())
        assertEquals(emptyList(), h.storageWrites)
        assertTrue(old === h.plugin.database)
        assertTrue(h.save(), "saves run again after a refused move")
    }

    @Test
    fun networkModeIsRefusedAndTheSameTypeIsNoMove() {
        val h = played { fail("no target should be opened") }
        migrate(h, "sqlite")
        assertTrue(h.console.replies.any { "already" in it.lowercase() }, h.console.replies.toString())
        h.reload("network:\n  enabled: true\n")
        h.console.clear()
        migrate(h, "mysql")
        assertTrue(h.console.replies.any { "network" in it.lowercase() }, h.console.replies.toString())
        assertEquals(emptyList(), h.storageWrites)
        assertFalse(h.plugin.saving)
    }

    @Test
    fun toMariaDb() {
        val prefix = "s" + Uuid.random().toHexString().take(8) + "_"
        // Only where XR_MYSQL names a MariaDB (CI's service); passes without one.
        val env = System.getenv("XR_MYSQL")?.takeIf { it.isNotBlank() } ?: return
        val (host, port, database, user, secret) = env.split(':', limit = 5)
        val settings = DatabaseSettings(DatabaseSettings.Type.MYSQL, prefix, DatabaseSettings.MySql(host, port.toInt(), database, user, secret))
        var mysql: JdbcDatabase? = null
        val h = played { _ ->
            val opened = JdbcDatabase.open(settings, folder.resolve("unused.db"), Drivers.serverOnly(javaClass.classLoader), co.xenastudios.core.testing.RecordingLog(), "Test")
            ((opened as Outcome.Ok).value).also { mysql = it; this.opened += it }
        }
        try {
            val before = contents(h.plugin.database!!, h)
            migrate(h, "mysql")
            assertTrue(h.console.replies.any { "moved" in it.lowercase() }, h.console.replies.toString())
            assertEquals(before, contents(h.plugin.database!!, h))
            h.player("Alex").counters["player_kills"] = 20
            assertTrue(h.save())
        } finally {
            harness?.stop()
            harness = null
            mysql?.let { db ->
                h.wait(db.session { session ->
                    Q.run(session, db.sql) { q ->
                        for (row in q.rows("SHOW TABLES LIKE '$prefix%'")) q.exec("DROP TABLE " + (row.values[0] as SqlValue.Text).value)
                    }
                })
            }
        }
    }
}
