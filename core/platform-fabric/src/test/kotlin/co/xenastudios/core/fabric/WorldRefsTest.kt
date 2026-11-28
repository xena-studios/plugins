package co.xenastudios.core.fabric

import co.xenastudios.core.api.WorldRef
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

// Level.ref's cache, keyed here by the id itself: a dimension's ResourceKey can't be made without
// Minecraft bootstrapped.
class WorldRefsTest {
    private val cache = ConcurrentHashMap<String, WorldRef>()
    private var made = 0

    private fun ref(id: String) = cachedWorldRef(cache, id) { made++; it }

    @Test
    fun `a dimension has one reference, made once`() {
        val first = ref("minecraft:the_nether")
        assertEquals(WorldRef.of("minecraft:the_nether"), first)
        assertSame(first, ref("minecraft:the_nether"))
        assertEquals(1, made)
    }

    @Test
    fun `dimensions made by the thousand aren't all kept`() {
        val first = ref("xena:arena-0")
        for (i in 1..1000) assertEquals("xena:arena-$i", ref("xena:arena-$i").name)
        assertTrue(cache.size <= MAX_WORLD_REFS)
        val again = ref("xena:arena-0")
        assertEquals(first, again)
        assertNotSame(first, again, "the cache was cleared on the way, not grown to a thousand")
    }
}
