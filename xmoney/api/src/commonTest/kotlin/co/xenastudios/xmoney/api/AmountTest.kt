package co.xenastudios.xmoney.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AmountTest {
    @Test
    fun parsesExactlyAndRoundsAsAsked() {
        assertEquals(125_000, Amount.parse("12.5")!!.units)
        assertEquals(-30_000, Amount.parse("-3")!!.units)
        assertEquals(10_000_000, Amount.parse("1e3")!!.units)
        assertNull(Amount.parse("0.00001"))
        assertEquals(0, Amount.parse("0.00001", Rounding.DOWN)!!.units)
        assertEquals(1, Amount.parse("0.00001", Rounding.UP)!!.units)
        assertEquals(9950, Amount.parse("0.99495", Rounding.HALF_UP)!!.units)
        assertNull(Amount.parse("abc"))
        assertNull(Amount.parse("."))
        assertNull(Amount.parse("100000000000001"))
        assertEquals(Amount.MAX_UNITS, Amount.parse("100000000000000")!!.units)
    }

    // Plain numbers are worked out without the general parser; the same answers either way.
    @Test
    fun plainNumbersParseAsAnyOther() {
        assertEquals(0, Amount.parse("0")!!.units)
        assertEquals(100, Amount.parse("0.01")!!.units)
        assertEquals(1, Amount.parse("0.0001")!!.units)
        assertEquals(70_000, Amount.parse(" 007 ")!!.units)
        assertEquals(12_345_678, Amount.parse("1234.5678")!!.units)
        assertEquals(Amount.parse("+12.5"), Amount.parse("12.5"))
        assertEquals(Amount.parse("1.50000"), Amount.parse("1.5"))
        assertNull(Amount.parse("100000000000000.0001"))
        assertNull(Amount.parse("999999999999999"))
        assertNull(Amount.parse("12a"))
        assertNull(Amount.parse("1.2.3"))
    }

    @Test
    fun doublesLoseTheirBinaryNoise() {
        // 0.1 + 0.2 is 0.30000000000000004 as a double: taken as 0.3, not rounded up to 0.3001.
        assertEquals(3000, Amount.ofDouble(0.1 + 0.2, Rounding.UP)!!.units)
        assertEquals(43_500_000, Amount.ofDouble(4.35 * 1000, Rounding.DOWN)!!.units)
        assertEquals(1, Amount.ofDouble(0.00001, Rounding.UP)!!.units)
        assertEquals(0, Amount.ofDouble(0.00001, Rounding.DOWN)!!.units)
        assertNull(Amount.ofDouble(Double.NaN, Rounding.DOWN))
        assertNull(Amount.ofDouble(Double.POSITIVE_INFINITY, Rounding.DOWN))
        assertNull(Amount.ofDouble(1e300, Rounding.DOWN))
        // From ten billion up its own digits are kept and rounded the safe way.
        assertEquals(1_234_567_890_123_456L, Amount.ofDouble(123456789012.34567, Rounding.DOWN)!!.units)
    }

    @Test
    fun printsEveryPlaceItHas() {
        assertEquals("12.5", Amount.parse("12.5").toString())
        assertEquals("12.50", Amount.parse("12.5")!!.toPlainString(2))
        assertEquals("0.995", Amount.parse("0.995")!!.toPlainString(2))
        assertEquals("-3.00", Amount.of(-3).toPlainString(2))
        assertEquals("7", Amount.of(7).toString())
    }
}
