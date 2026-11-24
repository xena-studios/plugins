package co.xenastudios.core.common.storage

import co.xenastudios.core.api.isSettled
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.db.ColumnType
import co.xenastudios.core.common.db.DbError
import co.xenastudios.core.common.db.FakeDatabase
import co.xenastudios.core.common.db.Row
import co.xenastudios.core.common.db.SqlRecordStore
import co.xenastudios.core.common.db.Table
import co.xenastudios.core.common.db.Values
import co.xenastudios.core.common.db.ok
import co.xenastudios.core.common.db.settled
import co.xenastudios.core.common.platform.StorageError
import co.xenastudios.core.common.platform.StoragePath
import co.xenastudios.core.common.platform.readText
import co.xenastudios.core.common.platform.writeText
import co.xenastudios.core.testing.FakePlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

data class Warp(val name: String, val owner: Uuid, val x: Double, val note: String?, val icon: ByteArray?)

object Warps : Table("warps") {
    val name = column("name", ColumnType.text(32))
    val owner = column("owner", ColumnType.UUID)
    val x = column("x", ColumnType.DOUBLE)
    val note = column("note", ColumnType.text(200).nullable())
    val icon = column("icon", ColumnType.BYTES.nullable())

    init {
        primaryKey(name)
    }
}

object WarpRecords : RecordType<String, Warp>(Warps, Warps.name) {
    override fun keyOf(value: Warp) = value.name

    override fun write(value: Warp, values: Values) {
        values[Warps.name] = value.name
        values[Warps.owner] = value.owner
        values[Warps.x] = value.x
        values[Warps.note] = value.note
        values[Warps.icon] = value.icon
    }

    override fun read(row: Row) = Warp(row[Warps.name], row[Warps.owner], row[Warps.x], row[Warps.note], row[Warps.icon])
}

class RecordStoreTest {
    private val steve = Uuid.parse("8667ba71-b85a-4004-af54-457a9734eed7")
    private val path = StoragePath.of("data/warps.txt")

    private fun open(platform: FakePlatform) =
        FileRecordStore.open(platform.storage, platform.scheduler, path, WarpRecords, platform.log).ok()

    @Test
    fun aChangeIsAcknowledgedOnlyOnceTheFileHoldsIt() {
        val platform = FakePlatform()
        val store = open(platform)
        val saved = store.put(Warp("spawn", steve, 1.5, "tab\there\nand \\ a line", byteArrayOf(1, -2)))
        val also = store.put(Warp("mine", steve, -3.0, null, null))
        assertFalse(saved.isSettled)
        assertEquals(emptyList(), platform.storage.paths)
        // Read from memory at once, before it's written.
        assertEquals(-3.0, store.get("mine").settled().ok()!!.x)
        platform.scheduler.runPending()
        assertEquals(Outcome.Ok(Unit), saved.settled())
        assertEquals(Outcome.Ok(Unit), also.settled())
        assertEquals(listOf("data/warps.txt"), platform.storage.paths)

        val again = open(platform)
        val spawn = again.get("spawn").settled().ok()!!
        assertEquals("tab\there\nand \\ a line", spawn.note)
        assertTrue(byteArrayOf(1, -2).contentEquals(spawn.icon))
        assertEquals(setOf("spawn", "mine"), again.all().settled().ok().map { it.name }.toSet())
        again.remove("spawn")
        platform.scheduler.runPending()
        assertNull(open(platform).get("spawn").settled().ok())
    }

    @Test
    fun aFailedWriteIsReportedAndKeptForTheNext() {
        val platform = FakePlatform()
        val store = open(platform)
        platform.storage.failure = StorageError.Kind.IO
        val saved = store.put(Warp("spawn", steve, 1.0, null, null))
        platform.scheduler.runPending()
        assertEquals(DbError.Kind.UNAVAILABLE, (saved.settled() as Outcome.Err).error.kind)
        assertEquals(1, platform.log.lines.size)
        platform.storage.failure = null
        store.put(Warp("mine", steve, 2.0, null, null))
        platform.scheduler.runPending()
        assertEquals(2, open(platform).all().settled().ok().size)
    }

    @Test
    fun aColumnAddedLaterReadsAsNullFromAnOldFile() {
        val platform = FakePlatform()
        platform.storage.writeText(path, "xena-records 1\nname\towner\tx\ntspawn\tb${steve.toByteArray().toHexString()}\tr2.5\n")
        val warp = open(platform).get("spawn").settled().ok()!!
        assertEquals(Warp("spawn", steve, 2.5, null, null), warp)
    }

    @Test
    fun aDamagedFileIsAnErrorAndLeftAlone() {
        val platform = FakePlatform()
        for (text in listOf("hello", "xena-records 1\nname\tx\ntspawn\n", "xena-records 1\nname\ntbad\\q\n", "xena-records 1\nname\nzz\n")) {
            platform.storage.writeText(path, text)
            val result = FileRecordStore.open(platform.storage, platform.scheduler, path, WarpRecords, platform.log)
            assertEquals(DbError.Kind.CORRUPT, (result as Outcome.Err).error.kind, text)
            assertEquals(text, platform.storage.readText(path).ok())
        }
        // A row whose values can't be read as a warp fails when it's read, not before.
        platform.storage.writeText(path, "xena-records 1\nname\towner\tx\ntspawn\ti1\tr1\n")
        assertEquals(DbError.Kind.CORRUPT, (open(platform).get("spawn").settled() as Outcome.Err).error.kind)
    }

    @Test
    fun theSqlStoreUpsertsThroughTheWriterAndReadsByKey() {
        val database = FakeDatabase()
        val store = SqlRecordStore(database, WarpRecords)
        assertEquals(Outcome.Ok(Unit), store.put(Warp("spawn", steve, 1.0, null, null)).settled())
        store.get("spawn").settled()
        store.remove("spawn").settled()
        assertEquals(
            listOf(
                "BEGIN",
                "INSERT INTO test_warps (name, owner, x, note, icon) VALUES (?, ?, ?, ?, ?) ON CONFLICT (name) DO UPDATE SET owner = excluded.owner, x = excluded.x, note = excluded.note, icon = excluded.icon",
                "COMMIT",
                "SELECT name, owner, x, note, icon FROM test_warps WHERE name = ? LIMIT 1",
                "BEGIN", "DELETE FROM test_warps WHERE name = ?", "COMMIT",
            ),
            database.session.log.map { it.substringBefore(" [") },
        )
    }
}

private fun <T> Outcome<T, StorageError>.ok(): T = (this as Outcome.Ok).value
