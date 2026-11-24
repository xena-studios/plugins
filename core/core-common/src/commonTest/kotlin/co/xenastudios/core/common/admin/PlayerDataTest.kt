package co.xenastudios.core.common.admin

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.pendingOf
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.db.ColumnType
import co.xenastudios.core.common.db.FakeDatabase
import co.xenastudios.core.common.db.Table
import co.xenastudios.core.common.db.settled
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid

class PlayerDataTest {

    private object Homes : Table("homes") {
        val owner = column("owner", ColumnType.UUID)
        val name = column("name", ColumnType.text(32))

        init {
            primaryKey(owner, name)
        }
    }

    private val notch = PlayerRef.of(Uuid.parse("069a79f4-44e9-4726-a5be-fca90e38aaf5"))
    private val database = FakeDatabase()
    private val registry = PlayerDataRegistry { name -> pendingOf(if (name.equals("notch", ignoreCase = true)) notch else null) }

    @Test
    fun everythingRegisteredIsDeletedAndOneFailureDoesNotStopTheRest() {
        val cache = mutableMapOf(notch to "Notch")
        registry.table("homes", { database }, Homes.owner)
        registry.register("cache") { player -> pendingOf(Outcome.Ok(if (cache.remove(player) != null) 1L else 0L)) }
        registry.register("files") { error("disk on fire") }
        assertEquals(listOf("homes", "cache", "files"), registry.names)

        val report = registry.forget(notch).settled()
        assertEquals(2L, report.records)
        assertEquals(mapOf("files" to "it threw IllegalStateException: disk on fire"), report.failed)
        assertEquals(emptyMap(), cache)
        assertEquals(listOf("BEGIN", "DELETE FROM test_homes WHERE owner = ? [Bytes(16)]", "COMMIT"), database.session.log)
        assertFailsWith<IllegalArgumentException> { registry.register("cache") { pendingOf(Outcome.Ok(0L)) } }
    }

    @Test
    fun aPlayerIsANameOrAUuid() {
        assertEquals(notch, registry.resolve("NOTCH").settled())
        assertEquals(null, registry.resolve("Herobrine").settled())
        assertEquals(notch, registry.resolve("069a79f4-44e9-4726-a5be-fca90e38aaf5").settled())
        assertEquals(notch, registry.resolve("069a79f444e94726a5befca90e38aaf5").settled())
    }
}
