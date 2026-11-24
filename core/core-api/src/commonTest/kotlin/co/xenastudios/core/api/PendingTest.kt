package co.xenastudios.core.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PendingTest {

    @Test
    fun settlesOnceWithAValue() {
        val pending = newPending<Int>()
        val seen = mutableListOf<Pair<Int?, Throwable?>>()
        pending.onSettled { value, error -> seen += value to error }
        assertFalse(pending.isSettled)
        assertTrue(seen.isEmpty())
        assertTrue(pending.resolve(4))
        assertFalse(pending.resolve(5))
        assertFalse(pending.reject(IllegalStateException()))
        assertTrue(pending.isSettled)
        assertEquals(listOf<Pair<Int?, Throwable?>>(4 to null), seen)
    }

    @Test
    fun callsBackRightAwayOnceSettled() {
        var seen: Int? = null
        pendingOf(7).onSettled { value, _ -> seen = value }
        assertEquals(7, seen)
    }

    @Test
    fun carriesAFailure() {
        val boom = IllegalStateException("boom")
        var value: String? = "unset"
        var error: Throwable? = null
        pendingFailure<String>(boom).onSettled { v, e ->
            value = v
            error = e
        }
        assertNull(value)
        assertSame(boom, error)
    }

    @Test
    fun mapsValuesAndPassesFailuresOn() {
        val pending = newPending<Int>()
        val mapped = pending.map { it * 2 }
        var seen: Int? = null
        mapped.onSettled { value, _ -> seen = value }
        pending.resolve(21)
        assertEquals(42, seen)

        val boom = IllegalStateException("boom")
        var error: Throwable? = null
        pendingFailure<Int>(boom).map { it * 2 }.onSettled { _, e -> error = e }
        assertSame(boom, error)

        var thrown: Throwable? = null
        pendingOf(1).map<Int, Int> { throw IllegalArgumentException("bad") }.onSettled { _, e -> thrown = e }
        assertEquals("bad", thrown?.message)
    }
}
