package co.xenastudios.core.fuzz

import co.xenastudios.core.common.text.Component
import co.xenastudios.core.common.text.LegacyRenderer
import co.xenastudios.core.common.text.Markup
import co.xenastudios.core.common.text.MarkupTag
import co.xenastudios.core.common.text.Placeholder
import co.xenastudios.core.common.text.PlainRenderer
import co.xenastudios.core.common.text.Style
import co.xenastudios.core.common.text.TextColor
import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.FuzzTest
import kotlin.test.assertEquals

/**
 * The markup parser (core-common's `Markup`), which reads every message in every config and
 * language file: whatever an owner writes, parsing and rendering never throw (its contract),
 * and escaped text comes back as it was.
 */
class MarkupFuzzTest {

    private val tags = mapOf(
        "highlight" to MarkupTag.Styling(Style(color = TextColor(0xB48CFF))),
        "prefix" to MarkupTag.Inserting(Component("[x] ", Style(bold = true))),
    )

    /** Any message, with the theme's tags and some placeholders, parsed and drawn every way the JVM draws one. */
    @FuzzTest
    fun parseAndRender(data: FuzzedDataProvider) {
        val shown = data.consumeBoolean()
        val player = data.consumeString(32)
        val input = data.consumeRemainingAsString()
        val markup = Markup.parse(input, tags)
        draw(markup.render())
        draw(
            markup.render(
                "player" to Placeholder.text(player),
                "has_next" to Placeholder.section(shown),
                "name" to Placeholder.component(Component(player, Style(italic = true))),
            ),
        )
    }

    /** A player's text, escaped, is shown exactly as typed: no tag in it does anything. */
    @FuzzTest
    fun escapeRoundTrips(data: FuzzedDataProvider) {
        val text = data.consumeRemainingAsString()
        val shown = Markup.parse(Markup.escape(text), tags).render()
        assertEquals(text, shown.plain())
        assertEquals(text, PlainRenderer.draw(shown))
    }

    private fun draw(component: Component) {
        component.plain()
        PlainRenderer.draw(component)
        LegacyRenderer(hex = false).draw(component)
        LegacyRenderer(hex = true).draw(component)
    }
}
