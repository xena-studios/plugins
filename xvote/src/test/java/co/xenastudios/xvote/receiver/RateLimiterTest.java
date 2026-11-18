package co.xenastudios.xvote.receiver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;

/** Connections per address per minute, and what counts as one address. */
class RateLimiterTest {

    @Test
    void ipv6CountsByItsSlash64() throws Exception {
        String a = RateLimiter.key(InetAddress.getByName("2001:db8:1:2::1"));
        String b = RateLimiter.key(InetAddress.getByName("2001:db8:1:2:ffff:ffff:ffff:ffff"));
        String other = RateLimiter.key(InetAddress.getByName("2001:db8:1:3::1"));
        assertEquals(a, b, "one machine's /64");
        assertNotEquals(a, other);
        assertEquals("203.0.113.7", RateLimiter.key(InetAddress.getByName("203.0.113.7")));
    }

    @Test
    void limitsPerMinuteAndStartsAgain() {
        RateLimiter limiter = new RateLimiter(2);
        assertTrue(limiter.allow("a", 0));
        assertTrue(limiter.allow("a", 1));
        assertFalse(limiter.allow("a", 2));
        assertTrue(limiter.allow("b", 2), "another address has its own count");
        assertTrue(limiter.allow("a", RateLimiter.WINDOW_MS + 1), "a new minute");
    }

    @Test
    void zeroIsNoLimit() {
        RateLimiter limiter = new RateLimiter(0);
        for (int i = 0; i < 1000; i++) {
            assertTrue(limiter.allow("a", i));
        }
        assertEquals(0, limiter.size(), "nothing is even counted");
    }

    @Test
    void oldAddressesAreSweptAtMostOnceASecond() {
        long minute = RateLimiter.WINDOW_MS;
        RateLimiter limiter = new RateLimiter(10);
        for (int i = 0; i <= RateLimiter.SWEEP_AT; i++) {
            limiter.allow("old" + i, 0);
        }
        // A minute on, the next connection sweeps the old ones out.
        limiter.allow("new", minute);
        assertEquals(1, limiter.size());
        // As many more old ones again: within a second of that sweep, they stay...
        for (int i = 0; i <= RateLimiter.SWEEP_AT; i++) {
            limiter.allow("old" + i, 0);
        }
        limiter.allow("soon", minute + 500);
        assertEquals(RateLimiter.SWEEP_AT + 3, limiter.size(), "no sweep within a second");
        // ...and a second after it, they go.
        limiter.allow("later", minute + 1_000);
        assertEquals(3, limiter.size());
    }
}
