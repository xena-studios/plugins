package co.xenastudios.xbackup.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ThrottleTest {

    @Test
    void aLongIdleRefillsOneSecondAndNeverOverflows() {
        long rate = 100_000_000L;
        // 100 s at 100 MB/s multiplied out is past Long.MAX_VALUE: it used to wrap negative and
        // stall the next caller for up to about 100 s.
        assertEquals(rate, Throttle.refill(0, 100_000_000_000L, rate));
        assertEquals(rate - 5_000, Throttle.refill(-5_000, Long.MAX_VALUE / 2, rate));
        assertEquals(rate / 2, Throttle.refill(0, 500_000_000L, rate));
        assertEquals(-10, Throttle.refill(-10, 0, rate));
        assertEquals(-10, Throttle.refill(-10, -1, rate));
    }

    @Test
    void acquiringAfterAnIdleDoesNotStall() throws Exception {
        Throttle throttle = new Throttle(1L << 40);
        long start = System.nanoTime();
        throttle.acquire(1 << 20);
        throttle.acquire(1 << 20);
        assertTrue(System.nanoTime() - start < 1_000_000_000L);
    }
}
