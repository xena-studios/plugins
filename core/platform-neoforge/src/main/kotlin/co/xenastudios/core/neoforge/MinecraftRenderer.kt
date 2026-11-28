package co.xenastudios.core.neoforge

import co.xenastudios.core.common.text.ClickEvent
import co.xenastudios.core.common.text.Component
import co.xenastudios.core.common.text.Style
import co.xenastudios.core.common.text.TextColor
import co.xenastudios.core.common.text.TextRenderer
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.ClickEvent as McClick
import net.minecraft.network.chat.Component as McComponent
import net.minecraft.network.chat.HoverEvent as McHover
import net.minecraft.network.chat.Style as McStyle
import net.minecraft.network.chat.TextColor as McColor
//? if click_records
import java.net.URI

/**
 * Draws our components to Minecraft's own text, which a Fabric server sends as it is: no
 * Adventure and no `adventure-platform-fabric`, which is about 1 MB a jar and pinned to one
 * Minecraft version a release (DECISIONS.md, M0).
 *
 * A named colour stays one of Minecraft's sixteen, so it shows the same on every client; any
 * other colour is RGB. A URL that isn't one (which 1.21.5 and later refuse to build a click
 * for) drops the click and keeps the text.
 *
 * It draws; [co.xenastudios.core.common.i18n.MessageDrawer] keeps what's drawn for messages
 * without placeholders, so a send is a lookup. Minecraft's components are immutable once sent,
 * so one drawn text can go to every player.
 */
public object MinecraftRenderer : TextRenderer<McComponent> {

    private val NAMED: Map<Int, McColor> = listOf(
        ChatFormatting.BLACK, ChatFormatting.DARK_BLUE, ChatFormatting.DARK_GREEN, ChatFormatting.DARK_AQUA,
        ChatFormatting.DARK_RED, ChatFormatting.DARK_PURPLE, ChatFormatting.GOLD, ChatFormatting.GRAY,
        ChatFormatting.DARK_GRAY, ChatFormatting.BLUE, ChatFormatting.GREEN, ChatFormatting.AQUA,
        ChatFormatting.RED, ChatFormatting.LIGHT_PURPLE, ChatFormatting.YELLOW, ChatFormatting.WHITE,
    ).mapNotNull { McColor.fromLegacyFormat(it) }.associateBy { it.value }

    override fun draw(component: Component): McComponent {
        val out: MutableComponent = McComponent.literal(component.text)
        if (!component.style.isEmpty()) out.setStyle(style(component.style))
        for (child in component.children) out.append(draw(child))
        return out
    }

    /** Our colour as Minecraft's: named where it's one of the sixteen. */
    public fun color(color: TextColor): McColor = NAMED[color.rgb] ?: McColor.fromRgb(color.rgb)

    private fun style(style: Style): McStyle {
        var out = McStyle.EMPTY
        style.color?.let { out = out.withColor(color(it)) }
        style.bold?.let { out = out.withBold(it) }
        style.italic?.let { out = out.withItalic(it) }
        style.underlined?.let { out = out.withUnderlined(it) }
        style.strikethrough?.let { out = out.withStrikethrough(it) }
        style.obfuscated?.let { out = out.withObfuscated(it) }
        style.click?.let { click -> click(click)?.let { out = out.withClickEvent(it) } }
        style.hover?.let { out = out.withHoverEvent(hover(draw(it))) }
        return out
    }

    //? if click_records {
    private fun click(click: ClickEvent): McClick? = when (click.action) {
        ClickEvent.Action.RUN_COMMAND -> McClick.RunCommand(click.value)
        ClickEvent.Action.SUGGEST_COMMAND -> McClick.SuggestCommand(click.value)
        ClickEvent.Action.COPY_TO_CLIPBOARD -> McClick.CopyToClipboard(click.value)
        ClickEvent.Action.OPEN_URL -> try {
            McClick.OpenUrl(URI(click.value))
        } catch (_: Exception) {
            null
        }
    }

    private fun hover(text: McComponent): McHover = McHover.ShowText(text)
    //?} else {
    /*private fun click(click: ClickEvent): McClick? = when (click.action) {
        ClickEvent.Action.RUN_COMMAND -> McClick(McClick.Action.RUN_COMMAND, click.value)
        ClickEvent.Action.SUGGEST_COMMAND -> McClick(McClick.Action.SUGGEST_COMMAND, click.value)
        ClickEvent.Action.COPY_TO_CLIPBOARD -> McClick(McClick.Action.COPY_TO_CLIPBOARD, click.value)
        ClickEvent.Action.OPEN_URL -> McClick(McClick.Action.OPEN_URL, click.value)
    }

    private fun hover(text: McComponent): McHover = McHover(McHover.Action.SHOW_TEXT, text)
    *///?}
}
