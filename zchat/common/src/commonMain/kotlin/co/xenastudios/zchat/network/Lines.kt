package co.xenastudios.zchat.network

import co.xenastudios.core.common.proxy.ProxyPayload
import co.xenastudios.core.common.text.ClickEvent
import co.xenastudios.core.common.text.Component
import co.xenastudios.core.common.text.Style
import co.xenastudios.core.common.text.TextColor

/**
 * A drawn chat line on the wire between servers: our [Component] as bytes, so the server a
 * message came from draws it once (its format, the sender's group and placeholders, which only it
 * knows) and every other server shows that same line. Depth and size are bounded: a frame is
 * signed by the network's secret, but a reader never trusts a length.
 */
internal object Lines {
    private const val MAX_DEPTH = 16

    fun write(out: ProxyPayload.Writer, line: Component, depth: Int = 0) {
        out.text(line.text)
        val style = line.style
        out.int(style.color?.rgb ?: -1)
        out.byte(flags(style.bold) or (flags(style.italic) shl 2) or (flags(style.underlined) shl 4) or (flags(style.strikethrough) shl 6))
        out.byte(flags(style.obfuscated))
        val click = style.click
        out.boolean(click != null)
        if (click != null) {
            out.int(click.action.ordinal)
            out.text(click.value)
        }
        val hover = if (depth < MAX_DEPTH) style.hover else null
        out.boolean(hover != null)
        if (hover != null) write(out, hover, depth + 1)
        val children = if (depth < MAX_DEPTH) line.children else emptyList()
        out.int(children.size)
        for (child in children) write(out, child, depth + 1)
    }

    fun read(input: ProxyPayload.Reader, depth: Int = 0): Component {
        if (depth > MAX_DEPTH) throw IllegalArgumentException("a line nested too deep")
        val text = input.text()
        val rgb = input.int()
        val decorations = input.byte()
        val obfuscated = input.byte()
        val click = if (input.boolean()) {
            val action = ClickEvent.Action.entries.getOrNull(input.int()) ?: throw IllegalArgumentException("no such click")
            ClickEvent(action, input.text())
        } else {
            null
        }
        val hover = if (input.boolean()) read(input, depth + 1) else null
        val count = input.int()
        if (count !in 0..512) throw IllegalArgumentException("a line with $count parts")
        val children = List(count) { read(input, depth + 1) }
        val style = Style(
            color = if (rgb < 0) null else TextColor(rgb),
            bold = flag(decorations),
            italic = flag(decorations shr 2),
            underlined = flag(decorations shr 4),
            strikethrough = flag(decorations shr 6),
            obfuscated = flag(obfuscated),
            click = click,
            hover = hover,
        )
        return Component(text, style, children)
    }

    // A tri-state in two bits: unset, off, on.
    private fun flags(value: Boolean?): Int = when (value) {
        null -> 0
        false -> 1
        true -> 2
    }

    private fun flag(bits: Int): Boolean? = when (bits and 3) {
        1 -> false
        2 -> true
        else -> null
    }
}
