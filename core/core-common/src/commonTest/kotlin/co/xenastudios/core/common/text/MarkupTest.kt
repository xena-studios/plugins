package co.xenastudios.core.common.text

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MarkupTest {

    private val red = TextColor.NAMED.getValue("red")
    private val gray = TextColor.NAMED.getValue("gray")
    private val purple = TextColor(0xB48CFF)
    private val green = TextColor(0x5EE08A)
    private val coral = TextColor(0xFF6B6B)

    // Today's default theme (core/src/main/java/co/xenastudios/core/text/Theme.java), as tags.
    private val theme = mapOf(
        "highlight" to MarkupTag.Styling(Style(color = purple)),
        "success" to MarkupTag.Styling(Style(color = green)),
        "error" to MarkupTag.Styling(Style(color = coral)),
        "prefix" to MarkupTag.Inserting(Markup.parse("<#B48CFF>»</#B48CFF> ").render()),
    )

    private fun render(input: String, vararg placeholders: Pair<String, Placeholder>) =
        Markup.parse(input, theme).render(*placeholders)

    private fun text(value: Any?) = Placeholder.text(value)

    /** Every component in the tree, depth first. */
    private fun Component.all(): List<Component> = listOf(this) + children.flatMap { it.all() }

    /** The style each piece of text ends up with, merged down the tree. */
    private fun Component.runs(inherited: Style = Style.EMPTY): List<Pair<String, Style>> {
        val style = inherited.merge(this.style)
        return (if (text.isEmpty()) emptyList() else listOf(text to style)) + children.flatMap { it.runs(style) }
    }

    private fun Component.styleOf(piece: String): Style =
        assertNotNull(runs().firstOrNull { it.first == piece }, "no \"$piece\" in $this").second

    @Test
    fun plainTextStaysPlain() {
        assertEquals(Component("hello"), render("hello"))
        assertEquals(Component.EMPTY, render(""))
        assertSame(Markup.EMPTY, Markup.parse(""))
    }

    @Test
    fun nestsColoursAndDecorations() {
        val c = render("<red>Hi <bold>there</bold>!</red> done")
        assertEquals("Hi there! done", c.plain())
        assertEquals(Style(color = red), c.styleOf("Hi "))
        assertEquals(Style(color = red, bold = true), c.styleOf("there"))
        assertEquals(Style(color = red), c.styleOf("!"))
        assertEquals(Style.EMPTY, c.styleOf(" done"))
    }

    @Test
    fun readsColoursEveryWayMiniMessageDoes() {
        assertEquals(TextColor(0xFF8800), render("<#ff8800>x").styleOf("x").color)
        assertEquals(TextColor(0xFF8800), render("<#FF8800>x</#FF8800>").styleOf("x").color)
        assertEquals("#ff8800", TextColor(0xFF8800).hex())
        assertEquals(TextColor.NAMED["gold"], render("<color:gold>x").styleOf("x").color)
        assertEquals(TextColor.NAMED["gold"], render("<colour:gold>x").styleOf("x").color)
        assertEquals(TextColor(0x123456), render("<c:#123456>x").styleOf("x").color)
        assertEquals(TextColor.NAMED["dark_gray"], render("<dark_grey>x").styleOf("x").color)
        assertEquals("<#12345>x", render("<#12345>x").plain())
        assertEquals("<color:nope>x", render("<color:nope>x").plain())
    }

    @Test
    fun decorationsHaveShortNamesAndTurnOff() {
        val c = render("<b>b</b><i>i</i><em>e</em><u>u</u><st>s</st><obf>o</obf><bold><!bold>n")
        assertEquals(Style(bold = true), c.styleOf("b"))
        assertEquals(Style(italic = true), c.styleOf("i"))
        assertEquals(Style(italic = true), c.styleOf("e"))
        assertEquals(Style(underlined = true), c.styleOf("u"))
        assertEquals(Style(strikethrough = true), c.styleOf("s"))
        assertEquals(Style(obfuscated = true), c.styleOf("o"))
        assertEquals(Style(bold = false), c.styleOf("n"))
        // A closing tag matches its long or short name.
        assertEquals(Style.EMPTY, render("<bold>a</b>z").styleOf("z"))
    }

    @Test
    fun closingAnOuterTagClosesTheOnesInside() {
        val c = render("<red><bold>a</red>b")
        assertEquals(Style(color = red, bold = true), c.styleOf("a"))
        assertEquals(Style.EMPTY, c.styleOf("b"))
        val closeLast = render("<red><bold>a</>b</>c")
        assertEquals(Style(color = red), closeLast.styleOf("b"))
        assertEquals(Style.EMPTY, closeLast.styleOf("c"))
    }

    @Test
    fun resetClosesEverything() {
        val c = render("<red><bold>a<reset>b")
        assertEquals(Style.EMPTY, c.styleOf("b"))
        assertEquals("ab", c.plain())
    }

    @Test
    fun newlines() {
        assertEquals("a\nb\nc", render("a<newline>b<br>c").plain())
    }

    @Test
    fun escapedAndUnknownTagsStayAsText() {
        assertEquals("a<b>c", render("a\\<b>c").plain())
        assertEquals("a\\b", render("a\\\\b").plain())
        assertEquals("a\\b", render("a\\b").plain())
        assertEquals("<nope:x>y", render("<nope:x>y").plain())
        assertEquals("x</blue>", render("x</blue>").plain())
        assertEquals("a < b > c", render("a < b > c").plain())
        assertEquals("<>", render("<>").plain())
        assertEquals("<Upper>", render("<Upper>").plain())
        assertEquals("<!nope>", render("<!nope>").plain())
        assertEquals("</>", render("</>").plain())
    }

    @Test
    fun escapeMakesAnyTextShowAsItIs() {
        for (raw in listOf("<red>Steve</red>", "a\\<b", "\\", "plain", "<click:run_command:'/op me'>x")) {
            assertEquals(raw, Markup.parse(Markup.escape(raw)).render().plain())
        }
        assertSame("plain", Markup.escape("plain"))
    }

    @Test
    fun clicksWithQuotedArgumentsAndColons() {
        val c = render("<click:run_command:'/tp a:b'>go</click> <click:open_url:https://x.co/a?b=c:d>link")
        assertEquals(ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tp a:b"), c.styleOf("go").click)
        assertEquals(ClickEvent(ClickEvent.Action.OPEN_URL, "https://x.co/a?b=c:d"), c.styleOf("link").click)
        assertEquals(ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/msg "), render("<click:suggest_command:'/msg '>x").styleOf("x").click)
        assertEquals(ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, "it's"), render("<click:copy_to_clipboard:\"it's\">x").styleOf("x").click)
        assertEquals("<click:explode:now>x", render("<click:explode:now>x").plain())
        assertEquals("<click:run_command>x", render("<click:run_command>x").plain())
    }

    @Test
    fun hoverTextIsMarkupToo() {
        val c = render("<hover:show_text:'<red>Go > there'>go")
        val hover = assertNotNull(c.styleOf("go").hover)
        assertEquals("Go > there", hover.plain())
        assertEquals(red, hover.styleOf("Go > there").color)
        assertEquals("it's", render("<hover:show_text:'it\\'s'>x").styleOf("x").hover?.plain())
        assertEquals("<hover:show_item:x>y", render("<hover:show_item:x>y").plain())
    }

    @Test
    fun gradientsColourEachCharacter() {
        val c = render("<gradient:#000000:#FFFFFF>abc</gradient>d")
        assertEquals(TextColor(0x000000), c.styleOf("a").color)
        assertEquals(TextColor(0x808080), c.styleOf("b").color)
        assertEquals(TextColor(0xFFFFFF), c.styleOf("c").color)
        assertNull(c.styleOf("d").color)
        assertEquals("abcd", c.plain())
    }

    @Test
    fun gradientsWithSeveralColoursAndColouredParts() {
        val c = render("<gradient:red:#00FF00:blue>abcde")
        assertEquals(red, c.styleOf("a").color)
        assertEquals(TextColor(0x00FF00), c.styleOf("c").color)
        assertEquals(TextColor.NAMED["blue"], c.styleOf("e").color)
        // A character with its own colour keeps it, and keeps its place along the gradient.
        val inner = render("<gradient:#000000:#FFFFFF>a<red>b</red>c")
        assertEquals(red, inner.styleOf("b").color)
        assertEquals(TextColor(0xFFFFFF), inner.styleOf("c").color)
        // The default, as MiniMessage's, is white to black.
        assertEquals(TextColor(0xFFFFFF), render("<gradient>ab").styleOf("a").color)
        assertEquals("<gradient:red:nope>x", render("<gradient:red:nope>x").plain())
    }

    @Test
    fun gradientsCountASurrogatePairAsOneCharacter() {
        val c = render("<gradient:#000000:#FFFFFF>a😀b")
        assertEquals(TextColor(0x808080), c.styleOf("😀").color)
    }

    @Test
    fun gradientsColourPlaceholdersToo() {
        val c = render("<gradient:#000000:#FFFFFF><player></gradient>", "player" to text("abc"))
        assertEquals(TextColor(0x808080), c.styleOf("b").color)
    }

    @Test
    fun theThemesTags() {
        val c = render("<prefix>Gave <highlight>Steve</highlight> <success>5</success>, not <error>6</error>")
        assertEquals("» Gave Steve 5, not 6", c.plain())
        assertEquals(purple, c.styleOf("»").color)
        assertEquals(purple, c.styleOf("Steve").color)
        assertEquals(green, c.styleOf("5").color)
        assertEquals(coral, c.styleOf("6").color)
        assertEquals(Style.EMPTY, c.styleOf("Gave "))
    }

    @Test
    fun aCallersTagWinsOverABuiltInOne() {
        val c = Markup.parse("<red>x</red>", mapOf("red" to MarkupTag.Styling(Style(bold = true)))).render()
        assertEquals(Style(bold = true), c.styleOf("x"))
    }

    @Test
    fun placeholdersAreFilledAsText() {
        val m = Markup.parse("<prefix>Sent <highlight><amount></highlight> to <player>.", theme)
        val c = m.render("amount" to text(5), "player" to text("<red>Steve"))
        assertEquals("» Sent 5 to <red>Steve.", c.plain())
        assertEquals(purple, c.styleOf("5").color)
        // A player's name can't add a style.
        assertEquals(Style.EMPTY, c.styleOf("<red>Steve"))
    }

    @Test
    fun placeholdersCanBeComponents() {
        val name = Markup.parse("<gold>Steve").render()
        val c = render("Hi <player>!", "player" to Placeholder.component(name))
        assertEquals("Hi Steve!", c.plain())
        assertEquals(TextColor.NAMED["gold"], c.styleOf("Steve").color)
    }

    @Test
    fun aPlaceholderLeftOutStaysAsWritten() {
        assertEquals("Hi <player>!", render("Hi <player>!").plain())
        assertEquals("<a>x</a>", render("<a>x</a>").plain())
    }

    @Test
    fun placeholdersInsideClicksAndHovers() {
        val c = render(
            "<click:run_command:'<command>'><hover:show_text:'Run <command>'><success>[Confirm]</success></hover></click>",
            "command" to text("/pay Steve 5"),
        )
        val style = c.styleOf("[Confirm]")
        assertEquals(ClickEvent(ClickEvent.Action.RUN_COMMAND, "/pay Steve 5"), style.click)
        assertEquals("Run /pay Steve 5", style.hover?.plain())
        assertEquals(green, style.color)
        assertEquals("/home <name>", render("<click:run_command:'/home <name>'>x").styleOf("x").click?.value)
    }

    @Test
    fun sectionsShowOrNot() {
        // ChatPages.FOOTER, today's paged-list footer.
        val footer = Markup.parse(
            "<gray>Page <page> of <pages></gray>" +
                "<has_previous> <click:run_command:'<previous>'><hover:show_text:'<previous>'><highlight>« Previous</highlight></hover></click></has_previous>" +
                "<has_next> <click:run_command:'<next>'><hover:show_text:'<next>'><highlight>Next »</highlight></hover></click></has_next>",
            theme,
        )
        val first = footer.render(
            "page" to text(1), "pages" to text(3), "next" to text("/homes 2"), "previous" to text("/homes 0"),
            "has_previous" to Placeholder.section(false), "has_next" to Placeholder.section(true),
        )
        assertEquals("Page 1 of 3 Next »", first.plain())
        assertEquals(gray, first.styleOf("Page ").color)
        assertEquals(ClickEvent(ClickEvent.Action.RUN_COMMAND, "/homes 2"), first.styleOf("Next »").click)
        assertEquals(purple, first.styleOf("Next »").color)
        val middle = footer.render(
            "page" to text(2), "pages" to text(3), "next" to text("/homes 3"), "previous" to text("/homes 1"),
            "has_previous" to Placeholder.section(true), "has_next" to Placeholder.section(true),
        )
        assertEquals("Page 2 of 3 « Previous Next »", middle.plain())
    }

    @Test
    fun aPlaceholderInsideAClosedTagDoesNotSwallowWhatFollows() {
        val c = render("<highlight><player></highlight> joined <red>now", "player" to text("Steve"))
        assertEquals("Steve joined now", c.plain())
        assertEquals(purple, c.styleOf("Steve").color)
        assertEquals(Style.EMPTY, c.styleOf(" joined "))
        assertEquals(red, c.styleOf("now").color)
    }

    @Test
    fun todaysUpdateNotice() {
        val c = render(
            "<prefix><plugin> <highlight><latest></highlight> is out (you have <current>): " +
                "<click:open_url:'<url>'><hover:show_text:'Open on Modrinth'><highlight><underlined><url>",
            "plugin" to text("xBans"), "latest" to text("2.0.0"), "current" to text("1.4.0"),
            "url" to text("https://modrinth.com/plugin/xbans"),
        )
        assertEquals("» xBans 2.0.0 is out (you have 1.4.0): https://modrinth.com/plugin/xbans", c.plain())
        val link = c.styleOf("https://modrinth.com/plugin/xbans")
        assertEquals(ClickEvent(ClickEvent.Action.OPEN_URL, "https://modrinth.com/plugin/xbans"), link.click)
        assertEquals(true, link.underlined)
        assertEquals(purple, link.color)
    }

    @Test
    fun aRenderWithoutPlaceholdersIsKept() {
        val m = Markup.parse("<red>hi")
        assertSame(m.render(), m.render())
        assertSame(m.render(), m.render(emptyMap()))
    }

    @Test
    fun neverThrowsOnJunk() {
        val fixed = listOf(
            "<", ">", "</>", "<<<>>>", "<click:>", "<hover:show_text:'", "\\", "<#zzzzzz>", "<color:>",
            "</>".repeat(50), "<a><b><c></a></b>", "<hover:show_text:'<hover:show_text:\"<hover:show_text:x>\">'>",
            "<gradient:>", "<gradient:red>x", "<reset></reset>", "<!>", "<:>", "<'>", "<a:'>",
        )
        val random = Random(120)
        val pieces = listOf(
            "<", ">", "/", "\\", "'", "\"", ":", "!", "red", "bold", "b", "click", "hover", "show_text",
            "run_command", "gradient", "#ff0000", "player", "highlight", "prefix", "reset", "x", " ",
        )
        val junk = fixed + List(3_000) {
            buildString { repeat(random.nextInt(1, 20)) { append(pieces[random.nextInt(pieces.size)]) } }
        }
        for (input in junk) {
            val c = Markup.parse(input, theme).render("player" to text("Steve"))
            // Everything shown came from the input or a placeholder, so it's never longer than both.
            assertTrue(c.all().all { it.text.length <= input.length + "Steve".length + 2 }, input)
        }
    }
}
