package co.xenastudios.xbackup.store

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The read and upload limits (1.x's ThrottleTest). */
class ThrottleTest {
    @Test
    fun aLongIdleRefillsOneSecondAndNeverOverflows() {
        val rate = 100_000_000L
        assertEquals(rate, Throttle.refill(0, 100_000_000_000L, rate))
        assertEquals(rate - 5_000, Throttle.refill(-5_000, Long.MAX_VALUE / 2, rate))
        assertEquals(rate / 2, Throttle.refill(0, 500_000_000L, rate))
        assertEquals(-10, Throttle.refill(-10, 0, rate))
        assertEquals(-10, Throttle.refill(-10, -1, rate))
        // A limit too big to multiply out.
        assertEquals(1L shl 40, Throttle.refill(0, 10_000_000_000L, 1L shl 40))
        assertEquals(1L shl 39, Throttle.refill(0, 500_000_000L, 1L shl 40))
    }

    @Test
    fun acquiringAfterAnIdleDoesNotStall() {
        val throttle = Throttle(1L shl 40)
        Thread.sleep(50)
        val start = System.nanoTime()
        throttle.acquire(1L shl 20)
        throttle.acquire(1L shl 20)
        assertTrue(System.nanoTime() - start < 1_000_000_000L)
    }
}
