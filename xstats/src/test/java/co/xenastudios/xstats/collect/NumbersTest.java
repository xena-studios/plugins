package co.xenastudios.xstats.collect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class NumbersTest {

    @Test
    void placeholderTextBecomesWholeNumbers() {
        assertEquals(123_456L, Numbers.parse("1,234.56", 2));
        assertEquals(1235L, Numbers.parse("1,234.56", 0));
        assertEquals(12_500L, Numbers.parse("$12.5k", 0));
        assertEquals(2_000_000L, Numbers.parse("2M coins", 0));
        assertEquals(42L, Numbers.parse("§a42", 0));
        assertEquals(-5L, Numbers.parse("-5", 0));
        assertEquals(7L, Numbers.parse("Level 7", 0));
        assertNull(Numbers.parse("none", 0));
        assertNull(Numbers.parse("", 0));
        assertNull(Numbers.parse(null, 0));
        assertNull(Numbers.parse("99999999999999999999999", 0));
    }
}
