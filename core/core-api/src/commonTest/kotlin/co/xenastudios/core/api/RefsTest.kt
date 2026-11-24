package co.xenastudios.core.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class RefsTest {

    private val notch = Uuid.parse("069a79f4-44e9-4726-a5be-fca90e38aaf5")

    @Test
    fun playersAreEqualByUuid() {
        assertEquals(PlayerRef.of(notch), PlayerRef.of(Uuid.parse(notch.toString())))
        assertEquals(PlayerRef.of(notch).hashCode(), PlayerRef.of(notch).hashCode())
        assertNotEquals(PlayerRef.of(notch), PlayerRef.of(Uuid.NIL))
        assertEquals("069a79f4-44e9-4726-a5be-fca90e38aaf5", PlayerRef.of(notch).toString())
    }

    @Test
    fun parsesAUuidWithOrWithoutDashes() {
        assertEquals(PlayerRef.of(notch), PlayerRef.parseOrNull("069a79f4-44e9-4726-a5be-fca90e38aaf5"))
        assertEquals(PlayerRef.of(notch), PlayerRef.parseOrNull("069a79f444e94726a5befca90e38aaf5"))
        assertNull(PlayerRef.parseOrNull("Notch"))
        assertNull(PlayerRef.parseOrNull(""))
    }

    @Test
    fun worldsAreEqualByNameAndNeverBlank() {
        assertEquals(WorldRef.of("world"), WorldRef.of("world"))
        assertNotEquals(WorldRef.of("world"), WorldRef.of("world_nether"))
        assertEquals("minecraft:overworld", WorldRef.of("minecraft:overworld").toString())
        assertFailsWith<IllegalArgumentException> { WorldRef.of(" ") }
    }

    @Test
    fun locationsKnowTheirBlockAndChunk() {
        val world = WorldRef.of("world")
        val here = Location(world, 17.5, 64.0, -0.5)
        assertEquals(17, here.blockX)
        assertEquals(64, here.blockY)
        assertEquals(-1, here.blockZ)
        assertEquals(1, here.chunkX)
        assertEquals(-1, here.chunkZ)
        assertEquals(-2, Location(world, -16.01, 0.0, -32.0).chunkX)
        assertEquals(-2, Location(world, -16.01, 0.0, -32.0).chunkZ)
    }

    @Test
    fun locationsCompareEveryPart() {
        val world = WorldRef.of("world")
        val here = Location(world, 1.0, 2.0, 3.0)
        assertEquals(here, Location(world, 1.0, 2.0, 3.0, 0f, 0f))
        assertEquals(here.hashCode(), Location(world, 1.0, 2.0, 3.0).hashCode())
        assertNotEquals(here, here.facing(90f, 0f))
        assertEquals(90f, here.facing(90f, 10f).yaw)
        assertEquals(10f, here.facing(90f, 10f).pitch)
        assertNotEquals(here, Location(WorldRef.of("other"), 1.0, 2.0, 3.0))
    }

    @Test
    fun distanceIsSquaredAndInfiniteAcrossWorlds() {
        val world = WorldRef.of("world")
        assertEquals(25.0, Location(world, 0.0, 0.0, 0.0).distanceSquared(Location(world, 3.0, 4.0, 0.0)))
        assertEquals(
            Double.POSITIVE_INFINITY,
            Location(world, 0.0, 0.0, 0.0).distanceSquared(Location(WorldRef.of("other"), 0.0, 0.0, 0.0)),
        )
    }
}
