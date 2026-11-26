package co.xenastudios.xmoney

import co.xenastudios.xmoney.api.Amount
import co.xenastudios.xmoney.config.CurrencySettings
import co.xenastudios.xmoney.config.XMoneyConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** 1.x's FormatTest, on 2.0's formatter: the same amounts shown and read the same way. */
class CurrencyFormatTest {
    private val one = Amount.ONE
    private val dollars = CurrencyFormat(XMoneyConfig.currency(XMoneyConfig.defaults))
    private val euros = CurrencyFormat(CurrencySettings("euro", "euros", "€", 2, "{amount} {symbol}", ".", ",", listOf("k", "M", "B", "T")))
    private val whole = CurrencyFormat(CurrencySettings("gem", "gems", "", 0, "{amount} {name}", ",", "", listOf("k", "M", "B", "T")))
    private val coins = CurrencyFormat(CurrencySettings("coin", "coins", "", 0, "{amount} {name}", ".", ",", listOf("k", "M", "B", "T")))

    private fun CurrencyFormat.read(typed: String): Long {
        val parsed = parse(typed)
        assertEquals(null, parsed.problem, "\"$typed\" was refused")
        return parsed.units
    }

    private fun CurrencyFormat.refuses(typed: String) = assertNotNull(parse(typed).problem, "\"$typed\" was read as ${parse(typed).units}")

    @Test
    fun formats() {
        assertEquals("$0.00", dollars.format(0))
        assertEquals("$1,234,567.89", dollars.format(1_234_567_8900L))
        assertEquals("$-5.00", dollars.format(-5 * one))
        assertEquals("1 coin", coins.format(one))
        assertEquals("1.000 coins", coins.format(1000 * one))
        assertEquals("1234.50", dollars.plain(1234_5000L))
    }

    @Test
    fun shortForms() {
        assertEquals("950", dollars.shortForm(950 * one))
        assertEquals("1k", dollars.shortForm(1000 * one))
        assertEquals("1.9k", dollars.shortForm(1999 * one))
        assertEquals("12.3M", dollars.shortForm(12_345_678 * one))
        assertEquals("4T", dollars.shortForm(4_000_000_000_000L * one))
        assertEquals("1.2k", whole.shortForm(1200 * one), "not 12k")
    }

    @Test
    fun readsWhatPlayersType() {
        assertEquals(250 * one, dollars.read("250"))
        assertEquals(1000 * one, dollars.read("1,000"))
        assertEquals(12_5000L, dollars.read("12.5"))
        assertEquals(1500 * one, dollars.read("1.5k"))
        assertEquals(2_000_000 * one, dollars.read("2M"))
        assertEquals(5000, dollars.read(".5"))
        assertEquals(10 * one, dollars.read("10.000"))
        assertEquals(1000 * one, coins.read("1.000"))
        assertEquals(1_234_567_5000L, dollars.read("1,234,567.50"))
        assertEquals(CurrencyFormat.Problem.TOO_PRECISE, dollars.parse("1.005").problem)
        for (bad in listOf("-5", "NaN", "1e5", "1,5", "2,50", "1,0000", "", "abc")) dollars.refuses(bad)
        coins.refuses("1,5")
        assertEquals(CurrencyFormat.Problem.TOO_LARGE, dollars.parse("999999999999999999999").problem)
        assertEquals(CurrencyFormat.Problem.TOO_LARGE, dollars.parse("100000000000001").problem)
    }

    @Test
    fun otherSeparators() {
        assertEquals(1500 * one, euros.read("1.500,00"))
        assertEquals(1_5000L, euros.read("1,5"))
        assertEquals(1500 * one, euros.read("1,5k"))
        euros.refuses("1500.00")
        for (units in listOf(0L, 1_5000L, 1234_5600L, 100 * one)) {
            assertEquals(units, euros.read(euros.typed(units)))
            assertEquals(units, dollars.read(dollars.typed(units)))
        }
        assertEquals(100 * one, whole.read("100"))
        assertEquals(1000 * one, whole.read("1,000"))
        assertEquals(100 * one, whole.read(whole.typed(100 * one)))
    }

    @Test
    fun startingBalancesRoundHalfUpToTheDecimals() {
        assertEquals(10_0100L, XMoneyPlugin.roundTo(10_0050L, 2))
        assertEquals(3 * one, XMoneyPlugin.roundTo(2_5000L, 0))
        assertEquals(1_2345L, XMoneyPlugin.roundTo(1_2345L, 4))
    }
}
