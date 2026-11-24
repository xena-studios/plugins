package co.xenastudios.core.common.db

import co.xenastudios.core.testing.row
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

object Homes : Table("homes") {
    val owner = column("owner", ColumnType.UUID)
    val name = column("name", ColumnType.text(32))
    val x = column("x", ColumnType.DOUBLE)
    val note = column("note", ColumnType.text(100).nullable())

    init {
        primaryKey(owner, name)
        index(name)
    }
}

object Events : Table("events") {
    val id = rowId()
    val at = column("at", ColumnType.INSTANT)
}

class SqlTest {
    private val steve = Uuid.parse("8667ba71-b85a-4004-af54-457a9734eed7")
    private val sqlite = Sql(Dialect.Sqlite.CURRENT, "xt_")
    private val oldSqlite = Sql(Dialect.Sqlite(SqlVersion(3, 7, 2)), "xt_")
    private val mysql = Sql(Dialect.MySql, "xt_")

    private fun home() = values(Homes) {
        it[Homes.owner] = steve
        it[Homes.name] = "base"
        it[Homes.x] = 1.5
        it[Homes.note] = null
    }

    @Test
    fun tablesAreMadePerDialectWithThePrefix() {
        assertEquals(
            listOf(
                "CREATE TABLE IF NOT EXISTS xt_homes (owner BLOB NOT NULL, name TEXT NOT NULL, x REAL NOT NULL, note TEXT, PRIMARY KEY (owner, name))",
                "CREATE INDEX IF NOT EXISTS xt_homes_name ON xt_homes (name)",
            ),
            sqlite.createTable(Homes).map { it.sql },
        )
        assertEquals(
            listOf(
                "CREATE TABLE IF NOT EXISTS xt_homes (owner BINARY(16) NOT NULL, name VARCHAR(32) NOT NULL, x DOUBLE NOT NULL, " +
                    "note VARCHAR(100), PRIMARY KEY (owner, name)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",
                "CREATE INDEX xt_homes_name ON xt_homes (name)",
            ),
            mysql.createTable(Homes).map { it.sql },
        )
        assertEquals("CREATE TABLE IF NOT EXISTS xt_events (id INTEGER PRIMARY KEY, at INTEGER NOT NULL)", sqlite.createTable(Events).single().sql)
        assertEquals("CREATE TABLE IF NOT EXISTS xt_events (id BIGINT PRIMARY KEY AUTO_INCREMENT, at BIGINT NOT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",
            mysql.createTable(Events).single().sql)
    }

    @Test
    fun upsertsAreOneStatementWhereTheDatabaseHasThem() {
        val native = sqlite.upsert(home()).single()
        assertEquals("INSERT INTO xt_homes (owner, name, x, note) VALUES (?, ?, ?, ?) ON CONFLICT (owner, name) DO UPDATE SET x = excluded.x, note = excluded.note", native.sql)
        assertEquals(listOf(SqlValue.Bytes(steve.toByteArray()), SqlValue.Text("base"), SqlValue.Real(1.5), SqlValue.Null), native.args)
        assertEquals("INSERT INTO xt_homes (owner, name, x, note) VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE x = VALUES(x), note = VALUES(note)",
            mysql.upsert(home()).single().sql)
    }

    @Test
    fun upsertsOnSqliteBefore324AreAnUpdateThenAnInsertOrIgnore() {
        assertFalse((oldSqlite.dialect as Dialect.Sqlite).nativeUpsert)
        val (update, insert) = oldSqlite.upsert(home())
        assertEquals("UPDATE xt_homes SET x = ?, note = ? WHERE owner = ? AND name = ?", update.sql)
        assertEquals(listOf(SqlValue.Real(1.5), SqlValue.Null, SqlValue.Bytes(steve.toByteArray()), SqlValue.Text("base")), update.args)
        assertEquals("INSERT OR IGNORE INTO xt_homes (owner, name, x, note) VALUES (?, ?, ?, ?)", insert.sql)
    }

    @Test
    fun anUpsertOfOnlyTheKeyIsAnInsertIgnore() {
        val key = values(Homes) {
            it[Homes.owner] = steve
            it[Homes.name] = "base"
        }
        assertEquals("INSERT IGNORE INTO xt_homes (owner, name) VALUES (?, ?)", mysql.upsert(key).single().sql)
        assertFailsWith<IllegalArgumentException> { sqlite.upsert(values(Homes) { it[Homes.x] = 1.0 }) }
    }

