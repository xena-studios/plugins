package co.xenastudios.core.neoforge

import co.xenastudios.core.common.text.ClickEvent
import co.xenastudios.core.common.text.Component
import co.xenastudios.core.common.text.Style
import co.xenastudios.core.common.text.TextColor
import net.minecraft.ChatFormatting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.minecraft.network.chat.TextColor as McColor

class MinecraftRendererTest {
    @Test
    fun `text, children and styles become Minecraft's`() {
        val drawn = MinecraftRenderer.draw(
            Component(
                "Hello, ",
                Style(color = TextColor(0xB48CFF), bold = true),
                listOf(Component("Steve", Style(color = TextColor.NAMED.getValue("red"), bold = false, underlined = true))),
            ),
        )
        assertEquals("Hello, Steve", drawn.string)
        assertEquals(0xB48CFF, drawn.style.color?.value)
        assertTrue(drawn.style.isBold)
        val child = drawn.siblings.single()
        assertEquals(McColor.fromLegacyFormat(ChatFormatting.RED), child.style.color)
        assertFalse(child.style.isBold)
        assertTrue(child.style.isUnderlined)
    }

    // On 1.20.5 to 1.21.4 a hover event reaches Minecraft's registries, which need the game
    // bootstrapped; platform-fabric's 1.20.1 line tests the same code without records.
    //? if click_records
    @Test
    fun `clicks and hovers are kept`() {
        val drawn = MinecraftRenderer.draw(
            Component("click", Style(click = ClickEvent(ClickEvent.Action.RUN_COMMAND, "/xsample"), hover = Component.text("Runs it"))),
        )
        assertNotNull(drawn.style.clickEvent)
        assertNotNull(drawn.style.hoverEvent)
        assertEquals(0xFF5555, MinecraftRenderer.color(TextColor.NAMED.getValue("red")).value)
    }

    @Test
    fun `an unstyled component has no style`() {
        val drawn = MinecraftRenderer.draw(Component.text("plain"))
        assertTrue(drawn.style.isEmpty)
        assertNull(drawn.style.color)
    }
}
