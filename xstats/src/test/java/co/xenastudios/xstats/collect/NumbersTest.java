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
        assertEquals(3_000_000_000L, Numbers.parse("3b", 0));
        assertEquals(42L, Numbers.parse("§a42", 0));
        assertEquals(-5L, Numbers.parse("-5", 0));
        assertEquals(7L, Numbers.parse("Level 7", 0));
        assertEquals(500L, Numbers.parse(".5", 3));
        assertNull(Numbers.parse("none", 0));
        assertNull(Numbers.parse("", 0));
        assertNull(Numbers.parse(null, 0));
        assertNull(Numbers.parse("99999999999999999999999", 0));
    }

    @Test
    void aUnitWordIsntAMultiplier() {
        assertEquals(5L, Numbers.parse("5 blocks", 0));
        assertEquals(12L, Numbers.parse("12 kills", 0));
        assertEquals(3L, Numbers.parse("3 mins", 0));
        assertEquals(3L, Numbers.parse("3mins", 0));
        assertEquals(7L, Numbers.parse("7 trophies", 0));
        assertEquals(9L, Numbers.parse("9 Million-ish", 0));
        assertEquals(4_000L, Numbers.parse("4k!", 0));
    }

    @Test
    void coloursArentTheNumber() {
        assertEquals(42L, Numbers.parse("&#FF0000 42", 0));
        assertEquals(1_234L, Numbers.parse("&#00ff00$1,234", 0));
        assertEquals(42L, Numbers.parse("<#ff0000>42", 0));
        assertEquals(42L, Numbers.parse("<gradient:#ff0000:#00ff00>42</gradient>", 0));
        assertEquals(42L, Numbers.parse("{#ff0000}42", 0));
        assertEquals(42L, Numbers.parse("§x§f§f§0§0§0§042", 0));
        assertEquals(42L, Numbers.parse("&x&1&2&3&4&5&6&l42", 0));
        assertEquals(1_500L, Numbers.parse("§6§l1.5k§r coins", 0));
    }
}