    @Test
    fun selectsUpdatesAndDeletesBindTheirValuesInOrder() {
        val select = sqlite.select(Homes, (Homes.owner eq steve) and (Homes.x gt 0.0), listOf(Homes.name), listOf(Homes.name.ascending), 10)
        assertEquals("SELECT name FROM xt_homes WHERE (owner = ?) AND (x > ?) ORDER BY name ASC LIMIT 10", select.sql)
        assertEquals(listOf(SqlValue.Bytes(steve.toByteArray()), SqlValue.Real(0.0)), select.args)
        val update = sqlite.update(values(Homes) { it[Homes.x] = 2.0 }, Homes.name eq "base")
        assertEquals("UPDATE xt_homes SET x = ? WHERE name = ?", update.sql)
        assertEquals(listOf(SqlValue.Real(2.0), SqlValue.Text("base")), update.args)
        assertEquals("DELETE FROM xt_homes WHERE note IS NULL", sqlite.delete(Homes, Homes.note eq null).sql)
        assertEquals("SELECT owner, name, x, note FROM xt_homes WHERE 1=0", sqlite.select(Homes, Homes.name within emptyList()).sql)
        assertEquals("SELECT COUNT(*) FROM xt_homes WHERE 1=1", sqlite.count(Homes).sql)
        assertEquals("SELECT MAX(id) FROM xt_events", sqlite.raw("SELECT MAX(id) FROM {p}events").sql)
        assertEquals("ALTER TABLE xt_homes ADD COLUMN note VARCHAR(100)", mysql.addColumn(Homes.note).sql)
    }

    @Test
    fun prefixesAndNamesAreChecked() {
        assertTrue(Sql.validPrefix(""))
        assertTrue(Sql.validPrefix("xbans_"))
        assertFalse(Sql.validPrefix("x-bans"))
        assertFalse(Sql.validPrefix("xbans; DROP TABLE"))
        assertFailsWith<IllegalArgumentException> { Sql(Dialect.MySql, "Bad") }
        assertFailsWith<IllegalArgumentException> { object : Table("homes; --") {} }
        assertFailsWith<IllegalArgumentException> { Statement("SELECT ?", emptyList()) }
    }

    @Test
    fun columnTypesRoundTrip() {
        assertEquals(steve, ColumnType.UUID.decode(ColumnType.UUID.encode(steve)))
        val at = Instant.parse("2026-11-24T12:00:00.123Z")
        assertEquals(at, ColumnType.INSTANT.decode(ColumnType.INSTANT.encode(at)))
        assertEquals(true, ColumnType.BOOLEAN.decode(SqlValue.Integer(1)))
        assertEquals(42, ColumnType.INT.decode(SqlValue.Text("42")))
        assertEquals(3.0, ColumnType.DOUBLE.decode(SqlValue.Integer(3)))
        assertNull(ColumnType.text(5).nullable().decode(SqlValue.Null))
        assertFailsWith<CorruptValueException> { ColumnType.UUID.decode(SqlValue.Bytes(ByteArray(3))) }
        assertFailsWith<CorruptValueException> { ColumnType.text(5).decode(SqlValue.Null) }
        val level = ColumnType.text(8).map("level", { it.length }, { "x".repeat(it) })
        assertEquals(3, level.decode(level.encode(3)))
    }

    @Test
    fun rowsAreReadByColumn() {
        val row = row("name" to SqlValue.Text("base"), "x" to SqlValue.Real(2.0), "NOTE" to SqlValue.Null)
        assertEquals("base", row[Homes.name])
        assertEquals(2.0, row[Homes.x])
        assertNull(row[Homes.note])
        assertEquals(null, row.long(2))
    }

    @Test
    fun versionsParseAndCompare() {
        assertEquals(SqlVersion(3, 7, 2), SqlVersion.parse("3.7.2"))
        assertEquals(SqlVersion(3, 49, 1), SqlVersion.parse("3.49.1.0"))
        assertTrue(SqlVersion(3, 24, 0) > SqlVersion(3, 7, 17))
        assertNull(SqlVersion.parse("unknown"))
        assertTrue(Dialect.Sqlite(SqlVersion(3, 24, 0)).nativeUpsert)
    }
}
