package co.xenastudios.xlimits.api

import co.xenastudios.core.api.Location
import co.xenastudios.core.api.WorldRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ChunkRefTest {
    private val world = WorldRef.of("world")

    @Test
    fun aLocationIsInTheChunkItsBlocksAre() {
        assertEquals(ChunkRef(world, 0, 0), ChunkRef.of(Location(world, 15.9, 64.0, 0.0)))
        assertEquals(ChunkRef(world, -1, -1), ChunkRef.of(Location(world, -0.1, 64.0, -16.0)))
        assertEquals(ChunkRef(world, 1, -2), ChunkRef.of(ChunkRef(world, 1, -2).center()))
        assertFalse(ChunkRef(world, 0, 0) == ChunkRef(WorldRef.of("world_nether"), 0, 0))
    }

    @Test
    fun theApiIsThereOnlyWhileXLimitsRuns() {
        XLimits.register(null)
        assertFalse(XLimits.isEnabled())
        assertFailsWith<IllegalStateException> { XLimits.api() }
    }
}
