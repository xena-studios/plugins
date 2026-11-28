package co.xenastudios.xrewind

import co.xenastudios.core.common.Outcome
import co.xenastudios.xrewind.api.Action
import co.xenastudios.xrewind.log.Dictionaries
import co.xenastudios.xrewind.log.StoredTime
import co.xenastudios.xrewind.query.Params
import co.xenastudios.xrewind.query.Search
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/** CoreProtect's parameters, read as 1.x read them, on the JVM and Wasm. */
class ParamsTest {
    private val known: (String) -> String? = { name -> if (name == "nothing") null else if (':' in name) name else "minecraft:$name" }

    private fun ok(vararg args: String): Search = (Params.parse(args.toList(), known) as Outcome.Ok).value

    private fun error(vararg args: String): String = (Params.parse(args.toList(), known) as Outcome.Err).error.message.key

    @Test
    fun everyParameter() {
        val s = ok("u:Steve,#TNT", "t:2h30m", "r:20", "a:-block,+container", "i:oak_log", "e:dirt,Alex", "w:world_nether", "#preview", "#silent")
        assertEquals(listOf("steve", "#tnt"), s.users)
        assertEquals(9000, s.seconds)
        assertEquals(20, s.radius)
        assertEquals(setOf(Action.BREAK, Action.ADD), s.actions)
        assertEquals(listOf("minecraft:oak_log"), s.blocks)
        assertEquals(listOf("minecraft:dirt", "minecraft:alex"), s.excludedBlocks)
        assertEquals("world_nether", s.world)
        assertTrue(s.preview && s.silent)
        assertEquals(Search.GLOBAL, ok("r:#global").radius)
        assertEquals(Search.WORLD, ok("r:#WORLD").radius)
        assertEquals(listOf("#creeper"), ok("e:#creeper").excludedUsers)
        assertEquals(Search.DEFAULT, ok().covered)
    }

    @Test
    fun whatsWrongIsSaid() {
        assertEquals("params.not-a-flag", error("#nope"))
        assertEquals("params.not-a-parameter", error("steve"))
        assertEquals("params.needs-value", error("u:"))
        assertEquals("params.unknown-key", error("x:1"))
        assertEquals("params.bad-radius", error("r:-4"))
        assertEquals("params.bad-action", error("a:fly"))
        assertEquals("params.bad-block", error("i:nothing"))
        assertEquals("params.bad-time", error("t:soon"))
    }

    @Test
    fun timesAreSecondsSince2026AsIn1x() {
        assertEquals(0, StoredTime.of(Instant.parse("2026-01-01T00:00:00Z")))
        assertEquals(Instant.parse("2026-06-01T00:00:00Z"), StoredTime.toInstant(13_046_400))
        assertEquals(Int.MIN_VALUE, StoredTime.ago(0, Long.MAX_VALUE / 2))
        // 1.x's FNV-1a, so 2.0 finds the items 1.x stored.
        assertEquals(-0x340d631b7bdddcdbL, Dictionaries.hash(ByteArray(0)))
    }
}
