package co.xenastudios.core.common.admin

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.pendingOf
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.db.ColumnType
import co.xenastudios.core.common.db.Database
import co.xenastudios.core.common.db.DatabaseSettings
import co.xenastudios.core.common.db.DbError
import co.xenastudios.core.common.db.Dialect
import co.xenastudios.core.common.db.FakeDatabase
import co.xenastudios.core.common.db.FakeSession
import co.xenastudios.core.common.db.Migration
import co.xenastudios.core.common.db.Row
import co.xenastudios.core.common.db.SqlValue
import co.xenastudios.core.common.db.Table
import co.xenastudios.core.testing.FakePlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** The migration's steps, on fake databases; `core-jvm`'s `StorageMigrationJdbcTest` moves real rows. */
class StorageMigrationTest {

    private object Coins : Table("coins") {
        val player = column("player", ColumnType.text(16))
        val amount = column("amount", ColumnType.INT)

        init {
            primaryKey(player)
        }
    }

    private val platform = FakePlatform()
    private val rows = listOf("alex" to 5, "steve" to 7, "notch" to 9)

    private fun count(n: Int) = Outcome.Ok(listOf(Row(listOf("count"), listOf(SqlValue.Integer(n.toLong())))))

    // The source answers counts and pages of rows as a database with [rows] would.
    private val source = FakeDatabase(FakeSession(Dialect.Sqlite.CURRENT)).also { db ->
        db.session.answer = { statement ->
            when {
                statement.sql.startsWith("SELECT COUNT(*)") -> count(rows.size)
                statement.sql.startsWith("SELECT player, amount") -> {
                    val limit = Regex("LIMIT (\\d+)").find(statement.sql)!!.groupValues[1].toInt()
                    val offset = Regex("OFFSET (\\d+)").find(statement.sql)?.groupValues?.get(1)?.toInt() ?: 0
                    Outcome.Ok(rows.drop(offset).take(limit).map { (name, amount) ->
                        Row(listOf("player", "amount"), listOf(SqlValue.Text(name), SqlValue.Integer(amount.toLong())))
                    })
                }
                else -> Outcome.Ok(emptyList())
            }
        }
    }
    private var targetRows = 0
    private val target = FakeDatabase(FakeSession(Dialect.MySql)).also { db ->
        db.session.answer = { statement ->
            when {
                statement.sql.startsWith("SELECT COUNT(*)") -> count(targetRows)
                statement.sql.startsWith("INSERT INTO test_coins") -> {
                    targetRows++
                    Outcome.Ok(emptyList())
                }
                statement.sql.startsWith("DELETE FROM test_coins") -> {
                    targetRows = 0
                    Outcome.Ok(emptyList())
                }
                else -> Outcome.Ok(emptyList())
            }
        }
    }
    private val switched = mutableListOf<DatabaseSettings.Type>()
    private val storage = object : StorageSwitch {
        override val currentType: DatabaseSettings.Type get() = DatabaseSettings.Type.SQLITE
        override val current: Database get() = source
        override fun open(type: DatabaseSettings.Type): Pending<Outcome<Database, DbError>> = pendingOf(Outcome.Ok(target))
        override fun switchTo(type: DatabaseSettings.Type): Pending<Outcome<Unit, String>> {
            switched += type
            return pendingOf(Outcome.Ok(Unit))
        }
    }
    private val migration = StorageMigration(platform, storage, listOf(Coins), listOf(Migration(1, "coins") { it.createTable(Coins) }), batch = 2)

    private class Heard : MigrationListener {
        val lines = mutableListOf<String>()
        override fun notEmpty(rows: Map<String, Long>) { lines += "not empty $rows" }
        override fun started() { lines += "started" }
        override fun copied(table: String, rows: Long) { lines += "copied $rows of $table" }
        override fun finished(rows: Long) { lines += "finished $rows" }
        override fun failed(reason: String) { lines += "failed: $reason" }
    }

    @Test
    fun copiesInBatchesThroughTheWriterChecksThenSwitches() {
        val heard = Heard()
        assertTrue(migration.run(DatabaseSettings.Type.MYSQL, force = false, heard))
        assertFalse(migration.run(DatabaseSettings.Type.MYSQL, force = false, Heard()), "one at a time")
        platform.scheduler.advance(1.seconds)
        assertEquals(listOf("started", "copied 3 of coins", "finished 3"), heard.lines)
        assertEquals(listOf(DatabaseSettings.Type.MYSQL), switched)
        // Two pages of two rows: each one transaction of inserts through the writer.
        val writes = target.session.log.joinToString("\n")
        assertTrue("CREATE TABLE IF NOT EXISTS test_coins (player VARCHAR(16) NOT NULL, amount INT NOT NULL, PRIMARY KEY (player)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4" in writes, writes)
        assertTrue(
            "BEGIN\nINSERT INTO test_coins (player, amount) VALUES (?, ?) [Text(value=alex), Integer(value=5)]\n" +
                "INSERT INTO test_coins (player, amount) VALUES (?, ?) [Text(value=steve), Integer(value=7)]\nCOMMIT\n" +
                "BEGIN\nINSERT INTO test_coins (player, amount) VALUES (?, ?) [Text(value=notch), Integer(value=9)]\nCOMMIT" in writes,
            writes,
        )
        assertTrue(source.session.log.none { it.startsWith("INSERT") || it.startsWith("DELETE") }, "the source is only read")
    }

    @Test
    fun aTargetWithDataIsRefusedUnlessForced() {
        targetRows = 4
        val refused = Heard()
        migration.run(DatabaseSettings.Type.MYSQL, force = false, refused)
        platform.scheduler.advance(1.seconds)
        assertEquals(listOf("not empty {coins=4}"), refused.lines)
        assertEquals(emptyList(), switched)

        val forced = Heard()
        migration.run(DatabaseSettings.Type.MYSQL, force = true, forced)
        platform.scheduler.advance(1.seconds)
        assertEquals(listOf("started", "copied 3 of coins", "finished 3"), forced.lines)
        assertTrue(target.session.log.any { it == "DELETE FROM test_coins" })
    }

    @Test
    fun countsThatDoNotMatchFailWithoutSwitching() {
        // Every insert lands twice, as if something else were writing there too.
        val answer = target.session.answer
        target.session.answer = { statement ->
            if (statement.sql.startsWith("INSERT INTO test_coins")) targetRows++
            answer(statement)
        }
        val heard = Heard()
        migration.run(DatabaseSettings.Type.MYSQL, force = false, heard)
        platform.scheduler.advance(1.seconds)
        assertEquals(
            "failed: the copy doesn't match (coins has 3 rows here and 6 there); something wrote during the copy, so run it again when the server is quiet",
            heard.lines.last(),
        )
        assertEquals(emptyList(), switched)
        assertFailsWith<IllegalArgumentException> { migration.run(DatabaseSettings.Type.SQLITE, force = false, Heard()) }
    }
}
