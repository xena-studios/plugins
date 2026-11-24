package co.xenastudios.core.common.i18n

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class FormatsTest {

    private fun f(tag: String) = Formats.of(LanguageTag.of(tag))
    private val nb = ' '

    @Test
    fun wholeNumbersAreGroupedPerLanguage() {
        val n = 1234567L
        assertEquals("1,234,567", f("en").number(n))
        assertEquals("1.234.567", f("de").number(n))
        assertEquals("1.234.567", f("es").number(n))
        assertEquals("1${nb}234${nb}567", f("fr").number(n))
        assertEquals("1.234.567", f("pt-BR").number(n))
        assertEquals("1${nb}234${nb}567", f("ru").number(n))
        assertEquals("1${nb}234${nb}567", f("pl").number(n))
        assertEquals("1,234,567", f("zh-CN").number(n))
        assertEquals("-1,000", f("en").number(-1000))
        assertEquals("999", f("en").number(999))
        assertEquals("0", f("de").number(0))
        assertEquals("-9,223,372,036,854,775,808", f("en").number(Long.MIN_VALUE))
    }

    @Test
    fun spanishAndPolishDontGroupFourDigits() {
        assertEquals("1234", f("es").number(1234))
        assertEquals("12.345", f("es").number(12345))
        assertEquals("1234", f("pl").number(1234))
        assertEquals("1.234", f("de").number(1234))
    }

    @Test
    fun decimalsAreRoundedHalfUpAndTrailingZerosDropped() {
        assertEquals("1,234.5", f("en").number(1234.5))
        assertEquals("1.234,5", f("de").number(1234.5))
        assertEquals("1${nb}234,57", f("fr").number(1234.567))
        assertEquals("2.00", f("en").number(2.0, minDecimals = 2))
        assertEquals("2", f("en").number(2.0))
        assertEquals("0.13", f("en").number(0.125))
        assertEquals("-0.5", f("en").number(-0.5))
        assertEquals("0", f("en").number(-0.001), "no -0")
        assertEquals("3", f("en").number(2.5, maxDecimals = 0))
        assertEquals("∞", f("en").number(Double.POSITIVE_INFINITY))
        assertEquals("NaN", f("en").number(Double.NaN))
    }

    @Test
    fun durationsAreWrittenInWordsWithEachLanguagesPlurals() {
        val d = 7.days * 2 + 3.days
        assertEquals("2 weeks, 3 days", f("en").duration(d))
        assertEquals("1 week", f("en").duration(7.days + 3.hours))
        assertEquals("2 Wochen, 3 Tage", f("de").duration(d))
        assertEquals("1 minute, 1 second", f("en").duration(61.seconds))
        assertEquals("0 seconds", f("en").duration(500.milliseconds))
        assertEquals("0 seconds", f("en").duration(Duration.ZERO - 5.seconds))
        assertEquals("1 día", f("es").duration(1.days))
        assertEquals("0 seconde", f("fr").duration(Duration.ZERO), "French: 0 is singular")
        assertEquals("2周3天", f("zh-CN").duration(d))
        assertEquals("1 hora, 30 minutos", f("pt-BR").duration(90.minutes))
    }

    @Test
    fun russianAndPolishHaveThreeForms() {
        val ru = f("ru")
        assertEquals("1 день", ru.duration(1.days))
        assertEquals("2 дня", ru.duration(2.days))
        assertEquals("5 дней", ru.duration(5.days))
        assertEquals("11 часов", ru.duration(11.hours))
        assertEquals("21 час", ru.duration(21.hours))
        assertEquals("22 часа", ru.duration(22.hours))
        val pl = f("pl")
        assertEquals("1 minuta", pl.duration(1.minutes))
        assertEquals("2 minuty", pl.duration(2.minutes))
        assertEquals("5 minut", pl.duration(5.minutes))
        assertEquals("22 minuty", pl.duration(22.minutes))
        assertEquals("12 minut", pl.duration(12.minutes))
        assertEquals(Formats.Plural.MANY, pl.plural(21), "Polish: 21 isn't 'one'")
        assertEquals(Formats.Plural.ONE, ru.plural(21))
    }

    @Test
    fun shortDurations() {
        assertEquals("2w 3d", f("en").durationShort(17.days))
        assertEquals("4h 30m", f("en").durationShort(270.minutes))
        assertEquals("4 Std. 30 Min.", f("de").durationShort(270.minutes))
        assertEquals("4 ч 30 мин", f("ru").durationShort(270.minutes))
        assertEquals("4小时30分钟", f("zh-CN").durationShort(270.minutes))
        assertEquals("1h 1m 1s", f("en").durationShort(1.hours + 61.seconds, units = 3))
    }

    @Test
    fun datesAndTimes() {
        // 2026-11-23T15:04:05Z
        val at = 1795446245000L
        assertEquals("Nov 23, 2026", f("en").date(at))
        assertEquals("3:04 PM", f("en").time(at))
        assertEquals("Nov 23, 2026, 3:04 PM", f("en").dateTime(at))
        assertEquals("23.11.2026, 15:04", f("de").dateTime(at))
        assertEquals("23 nov 2026", f("es").date(at))
        assertEquals("23 nov. 2026", f("fr").date(at))
        assertEquals("23 de nov. de 2026 15:04", f("pt-BR").dateTime(at))
        assertEquals("23 нояб. 2026 г.", f("ru").date(at))
        assertEquals("23 lis 2026", f("pl").date(at))
        assertEquals("2026年11月23日 15:04", f("zh-CN").dateTime(at))
        // The reader's offset moves the date: 23:04 in UTC+9 is the next day.
        assertEquals("Nov 24, 2026, 12:04 AM", f("en").dateTime(at, utcOffsetSeconds = 9 * 3600))
        assertEquals("Jan 1, 1970", f("en").date(0))
        assertEquals("Dec 31, 1969, 11:59 PM", f("en").dateTime(-1))
        assertEquals("Feb 29, 2024", f("en").date(1709164800000L))
    }

    @Test
    fun aLanguageFallsBackToItsOwnThenEnglish() {
        assertEquals(LanguageTag.of("de"), Formats.of(LanguageTag.of("de-AT")).tag)
        assertEquals(LanguageTag.of("pt-BR"), Formats.of(LanguageTag.of("pt")).tag)
        assertEquals(LanguageTag.of("zh-CN"), Formats.of(LanguageTag.of("zh")).tag)
        assertEquals(LanguageTag.ENGLISH, Formats.of(LanguageTag.of("pt-PT")).tag)
        assertEquals(LanguageTag.ENGLISH, Formats.of(LanguageTag.of("zh-TW")).tag)
        assertEquals(LanguageTag.ENGLISH, Formats.of(LanguageTag.of("it")).tag)
        assertEquals(8, Formats.LANGUAGES.size)
    }
}
