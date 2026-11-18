package co.xenastudios.xbans.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Lengths as LiteBans staff type them. */
class LengthTest {

    private static final long MINUTE = 60_000L;
    private static final long HOUR = 60 * MINUTE;
    private static final long DAY = 24 * HOUR;

    @Test
    void readsLiteBansUnits() {
        assertEquals(30 * MINUTE, Length.parse("30m").millis());
        assertEquals(DAY + 2 * HOUR, Length.parse("1d2h").millis());
        assertEquals(90 * MINUTE, Length.parse("1.5h").millis());
        assertEquals(2 * 30 * DAY, Length.parse("2mo").millis());
        assertEquals(365 * DAY, Length.parse("1y").millis());
        assertEquals(14 * DAY, Length.parse("2w").millis());
        assertEquals(5 * DAY, Length.parse("5days").millis());
        assertEquals(10 * 1000L, Length.parse("10s").millis());
        assertEquals(DAY, Length.parse("duration:1d").millis());
    }

    @Test
    void permanentHasManyNames() {
        for (String word : new String[] {"perm", "permanent", "forever", "PERM", "duration:permanent", "-1"}) {
            assertTrue(Length.parse(word).permanent(), word);
        }
    }

    @Test
    void wordsThatArentLengthsStartTheReason() {
        assertNull(Length.parse("hacking"));
        assertNull(Length.parse("10x"));
        assertNull(Length.parse("1d2"));
        assertNull(Length.parse(""));
        assertNull(Length.parse("0s"));
    }

    @Test
    void moreThanAHundredYearsIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> Length.parse("101y"));
    }

    @Test
    void permanentIsLongest() {
        assertTrue(Length.PERMANENT.compareTo(Length.parse("100y")) > 0);
        assertTrue(Length.parse("1d").compareTo(Length.parse("2h")) > 0);
        assertEquals(0, Length.PERMANENT.compareTo(Length.PERMANENT));
    }

    @Test
    void writesBackAsTyped() {
        assertEquals("10d", Length.parse("10d").typed());
        assertEquals("30d", Length.parse("30d").typed());
        assertEquals("1y", Length.parse("365d").typed());
        assertEquals("2w", Length.parse("14d").typed());
        assertEquals("1d12h", Length.parse("1d12h").typed());
        assertEquals("1d30m", Length.parse("1d30m").typed());
        assertEquals("2h", Length.parse("120m").typed());
        assertEquals("1mo 3d", Length.parse("33d").shown());
        assertEquals("1d 12h", Length.parse("36h").shown());
        assertEquals("permanent", Length.PERMANENT.typed());
        assertEquals(Length.parse("1d12h"), Length.CODEC.decode(Length.CODEC.encode(Length.parse("1d12h"))));
    }
}
