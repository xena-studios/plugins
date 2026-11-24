package co.xenastudios.core.common.i18n

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.testing.TestYaml
import co.xenastudios.core.common.text.Component
import co.xenastudios.core.common.text.PlainRenderer
import co.xenastudios.core.common.text.Placeholder
import co.xenastudios.core.common.text.Style
import co.xenastudios.core.common.text.TextColor
import co.xenastudios.core.common.text.TextRenderer
import co.xenastudios.core.common.text.Theme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class TranslationsTest {

    private fun shipped(tag: LanguageTag, text: String): ShippedLanguage =
        (LangFiles.readShipped(tag, text, TestYaml) as Outcome.Ok).value

    private fun messages(tag: LanguageTag, shipped: ShippedLanguage?): Map<String, String> =
        (LangFiles.load(CATALOG, tag, shipped, null, TestYaml) as Outcome.Ok).value.messages

    private val files: Map<LanguageTag, Map<String, String>> = mapOf(
        LanguageTag.ENGLISH to messages(LanguageTag.ENGLISH, null),
        DE to messages(DE, shipped(DE, SHIPPED_DE)),
        PT_BR to messages(PT_BR, shipped(PT_BR, SHIPPED_PT_BR)),
    )

    private val plain = Theme(prefix = "» ", base = Style.EMPTY)

    private fun translations(settings: LanguageSettings = LanguageSettings.DEFAULT, theme: Theme = plain) =
        Translations.of(CATALOG, files, settings, theme)

    private fun text(t: Translations, key: MessageKey, locale: String?, vararg placeholders: Pair<String, Placeholder>) =
        t.render(key, t.languageFor(locale), placeholders.toMap())?.plain()

    @Test
    fun aReaderGetsTheClosestLanguageThenTheDefault() {
        val t = translations()
        assertEquals(PT_BR, t.languageFor("pt_br").tag)
        assertEquals(PT_BR, t.languageFor("pt_BR").tag)
        assertEquals(DE, t.languageFor("de_at").tag, "region, then language")
        assertEquals(DE, t.languageFor("de").tag)
        assertEquals(LanguageTag.ENGLISH, t.languageFor("pt_pt").tag, "pt-PT has no file, and neither has pt")
        assertEquals(LanguageTag.ENGLISH, t.languageFor("en_us").tag)
        assertEquals(LanguageTag.ENGLISH, t.languageFor("??").tag, "nonsense from a client")
        assertEquals(LanguageTag.ENGLISH, t.languageFor(null).tag, "the console")
    }

    @Test
    fun aPlainLanguageFileServesItsRegions() {
        val t = Translations.of(CATALOG, files + (PT to files.getValue(PT_BR)))
        assertEquals(PT, t.languageFor("pt_pt").tag)
        assertEquals(PT_BR, t.languageFor("pt_br").tag)
    }

    @Test
    fun theDefaultLanguageIsForTheConsoleAndLanguagesWithNoFile() {
        val t = translations(LanguageSettings(default = DE))
        assertEquals(DE, t.languageFor(null).tag)
        assertEquals(DE, t.languageFor("fr_fr").tag)
        assertEquals(PT_BR, t.languageFor("pt_br").tag)
        assertEquals(emptyList(), t.problems)
    }

    @Test
    fun aFixedLanguageIsForEveryPlayerButNotTheConsole() {
        val t = translations(LanguageSettings(LanguageChoice.Fixed(DE), default = LanguageTag.ENGLISH))
        assertEquals(DE, t.languageFor("pt_br").tag)
        assertEquals(LanguageTag.ENGLISH, t.languageFor(null).tag)
        val region = translations(LanguageSettings(LanguageChoice.Fixed(LanguageTag.of("de-CH"))))
        assertEquals(DE, region.languageFor("en_us").tag, "de-CH falls back to de")
    }

    @Test
    fun aLanguageWithNoFileIsReportedAndEnglishUsed() {
        val t = translations(LanguageSettings(LanguageChoice.Fixed(LanguageTag.of("fr")), default = LanguageTag.of("it")))
        assertEquals(LanguageTag.ENGLISH, t.default.tag)
        assertEquals(LanguageTag.ENGLISH, t.languageFor("de_de").tag)
        assertEquals(listOf(LangProblem.Kind.NO_SUCH_LANGUAGE, LangProblem.Kind.NO_SUCH_LANGUAGE), t.problems.map { it.kind })
    }

    @Test
    fun messagesAreFilledInEachLanguageWithTheTheme() {
        val t = translations()
        val name = "name" to Placeholder.text("Base")
        assertEquals("» Home Base set.", text(t, HomesMessages.set, "en_us", name))
        assertEquals("» Zuhause Base gesetzt.", text(t, HomesMessages.set, "de_de", name))
        assertEquals("» Casa Base definida.", text(t, HomesMessages.set, "pt_br", name))
        assertEquals("» You have 3 homes.", text(t, HomesMessages.list, "pt_br", "count" to Placeholder.text(3)), "pt-BR lacks it: English")
    }

    @Test
    fun aMissingMessageComesFromTheDefaultLanguageThenEnglish() {
        val partial = mapOf(LanguageTag.ENGLISH to files.getValue(LanguageTag.ENGLISH), DE to files.getValue(DE), PT_BR to mapOf("homes.set" to "x"))
        val t = Translations.of(CATALOG, partial, LanguageSettings(default = DE), plain)
        assertEquals("<error>Du hast kein Zuhause.", t.text(HomesMessages.none, t.language(PT_BR)!!))
    }

    @Test
    fun theThemeIsAppliedOnceForEveryLanguage() {
        val gold = TextColor.NAMED.getValue("gold")
        val theme = Theme(prefix = "<highlight>X</highlight> ", base = Style(color = TextColor.NAMED.getValue("gray")), highlight = Style(color = gold))
        val t = translations(theme = theme)
        for (locale in listOf("en_us", "de_de")) {
            val component = t.render(HomesMessages.none, t.languageFor(locale))!!
            assertEquals(Style(color = TextColor.NAMED.getValue("gray")), component.style, "the base colour wraps it")
        }
        val set = t.render(HomesMessages.set, t.languageFor("de_de"), "name" to Placeholder.text("A"))!!
        assertTrue(set.toString().contains("color=gold"), "the prefix and <highlight> use the theme: $set")
    }

    @Test
    fun aMessageIsParsedOnceAndItsPlainRenderKept() {
        val t = translations()
        val de = t.languageFor("de_de")
        assertSame(t.markup(HomesMessages.none, de), t.markup(HomesMessages.none, de))
        assertSame(t.render(HomesMessages.none, de), t.render(HomesMessages.none, de))
    }

    @Test
    fun aBlankMessageRendersToNothing() {
        val t = translations()
        assertNull(t.render(HomesMessages.hidden, t.default))
    }

    @Test
    fun aMessageFromAnotherCatalogIsRefused() {
        val other = Translations.of(MessageCatalog.of(CoreMessages), emptyMap())
        val error = runCatching { other.render(HomesMessages.none, other.default) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException, "$error")
    }

    // A renderer that counts its draws.
    private class Counting : TextRenderer<String> {
        var draws = 0
        override fun draw(component: Component): String {
            draws++
            return component.plain()
        }
    }

    // Players by name, each with the locale their client sent, and the names back for the
    // tests' messages; "console" is the console, which has no reference.
    private val locales = HashMap<PlayerRef, String?>()
    private val names = HashMap<PlayerRef?, String>().apply { put(null, "console") }

    private fun viewer(name: String, locale: String?): PlayerRef? {
        if (name == "console") return null
        val player = PlayerRef.of(Uuid.fromLongs(name.hashCode().toLong(), name.length.toLong()))
        locales[player] = locale
        names[player] = name
        return player
    }

    private val clients = object : LanguageSource {
        override fun clientLocale(player: PlayerRef): String? = locales[player]
    }

    @Test
    fun aMessageWithNoPlaceholdersIsDrawnOncePerLanguage() {
        val renderer = Counting()
        val drawer = MessageDrawer(translations(), clients, renderer)
        val a = viewer("a", "de_de")
        val first = drawer.draw(a, HomesMessages.none)
        assertSame(first, drawer.draw(a, HomesMessages.none))
        assertSame(first, drawer.draw(viewer("b", "de_at"), HomesMessages.none))
        assertEquals(1, renderer.draws)
        assertNull(drawer.draw(a, HomesMessages.hidden))
        assertNull(drawer.draw(a, HomesMessages.hidden))
        assertEquals(1, renderer.draws, "a blank message isn't drawn")
    }

    @Test
    fun aBroadcastIsDrawnOncePerLanguageAndTheConsoleGetsTheDefault() {
        val renderer = Counting()
        val drawer = MessageDrawer(translations(LanguageSettings(default = DE)), clients, renderer)
        val viewers = listOf(
            viewer("en1", "en_us"), viewer("de1", "de_de"), viewer("en2", "en_gb"),
            viewer("pt", "pt_br"), viewer("de2", "de_ch"), null,
        )
        val sent = mutableListOf<Pair<String, String>>()
        val draws = drawer.broadcast(
            viewers, HomesMessages.list,
            LanguagePlaceholders { language -> mapOf("count" to Placeholder.text(language.formats.number(12345))) },
        ) { viewer, text -> sent += names.getValue(viewer) to text }
        assertEquals(3, draws, "en, de (with the console) and pt-BR")
        assertEquals(3, renderer.draws)
        assertEquals(
            listOf(
                "en1" to "» You have 12,345 homes.",
                "en2" to "» You have 12,345 homes.",
                "de1" to "» Du hast 12.345 Zuhause.",
                "de2" to "» Du hast 12.345 Zuhause.",
                "console" to "» Du hast 12.345 Zuhause.",
                "pt" to "» You have 12.345 homes.",
            ),
            sent,
        )
    }

    @Test
    fun aBlankBroadcastSendsNothing() {
        val drawer = MessageDrawer(translations(), clients, PlainRenderer)
        var sent = 0
        drawer.broadcast(listOf(viewer("a", "en_us")), HomesMessages.hidden, emptyMap()) { _, _ -> sent++ }
        assertEquals(0, sent)
        assertEquals(false, drawer.send(viewer("a", "en_us"), HomesMessages.hidden, emptyMap()) { _, _ -> sent++ })
    }

    @Test
    fun placeholdersAreFilledInsideAClick() {
        val drawer = MessageDrawer(translations(), clients, object : TextRenderer<Component> {
            override fun draw(component: Component): Component = component
        })
        val drawn = assertNotNull(drawer.draw(viewer("a", "de_de"), HomesMessages.teleport, mapOf("name" to Placeholder.text("Base"))))
        assertTrue("/home Base" in drawn.toString(), drawn.toString())
    }
}
