package co.xenastudios.xstats

import co.xenastudios.xstats.api.StatUnit
import co.xenastudios.xstats.config.XStatsConfig
import co.xenastudios.xstats.data.Calendar
import co.xenastudios.xstats.stat.Format
import co.xenastudios.xstats.stat.Numbers
import co.xenastudios.xstats.stat.Vanilla
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** How numbers read, how placeholders' text becomes numbers, the calendar, and the stat table: 1.x's, on every platform. */
class FormatTest {
    @Test
    fun numbersReadAs1xWroteThem() {
        assertEquals("1,234,567", Format.value(StatUnit.COUNT, 0, 1_234_567))
        assertEquals("12.34", Format.value(StatUnit.COUNT, 2, 1234))
        assertEquals("-0.05", Format.value(StatUnit.COUNT, 2, -5))
        assertEquals("45s", Format.time(45))
        assertEquals("12m", Format.time(720))
        assertEquals("3h 12m", Format.time(3 * 3600 + 12 * 60))
        assertEquals("2d 4h", Format.time(2 * 86400 + 4 * 3600))
        assertEquals("350 m", Format.distance(35_000))
        assertEquals("1.2 km", Format.distance(123_456))
        assertEquals("150 km", Format.distance(15_000_000))
        assertEquals("1 heart", Format.value(StatUnit.DAMAGE, 0, 20))
        assertEquals("2.2 hearts", Format.value(StatUnit.DAMAGE, 0, 45))
        assertEquals("2.50", Format.ratio(5, 2))
        assertEquals("7.00", Format.ratio(7, 0))
    }

    @Test
    fun placeholdersTextBecomesNumbers() {
        assertEquals(123456, Numbers.parse("$1,234.56", 2))
        assertEquals(12_500, Numbers.parse("12.5k", 0))
        assertEquals(5, Numbers.parse("5 blocks", 0))
        assertEquals(3, Numbers.parse("3mins", 0))
        assertEquals(42, Numbers.parse("§a42", 0))
        assertEquals(42, Numbers.parse("&#FF0000 42", 0))
        assertEquals(13, Numbers.parse("12.5", 0))
        assertNull(Numbers.parse("none", 0))
        assertNull(Numbers.parse("99999999999999999999", 0))
    }

    @Test
    fun daysWeeksAndMonths() {
        assertEquals(20458, Calendar.days(2026, 1, 5))
        assertEquals(Triple(2026, 1, 5), Calendar.civil(20458))
        assertEquals(2026 * 12, Calendar.month(20458))
        assertEquals(Calendar.days(2026, 1, 1), Calendar.firstDay(2026 * 12))
        // 2026-01-05 is a Monday: its own week's start, and Sunday-weeks started the day before.
        assertEquals(20458, Calendar.week(20460, XStatsConfig.WeekDay.MONDAY))
        assertEquals(20457, Calendar.week(20458, XStatsConfig.WeekDay.SUNDAY))
        assertEquals(Calendar.days(2024, 2, 29), Calendar.days(2024, 3, 1) - 1)
    }

    @Test
    fun theStatTable() {
        assertEquals("PLAY_ONE_MINUTE", Vanilla.bukkitName("play_time"))
        assertEquals("JUMP", Vanilla.bukkitName("jump"))
        assertEquals("play_time", Vanilla.term("play_one_minute").name)
        assertEquals("mined:stone", Vanilla.term("minecraft:mined:minecraft:stone").toString())
        assertEquals(StatUnit.DISTANCE, Vanilla.unit(Vanilla.term("aviate_one_cm")))
        assertEquals(StatUnit.TIME, Vanilla.unit(Vanilla.term("play_time")))
        assertFailsWith<IllegalArgumentException> { Vanilla.term("flying_pigs") }
        assertFailsWith<IllegalArgumentException> { Vanilla.term("eaten:cake") }
        val file = mapOf("minecraft:custom" to mapOf("minecraft:play_one_minute" to 2400L, "minecraft:jump" to 7L), "minecraft:mined" to mapOf("minecraft:stone" to 3L, "minecraft:dirt" to 2L))
        assertEquals(120, Vanilla.fromFile(Vanilla.term("play_time"), file))
        assertEquals(5, Vanilla.fromFile(Vanilla.term("mined:*"), file))
        assertEquals(7, Vanilla.fromFile(Vanilla.term("jump"), file))
    }
}
