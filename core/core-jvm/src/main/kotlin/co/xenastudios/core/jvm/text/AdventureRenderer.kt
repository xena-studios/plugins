package co.xenastudios.core.jvm.text

import co.xenastudios.core.common.text.ClickEvent
import co.xenastudios.core.common.text.Component
import co.xenastudios.core.common.text.Style
import co.xenastudios.core.common.text.TextColor
import co.xenastudios.core.common.text.TextRenderer
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.Component as AdventureComponent
import net.kyori.adventure.text.event.ClickEvent as AdventureClick
import net.kyori.adventure.text.event.HoverEvent as AdventureHover
import net.kyori.adventure.text.format.Style as AdventureStyle
import net.kyori.adventure.text.format.TextColor as AdventureColor

/**
 * Draws our components to Adventure's, for the servers that ship it: Paper and Folia (from
 * 1.16.5), Velocity, Sponge, and PandaSpigot. The server's own Adventure is used (never
 * shaded, never `adventure-platform`), so this is compiled against Adventure 4.7.0, the
 * one Paper 1.16.5 bundles, and calls nothing newer; it ran unchanged against Adventure 5.2
 * on Folia 26.3 in M0.
 *
 * A named colour becomes Adventure's [NamedTextColor], so servers can downsample for old
 * clients the way they always have; any other colour is RGB.
 *
 * It draws; [co.xenastudios.core.common.i18n.MessageDrawer] keeps what's drawn for messages
 * without placeholders, so a send on Paper is the server's own ~0.4 µs (M0).
 */
public object AdventureRenderer : TextRenderer<AdventureComponent> {

    private val NAMED: Map<Int, NamedTextColor> = listOf(
        NamedTextColor.BLACK, NamedTextColor.DARK_BLUE, NamedTextColor.DARK_GREEN, NamedTextColor.DARK_AQUA,
        NamedTextColor.DARK_RED, NamedTextColor.DARK_PURPLE, NamedTextColor.GOLD, NamedTextColor.GRAY,
        NamedTextColor.DARK_GRAY, NamedTextColor.BLUE, NamedTextColor.GREEN, NamedTextColor.AQUA,
        NamedTextColor.RED, NamedTextColor.LIGHT_PURPLE, NamedTextColor.YELLOW, NamedTextColor.WHITE,
    ).associateBy { it.value() }

    override fun draw(component: Component): AdventureComponent {
        val builder = AdventureComponent.text().content(component.text)
        if (!component.style.isEmpty()) builder.style(style(component.style))
        for (child in component.children) builder.append(draw(child))
        return builder.build()
    }

    /** Our colour as Adventure's: named where it's one of the sixteen. */
    public fun color(color: TextColor): AdventureColor = NAMED[color.rgb] ?: AdventureColor.color(color.rgb)

    private fun style(style: Style): AdventureStyle {
        val builder = AdventureStyle.style()
        style.color?.let { builder.color(color(it)) }
        decoration(builder, TextDecoration.BOLD, style.bold)
        decoration(builder, TextDecoration.ITALIC, style.italic)
        decoration(builder, TextDecoration.UNDERLINED, style.underlined)
        decoration(builder, TextDecoration.STRIKETHROUGH, style.strikethrough)
        decoration(builder, TextDecoration.OBFUSCATED, style.obfuscated)
        style.click?.let { builder.clickEvent(click(it)) }
        style.hover?.let { builder.hoverEvent(AdventureHover.showText(draw(it))) }
        return builder.build()
    }

    private fun decoration(builder: AdventureStyle.Builder, decoration: TextDecoration, on: Boolean?) {
        if (on != null) builder.decoration(decoration, if (on) TextDecoration.State.TRUE else TextDecoration.State.FALSE)
    }

    private fun click(click: ClickEvent): AdventureClick = when (click.action) {
        ClickEvent.Action.RUN_COMMAND -> AdventureClick.runCommand(click.value)
        ClickEvent.Action.SUGGEST_COMMAND -> AdventureClick.suggestCommand(click.value)
        ClickEvent.Action.OPEN_URL -> AdventureClick.openUrl(click.value)
        ClickEvent.Action.COPY_TO_CLIPBOARD -> AdventureClick.copyToClipboard(click.value)
    }
}
