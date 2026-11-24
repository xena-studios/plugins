package co.xenastudios.m0

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.TimeSource

class ClockTest {
    @Test fun monotonicClockWorks() {
        val mark = TimeSource.Monotonic.markNow()
        assertTrue(mark.elapsedNow().isFinite())
    }

    @Test fun wallClockWorks() {
        assertTrue(Clock.System.now().toEpochMilliseconds() > 0)
    }
}
