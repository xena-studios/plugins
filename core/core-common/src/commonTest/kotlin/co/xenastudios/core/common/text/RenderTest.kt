package co.xenastudios.core.common.text

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.i18n.ChatPages
import co.xenastudios.core.common.i18n.CoreMessages
import co.xenastudios.core.common.i18n.LanguageTag
import co.xenastudios.core.common.i18n.MessageCatalog
import co.xenastudios.core.common.i18n.Translations
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LegacyRendererTest {

    private val legacy = LegacyRenderer()
    private fun draw(markup: String, renderer: LegacyRenderer = legacy) = renderer.draw(Markup.parse(markup).render())

    @Test
    fun coloursAndDecorationsBecomeSectionCodes() {
        assertEquals("§cHi", draw("<red>Hi"))
        assertEquals("§c§lHi", draw("<red><bold>Hi"))
        assertEquals("§cA§9B", draw("<red>A</red><blue>B"))
        assertEquals("plain", draw("plain"))
        assertEquals("§lA§rB", draw("<bold>A</bold>B"), "a dropped decoration starts over")
        assertEquals("§cA§lB", draw("<red>A<bold>B"), "an added one is just added")
        assertEquals("§c§lA§cB", draw("<red><bold>A</bold>B"))
    }

    @Test
    fun hexColoursAreTheNearestNamedOneOrHexOnNewServers() {
        assertEquals("§dX", draw("<#FF66FF>X"))
        assertEquals("§x§b§4§8§c§f§fX", draw("<#B48CFF>X", LegacyRenderer(hex = true)))
        assertEquals("§cX", draw("<#FF5555>X", LegacyRenderer(hex = true)), "a named colour keeps its code")
    }

    @Test
    fun clicksAndHoversAreDropped() {
        assertEquals("§aGo", draw("<click:run_command:'/x'><hover:show_text:'tip'><green>Go"))
    }

    @Test
    fun plainDropsEverything() {
        assertEquals("Go there", PlainRenderer.draw(Markup.parse("<red>Go <bold>there").render()))
    }
}

class ThemeTest {

    @Test
    fun stylesAreReadFromTags() {
        assertEquals(Style(color = TextColor.NAMED.getValue("gray")), assertIs<Outcome.Ok<Style>>(Theme.style("<gray>")).value)
        assertEquals(Style(color = TextColor.NAMED.getValue("gold"), bold = true), assertIs<Outcome.Ok<Style>>(Theme.style("gold bold")).value)
        assertEquals(Style(color = TextColor(0xB48CFF)), assertIs<Outcome.Ok<Style>>(Theme.style("<#B48CFF>")).value)
        assertEquals(Style.EMPTY, assertIs<Outcome.Ok<Style>>(Theme.style("  ")).value)
    }

    @Test
    fun anythingButColoursAndDecorationsIsRefused() {
        assertEquals("isn't a colour or style, such as <gray> or <#B48CFF><bold>", assertIs<Outcome.Err<String>>(Theme.style("<grey2>")).error)
        assertEquals("can't be a gradient; put those in the prefix or a message instead", assertIs<Outcome.Err<String>>(Theme.style("<gradient:red:blue>")).error)
        assertEquals("can only be colours and decorations, not clicks or hovers", assertIs<Outcome.Err<String>>(Theme.style("<click:run_command:'/x'>")).error)
    }

    @Test
    fun thePrefixUsesTheThemesStyles() {
        val theme = Theme(prefix = "<highlight>S</highlight> ", highlight = Style(color = TextColor(0x123456)))
        val prefix = assertIs<MarkupTag.Inserting>(theme.tags["prefix"]).component
        assertEquals("S ", prefix.plain())
        assertEquals("§x§1§2§3§4§5§6S§r ", LegacyRenderer(hex = true).draw(prefix))
        assertEquals(Component.EMPTY, assertIs<MarkupTag.Inserting>(Theme(prefix = "").tags["prefix"]).component)
    }
}

class ChatPagesTest {

    @Test
    fun pagesAreCountedAndClamped() {
        assertEquals(1, ChatPages.pages(0, 10))
        assertEquals(3, ChatPages.pages(21, 10))
        assertEquals(20L, ChatPages.offset(3, 10))
        val page = ChatPages.of((1..25).toList(), 9, 10)
        assertEquals(3, page.number)
        assertEquals(listOf(21, 22, 23, 24, 25), page.items)
        assertEquals(false, page.hasNext)
        assertEquals(true, page.hasPrevious)
    }

    @Test
    fun theFooterIsACoreMessageInTheReadersLanguage() {
        val t = Translations.of(
            MessageCatalog.of(CoreMessages),
            mapOf(LanguageTag.of("de") to mapOf(CoreMessages.pagesFooter.key to "Seite <page> von <pages><has_next> <click:run_command:'<next>'>Weiter</click></has_next>")),
        )
        val de = t.language(LanguageTag.of("de"))!!
        val first = ChatPages.of((1..2500).toList(), 1, 1)
        assertEquals("Seite 1 von 2.500 Weiter", t.render(CoreMessages.pagesFooter, de, ChatPages.footer(first, "/homes {page}", de))!!.plain())
        val last = ChatPages.of((1..2).toList(), 2, 1)
        assertEquals("Seite 2 von 2", t.render(CoreMessages.pagesFooter, de, ChatPages.footer(last, "/homes {page}", de))!!.plain())
        val english = t.render(CoreMessages.pagesFooter, t.default, ChatPages.footer(ChatPages.of((1..3).toList(), 2, 1), "/homes {page}", t.default))!!
        assertEquals("Page 2 of 3 « Previous Next »", english.plain())
        assertEquals(true, "/homes 3" in english.toString())
    }
}
