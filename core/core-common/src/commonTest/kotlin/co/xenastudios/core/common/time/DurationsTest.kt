package co.xenastudios.core.common.time

import co.xenastudios.core.common.Outcome
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class DurationsTest {

    private fun ok(input: String): Duration = assertIs<Outcome.Ok<Duration>>(Durations.parse(input), input).value

    private fun err(input: String): DurationError = assertIs<Outcome.Err<DurationError>>(Durations.parse(input), input).error

    @Test
    fun readsWhatTodaysPluginsRead() {
        assertEquals(30.minutes, ok("30m"))
        assertEquals(6.hours, ok("6h"))
        assertEquals(17.days, ok("2w3d"))
        assertEquals(90.minutes, ok("1.5h"))
        assertEquals(30.minutes, ok(".5h"))
        assertEquals(3.days + 1.hours, ok("1h3d"))
        assertEquals(30.minutes, ok("  30M  "))
        assertEquals(1500.milliseconds, ok("1.5s"))
    }

    @Test
    fun readsSpacesMillisecondsMonthsAndYears() {
        assertEquals(1.days + 2.hours + 30.minutes, ok("1d 2h 30m"))
        assertEquals(250.milliseconds, ok("250ms"))
        assertEquals(30.days, ok("1mo"))
        assertEquals(365.days, ok("1y"))
        assertEquals(30.days + 5.minutes, ok("1MO5m"))
        assertEquals(Duration.ZERO, ok("0s"))
    }

    @Test
    fun saysWhatIsWrongAndWhere() {
        assertEquals(DurationError("", DurationError.Kind.EMPTY, 0, ""), err(""))
        assertEquals(DurationError.Kind.EMPTY, err("   ").kind)
        assertEquals(DurationError("1x", DurationError.Kind.UNKNOWN_UNIT, 1, "x"), err("1x"))
        assertEquals(DurationError("30", DurationError.Kind.MISSING_UNIT, 2, ""), err("30"))
        assertEquals(DurationError("h2", DurationError.Kind.EXPECTED_NUMBER, 0, "h"), err("h2"))
        assertEquals(DurationError("1h -2m", DurationError.Kind.EXPECTED_NUMBER, 3, "-"), err("1h -2m"))
        assertEquals(DurationError("1.2.3h", DurationError.Kind.BAD_NUMBER, 0, "1.2.3"), err("1.2.3h"))
        assertEquals(DurationError.Kind.BAD_NUMBER, err(".h").kind)
        assertEquals(DurationError.Kind.MISSING_UNIT, err("1 d").kind)
        assertEquals(DurationError("1d 101y", DurationError.Kind.TOO_LONG, 3, "101y"), err("1d 101y"))
        assertEquals(DurationError.Kind.TOO_LONG, err("99999999999999999999d").kind)
    }

    @Test
    fun allowsUpToAHundredYears() {
        assertEquals(Durations.MAX, ok("100y"))
        assertEquals(DurationError.Kind.TOO_LONG, err("100y1ms").kind)
    }

    @Test
    fun formatsTheTwoLargestUnitsForMessages() {
        assertEquals("2w 3d", Durations.format(17.days))
        assertEquals("4h 30m", Durations.format(4.hours + 30.minutes + 10.seconds))
        assertEquals("12s", Durations.format(12.seconds + 400.milliseconds))
        assertEquals("1w", Durations.format(7.days + 3.hours))
        assertEquals("0s", Durations.format(999.milliseconds))
        assertEquals("0s", Durations.format(-5.seconds))
        assertEquals("1d 2h 3m", Durations.format(1.days + 2.hours + 3.minutes, units = 3))
        assertEquals("1d", Durations.format(1.days + 2.hours, units = 0))
    }

    @Test
    fun writesTheExactFormForConfigs() {
        assertEquals("1d2h30m", Durations.write(1.days + 2.hours + 30.minutes))
        assertEquals("1w", Durations.write(7.days))
        assertEquals("1s500ms", Durations.write(1500.milliseconds))
        assertEquals("0s", Durations.write(Duration.ZERO))
        assertEquals("0s", Durations.write(-1.days))
    }

    @Test
    fun readsBackWhatItWrites() {
        val random = Random(122)
        repeat(2_000) {
            val duration = random.nextLong(0, Durations.MAX.inWholeMilliseconds + 1).milliseconds
            val written = Durations.write(duration)
            assertEquals(duration, ok(written), written)
        }
    }

    @Test
    fun neverThrowsOnJunk() {
        val random = Random(121)
        val alphabet = "0123456789.msdhwoy MSHx-+,:\téİ"
        repeat(5_000) {
            val input = CharArray(random.nextInt(0, 12)) { alphabet[random.nextInt(alphabet.length)] }.concatToString()
            when (val outcome = Durations.parse(input)) {
                is Outcome.Ok -> assertEquals(true, outcome.value in Duration.ZERO..Durations.MAX, input)
                is Outcome.Err -> assertEquals(input, outcome.error.input)
            }
        }
    }
}
