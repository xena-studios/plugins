package co.xenastudios.core.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class DurationsTest {

    @Test
    void readsUnitsInAnyMix() {
        assertEquals(Duration.ofMinutes(30), Durations.parse("30m"));
        assertEquals(Duration.ofDays(17).plusHours(4), Durations.parse("2w3d4h"));
        assertEquals(Duration.ofMinutes(90), Durations.parse("1.5h"));
        assertEquals(Duration.ofSeconds(70), Durations.parse("1m10s"));
        assertEquals(Duration.ofHours(6), Durations.parse(" 6H "));
    }

    @Test
    void refusesWhatIsntATime() {
        for (String bad : new String[] {"", "30", "m", "3x", "1.2.3h", "h5", "999999w"}) {
            assertThrows(IllegalArgumentException.class, () -> Durations.parse(bad), bad);
        }
    }

    @Test
    void formatsTheTwoLargestUnits() {
        assertEquals("2w 3d", Durations.format(Duration.ofDays(17).plusHours(4)));
        assertEquals("4h 30m", Durations.format(Duration.ofMinutes(270)));
        assertEquals("12s", Durations.format(Duration.ofSeconds(12)));
        assertEquals("1h", Durations.format(Duration.ofSeconds(3_605)), "a gap ends it: not 1h 5s");
        assertEquals("0s", Durations.format(Duration.ZERO));
    }
}
