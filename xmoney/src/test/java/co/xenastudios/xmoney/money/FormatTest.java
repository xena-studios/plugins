package co.xenastudios.xmoney.money;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.xenastudios.xmoney.config.XMConfig;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Amounts in and out: rounding, separators, the short form, and what players type. */
class FormatTest {

    final Format dollars = new Format(XMConfig.DEFAULTS.currency());
    final Format coins = new Format(new XMConfig.CurrencySettings("coin", "coins", "", 0, "{amount} {name}", ".", ",", List.of("k", "M", "B", "T")));

    @Test
    void formats() {
        assertEquals("$0.00", dollars.format(0));
        assertEquals("$1,234,567.89", dollars.format(1_234_567_8900L));
        assertEquals("-$5.00".replace("-$", "$-"), dollars.format(-5 * Money.ONE));
        assertEquals("1 coin", coins.format(Money.ONE));
        assertEquals("1.000 coins", coins.format(1000 * Money.ONE));
        assertEquals("1234.50", dollars.plain(1234_5000L));
    }

    @Test
    void shortForm() {
        assertEquals("950", dollars.shortForm(950 * Money.ONE));
        assertEquals("1k", dollars.shortForm(1000 * Money.ONE));
        assertEquals("1.9k", dollars.shortForm(1999 * Money.ONE));
        assertEquals("12.3M", dollars.shortForm(12_345_678 * Money.ONE));
        assertEquals("4T", dollars.shortForm(4_000_000_000_000L * Money.ONE));
    }

    @Test
    void parses() {
        assertEquals(250 * Money.ONE, dollars.parse("250"));
        assertEquals(1000 * Money.ONE, dollars.parse("1,000"));
        assertEquals(12_5000L, dollars.parse("12.5"));
        assertEquals(1500 * Money.ONE, dollars.parse("1.5k"));
        assertEquals(2_000_000 * Money.ONE, dollars.parse("2M"));
        assertEquals(5000, dollars.parse(".5"));
        assertEquals(1000 * Money.ONE, coins.parse("1.000"));
        assertThrows(IllegalArgumentException.class, () -> dollars.parse("1.005"));
        assertThrows(IllegalArgumentException.class, () -> coins.parse("1,5"));
        assertThrows(IllegalArgumentException.class, () -> dollars.parse("-5"));
        assertThrows(IllegalArgumentException.class, () -> dollars.parse("NaN"));
        assertThrows(IllegalArgumentException.class, () -> dollars.parse("1e5"));
        assertThrows(IllegalArgumentException.class, () -> dollars.parse("999999999999999999999"));
    }

    @Test
    void outsideAmountsAreRoundedOrRefused() {
        assertEquals(10_0100L, Money.units(10.005, 2), "half up");
        assertEquals(3000, Money.units(0.1 + 0.2, 2));
        assertEquals(-1, Money.units(Double.NaN, 2));
        assertEquals(-1, Money.units(Double.POSITIVE_INFINITY, 2));
        assertEquals(-1, Money.units(-0.01, 2));
        assertEquals(-1, Money.units(1e300, 2));
        assertEquals(-1, Money.positive(0.001, 2), "rounds to nothing");
        assertEquals(-1, Money.units((BigDecimal) null, 2));
        assertEquals(Money.MAX_UNITS, Money.units(BigDecimal.valueOf(Money.MAX_WHOLE), 4));
    }
}
