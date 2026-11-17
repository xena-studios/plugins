package co.xenastudios.xlimbo.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReturnHistoryTest {

    private final UUID player = UUID.randomUUID();

    @Test
    void threeTriesInFiveMinutesIsTheLimit() {
        ReturnHistory history = new ReturnHistory();
        history.tried(player, 0);
        history.tried(player, 60_000);
        assertFalse(history.triedTooOften(player, 120_000));
        history.tried(player, 120_000);
        assertTrue(history.triedTooOften(player, 130_000));
        assertFalse(history.triedTooOften(player, 300_001 + 60_000), "the oldest two have aged out");
    }

    @Test
    void triesOutliveLeavingButArrivalsDont() {
        ReturnHistory history = new ReturnHistory();
        history.arrived(player, 0);
        history.tried(player, 1_000);
        history.left(player);
        assertTrue(history.recentlySent(player, 2_000), "being sent back is leaving");
        assertEquals(0, history.here(player, 5_000), "a new arrival isn't known yet");
        history.arrivedIfUnknown(player, 5_000);
        history.arrivedIfUnknown(player, 9_000);
        assertEquals(5_000, history.here(player, 10_000), "an arrival already known is kept");
    }

    @Test
    void pruningDropsOnlyOldTries() {
        ReturnHistory history = new ReturnHistory();
        history.tried(player, 0);
        history.tried(player, 200_000);
        history.prune(400_000);
        assertTrue(history.recentlySent(player, 200_001));
        history.prune(600_000);
        assertFalse(history.recentlySent(player, 600_000));
        assertFalse(history.triedTooOften(player, 600_000));
    }
}
