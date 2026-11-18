package co.xenastudios.xteleport.teleport;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class CooldownsTest {

    final AtomicLong now = new AtomicLong(5_000);
    final Cooldowns cooldowns = new Cooldowns(now::get);
    final UUID steve = UUID.randomUUID();

    @Test
    void countsDownPerKey() {
        cooldowns.start(steve, "home", 30);
        now.addAndGet(10_000);
        assertEquals(Duration.ofSeconds(20), cooldowns.remaining(steve, "home"));
        assertEquals(Duration.ZERO, cooldowns.remaining(steve, "warp"));
        now.addAndGet(20_000);
        assertEquals(Duration.ZERO, cooldowns.remaining(steve, "home"));
    }

    @Test
    void nothingForZeroOrNoKey() {
        cooldowns.start(steve, "home", 0);
        cooldowns.start(steve, "", 30);
        assertEquals(0, cooldowns.players());
    }

    @Test
    void pruneDropsOnlyWhatRanOut() {
        cooldowns.start(steve, "home", 10);
        cooldowns.start(UUID.randomUUID(), "rtp", 300);
        now.addAndGet(11_000);
        cooldowns.prune();
        assertEquals(1, cooldowns.players());
    }
}
