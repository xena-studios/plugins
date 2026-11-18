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
    void typedAmountsAreRoundedToTheDecimalsOrRefused() {
        assertEquals(10_0100L, Money.units(10.005, 2), "half up");
        assertEquals(3000, Money.units(0.1 + 0.2, 2));
        assertEquals(-1, Money.units(Double.NaN, 2));
        assertEquals(-1, Money.units(Double.POSITIVE_INFINITY, 2));
        assertEquals(-1, Money.units(-0.01, 2));
        assertEquals(-1, Money.units(1e300, 2));
        assertEquals(-1, Money.units((BigDecimal) null, 2));
        assertEquals(Money.MAX_UNITS, Money.units(BigDecimal.valueOf(Money.MAX_WHOLE), 4));
    }

    @Test
    void otherPluginsAmountsKeepFourPlacesAndNeverRoundInTheirFavour() {
        // Whatever the currency's decimals: a split payment costs what it would whole.
        assertEquals(9950, Money.outgoing(0.995));
        assertEquals(50, Money.outgoing(0.005));
        assertEquals(Money.ONE, Money.outgoing(0.995) + Money.outgoing(0.005));
        assertEquals(4000, Money.outgoing(0.4), "with 0 decimals, 0.4 still takes 0.4");
        assertEquals(2_5000L, Money.incoming(2.5), "with 0 decimals, 2.5 is still 2.5, not 3");
        // Past four places: in is rounded down, out up, so rounding never makes money.
        assertEquals(1, Money.outgoing(new BigDecimal("0.00001")), "a positive withdrawal always takes something");
        assertEquals(0, Money.incoming(new BigDecimal("0.00009")));
        assertEquals(1_2346L, Money.outgoing(new BigDecimal("1.23451")));
        assertEquals(1_2345L, Money.incoming(new BigDecimal("1.23459")));
        // A double's binary noise isn't a fraction of a unit to round up or down.
        assertEquals(3000, Money.outgoing(0.1 + 0.2));
        assertEquals(435 * Money.ONE, Money.incoming(4.35 * 100));
        assertEquals(3_3000L, Money.outgoing(1.1 * 3));
        // From ten billion up, 15 digits are coarser than units: the double's own digits are
        // rounded the safe way, not half even at 15 digits (which paid in ...012.3460).
        assertEquals(1_234_567_890_123_456L, Money.incoming(123456789012.34567));
        assertEquals(1_234_567_890_123_457L, Money.outgoing(123456789012.34567));
        assertEquals(987_654_321_098_765L, Money.incoming(98765432109.87654));
        assertEquals(987_654_321_098_766L, Money.outgoing(98765432109.87654));
        assertEquals(-1, Money.outgoing(Double.NaN));
        assertEquals(-1, Money.incoming(-0.01));
        assertEquals(-1, Money.outgoing(1e300));
        assertEquals(-1, Money.incoming((BigDecimal) null));
    }

    @Test
    void exactKeepsEveryPlaceAndAtLeastTheDecimals() {
        assertEquals(new BigDecimal("12.50"), Money.exact(12_5000L, 2));
        assertEquals(new BigDecimal("0.995"), Money.exact(9950, 2));
        assertEquals(new BigDecimal("1000"), Money.exact(1000 * Money.ONE, 0));
        assertEquals(new BigDecimal("0.00"), Money.exact(0, 2));
        assertEquals(new BigDecimal("-5.00"), Money.exact(-5 * Money.ONE, 2));
    }
}
