package co.xenastudios.m0

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class DurationsTest {
    private fun ok(s: String) = assertIs<Parsed.Ok<kotlin.time.Duration>>(Durations.parse(s)).value

    @Test fun parsesCompoundDurations() {
        assertEquals(1.days + 2.hours, ok("1d2h"))
        assertEquals(1.days + 2.hours + 30.minutes, ok("1d 2h 30m"))
        assertEquals(90.seconds, ok("90s"))
        assertEquals(250.milliseconds, ok("250ms"))
        assertEquals(10.days, ok("1w3d"))
        assertEquals(30.days, ok("1mo"))
        assertEquals(2.hours, ok("2H"))
    }

    @Test fun reportsWhereItFailed() {
        assertEquals(ParseError("1x", 1, "unknown unit 'x'"), assertIs<Parsed.Err>(Durations.parse("1x")).error)
        assertEquals("missing unit", assertIs<Parsed.Err>(Durations.parse("12")).error.reason)
        assertEquals("expected a number", assertIs<Parsed.Err>(Durations.parse("h")).error.reason)
        assertEquals("empty duration", assertIs<Parsed.Err>(Durations.parse("  ")).error.reason)
        assertEquals("number too large", assertIs<Parsed.Err>(Durations.parse("99999999999d")).error.reason)
    }

    @Test fun formatsBackToTheSameValue() {
        for (s in listOf("1d2h", "90s", "1w3d", "250ms", "3h5m7s")) {
            val d = ok(s)
            assertEquals(d, ok(Durations.format(d)), s)
        }
        assertEquals("1d2h", Durations.format(1.days + 2.hours))
    }
}

class MarkupTest {
    @Test fun plainTextStaysPlain() {
        assertEquals(Component("hello"), Markup.parse("hello"))
    }

    @Test fun nestsColorsAndDecorations() {
        val c = Markup.parse("<red>Hi <bold>there</bold>!</red> done")
        assertEquals("Hi there! done", c.plain())
        val red = c.children[0]
        assertEquals(TextColor.NAMED["red"], red.style.color)
        assertEquals(Component("Hi "), red.children[0])
        assertEquals(Style(bold = true), red.children[1].style)
        assertEquals(Component(" done"), c.children[1])
    }

    @Test fun hexColorsAliasesAndNegation() {
        val c = Markup.parse("<#ff8800>orange</#ff8800><b>b</b><!i>n")
        assertEquals(TextColor(0xff8800), c.children[0].style.color)
        assertEquals("#ff8800", c.children[0].style.color!!.hex())
        assertEquals(Style(bold = true), c.children[1].style)
        assertEquals(Style(italic = false), c.children[2].style)
        assertEquals(TextColor.NAMED["gold"], Markup.parse("<color:gold>x").style.color)
    }

    @Test fun clickAndHoverWithQuotedArguments() {
        val c = Markup.parse("<click:run_command:/tp a:b><hover:show_text:'<red>Go > there'>go")
        assertEquals(ClickAction.RunCommand("/tp a:b"), c.style.click)
        val hover = c.children[0].style.hover!!
        assertEquals("Go > there", hover.plain())
        assertEquals(TextColor.NAMED["red"], hover.style.color)
    }

    @Test fun resetEscapesAndUnknownTags() {
        assertEquals("a<b>c", Markup.parse("a\\<b>c").plain())
        assertEquals("<nope>x", Markup.parse("<nope>x").plain())
        val reset = Markup.parse("<red><bold>a<reset>b")
        assertEquals(Component("b"), reset.children.last())
        assertEquals("x</blue>", Markup.parse("x</blue>").plain())
        assertEquals("a < b", Markup.parse("a < b").plain())
    }

    @Test fun neverThrowsOnJunk() {
        val junk = listOf("<", ">", "</>", "<<<>>>", "<click:>", "<hover:show_text:'", "\\", "<#zzzzzz>", "<color:>", "</>".repeat(50))
        for (s in junk) assertTrue(Markup.parse(s).plain().length <= s.length, s)
    }
}

class ConfigTest {
    @Test fun defaultsRoundTrip() {
        val text = Configs.encode(SpikeConfig())
        assertTrue("\"default-language\": \"en\"" in text, text)
        assertEquals(SpikeConfig(), assertIs<Parsed.Ok<SpikeConfig>>(Configs.decode(text)).value)
    }

    @Test fun partialFileKeepsDefaultsAndIgnoresUnknownKeys() {
        val c = assertIs<Parsed.Ok<SpikeConfig>>(
            Configs.decode("""{"storage":{"type":"mysql"},"homes":{"teleport-delay":"5s"},"future-key":true}"""),
        ).value
        assertEquals(SpikeConfig.StorageType.MYSQL, c.storage.type)
        assertEquals("xena_", c.storage.prefix)
        assertEquals(5.seconds, assertIs<Parsed.Ok<kotlin.time.Duration>>(Durations.parse(c.homes.teleportDelay)).value)
    }

    @Test fun badFilesAreValuesNotExceptions() {
        assertIs<Parsed.Err>(Configs.decode("""{"storage":{"type":"postgres"}}"""))
        assertIs<Parsed.Err>(Configs.decode("""{"homes":{"limit":"three"}}"""))
        assertIs<Parsed.Err>(Configs.decode("not json"))
    }
}
