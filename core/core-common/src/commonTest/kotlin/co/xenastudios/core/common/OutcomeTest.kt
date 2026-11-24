package co.xenastudios.core.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OutcomeTest {

    private fun half(n: Int): Outcome<Int, String> = if (n % 2 == 0) Outcome.ok(n / 2) else Outcome.err("$n is odd")

    @Test
    fun okHoldsAValue() {
        val outcome = half(8)
        assertTrue(outcome.isOk)
        assertFalse(outcome.isErr)
        assertEquals(4, outcome.getOrNull())
        assertNull(outcome.errorOrNull())
        assertEquals(Outcome.Ok(4), outcome)
    }

    @Test
    fun errHoldsAnError() {
        val outcome = half(3)
        assertTrue(outcome.isErr)
        assertNull(outcome.getOrNull())
        assertEquals("3 is odd", outcome.errorOrNull())
        assertEquals(-1, outcome.getOrElse { -1 })
    }

    @Test
    fun mapsTheValueOrTheError() {
        assertEquals(Outcome.ok("4"), half(8).map { it.toString() })
        assertEquals(Outcome.err("3 is odd"), half(3).map { it.toString() })
        assertEquals(Outcome.err(8), half(3).mapError { it.length })
        assertEquals(Outcome.ok(4), half(8).mapError { it.length })
    }

    @Test
    fun chainsStepsUntilOneFails() {
        assertEquals(Outcome.ok(2), half(8).andThen(::half))
        assertEquals(Outcome.err("3 is odd"), half(12).andThen(::half).andThen(::half))
        assertEquals(Outcome.err("5 is odd"), half(5).andThen(::half))
    }

    @Test
    fun foldsAndRunsSideEffectsOnOneSide() {
        assertEquals("ok 4", half(8).fold({ "ok $it" }, { "err $it" }))
        assertEquals("err 3 is odd", half(3).fold({ "ok $it" }, { "err $it" }))
        val seen = mutableListOf<String>()
        half(8).onOk { seen += "ok $it" }.onErr { seen += "err $it" }
        half(3).onOk { seen += "ok $it" }.onErr { seen += "err $it" }
        assertEquals(listOf("ok 4", "err 3 is odd"), seen)
    }

    @Test
    fun smartCastsInAWhen() {
        val text = when (val outcome = half(6)) {
            is Outcome.Ok -> "value ${outcome.value + 1}"
            is Outcome.Err -> "error ${outcome.error.length}"
        }
        assertEquals("value 4", text)
    }
}
