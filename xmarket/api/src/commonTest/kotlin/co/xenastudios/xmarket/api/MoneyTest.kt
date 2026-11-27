package co.xenastudios.xmarket.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MoneyTest {
    @Test
    fun parsesAndWritesExactly() {
        assertEquals(25_000L, Money.parse("2.5")!!.units)
        assertEquals("2.5", Money.parse("2.50").toString())
        assertEquals("0.0001", Money.parse(".0001").toString())
        assertEquals("-3", Money.parse("-3").toString())
        assertNull(Money.parse("0.00001"))
        assertNull(Money.parse("1e3"))
        assertNull(Money.parse(""))
        assertEquals("2.50", Money.parse("2.5")!!.toPlainString(2))
    }

    @Test
    fun scalesWithoutError() {
        val price = Money.parse("0.1")!!
        assertEquals("0.3", price.times(3).toString())
        assertEquals("0.09", price.percent(90).toString())
        assertEquals("3.3333", Money.of(10).divide(3, up = false).toString())
        assertEquals("3.3334", Money.of(10).divide(3, up = true).toString())
        assertEquals(3, Money.parse("3.9")!!.whole)
        assertEquals(4, Money.parse("3.1")!!.wholeUp)
        assertEquals(Money.MAX_UNITS, Money.of(Long.MAX_VALUE / 2).units, "a total too large stops at the most")
    }
}
