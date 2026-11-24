package co.xenastudios.core.jvm.text

import co.xenastudios.core.common.text.ClickEvent
import co.xenastudios.core.common.text.Component
import co.xenastudios.core.common.text.LegacyRenderer
import co.xenastudios.core.common.text.Style
import co.xenastudios.core.common.text.TextColor
import co.xenastudios.core.common.text.TextRenderer
import net.md_5.bungee.api.ChatColor
import net.md_5.bungee.api.chat.BaseComponent
import net.md_5.bungee.api.chat.HoverEvent
import net.md_5.bungee.api.chat.TextComponent
import net.md_5.bungee.api.chat.ClickEvent as BungeeClick

/**
 * Draws our components to the `BaseComponent[]` Spigot sends with `player.spigot()
 * .sendMessage(...)`, on every Spigot from 1.8.8, where Adventure isn't there. Drawn once
 * per language and kept, a send costs about 0.07 µs on 1.8.8, against 8 µs through
 * `adventure-platform` (M0). On 1.21, where Spigot serializes on the main thread, a kept
 * legacy string ([LegacyRenderer]) sends faster; the platform picks.
 *
 * It uses only what Spigot 1.8.8's chat API has (a test reads its class files to make sure),
 * except behind the two switches the platform sets once at startup:
 * - [hex] (1.16+): a colour that isn't one of the sixteen is sent as RGB; without it, as the
 *   nearest of the sixteen;
 * - [copyToClipboard] (1.15+): a copy click copies; without it, it suggests the text.
 *
 * The drawn arrays are shared by every send: don't change one.
 */
public class BungeeRenderer(
    private val hex: Boolean = false,
    private val copyToClipboard: Boolean = false,
) : TextRenderer<Array<BaseComponent>> {

    override fun draw(component: Component): Array<BaseComponent> = arrayOf(component(component))

    private fun component(component: Component): BaseComponent {
        val out = TextComponent(component.text)
        if (!component.style.isEmpty()) style(out, component.style)
        for (child in component.children) out.addExtra(component(child))
        return out
    }

    private fun style(out: BaseComponent, style: Style) {
        style.color?.let { out.setColor(color(it)) }
        style.bold?.let { out.setBold(it) }
        style.italic?.let { out.setItalic(it) }
        style.underlined?.let { out.setUnderlined(it) }
        style.strikethrough?.let { out.setStrikethrough(it) }
        style.obfuscated?.let { out.setObfuscated(it) }
        style.click?.let { out.setClickEvent(click(it)) }
        style.hover?.let { out.setHoverEvent(hover(it)) }
    }

    /** Our colour as a [ChatColor]: one of the sixteen, or RGB with [hex]. */
    public fun color(color: TextColor): ChatColor {
        NAMED[color.rgb]?.let { return it }
        // ChatColor.of arrived in 1.16; only called when the platform says it's there.
        return if (hex) ChatColor.of(color.hex()) else NAMED.getValue(LegacyRenderer.nearest(color).rgb)
    }

    private fun click(click: ClickEvent): BungeeClick {
        val action = when (click.action) {
            ClickEvent.Action.RUN_COMMAND -> BungeeClick.Action.RUN_COMMAND
            ClickEvent.Action.SUGGEST_COMMAND -> BungeeClick.Action.SUGGEST_COMMAND
            ClickEvent.Action.OPEN_URL -> BungeeClick.Action.OPEN_URL
            // COPY_TO_CLIPBOARD arrived in 1.15.
            ClickEvent.Action.COPY_TO_CLIPBOARD -> if (copyToClipboard) BungeeClick.Action.COPY_TO_CLIPBOARD else BungeeClick.Action.SUGGEST_COMMAND
        }
        return BungeeClick(action, click.value)
    }

    // The constructor 1.8.8 has; 1.16 deprecated it for content objects 1.8.8 lacks.
    @Suppress("DEPRECATION")
    private fun hover(hover: Component): HoverEvent = HoverEvent(HoverEvent.Action.SHOW_TEXT, draw(hover))

    private companion object {
        // Minecraft's sixteen, by RGB: the static fields every version has.
        val NAMED: Map<Int, ChatColor> = mapOf(
            0x000000 to ChatColor.BLACK, 0x0000AA to ChatColor.DARK_BLUE, 0x00AA00 to ChatColor.DARK_GREEN,
            0x00AAAA to ChatColor.DARK_AQUA, 0xAA0000 to ChatColor.DARK_RED, 0xAA00AA to ChatColor.DARK_PURPLE,
            0xFFAA00 to ChatColor.GOLD, 0xAAAAAA to ChatColor.GRAY, 0x555555 to ChatColor.DARK_GRAY,
            0x5555FF to ChatColor.BLUE, 0x55FF55 to ChatColor.GREEN, 0x55FFFF to ChatColor.AQUA,
            0xFF5555 to ChatColor.RED, 0xFF55FF to ChatColor.LIGHT_PURPLE, 0xFFFF55 to ChatColor.YELLOW,
            0xFFFFFF to ChatColor.WHITE,
        )
    }
}
