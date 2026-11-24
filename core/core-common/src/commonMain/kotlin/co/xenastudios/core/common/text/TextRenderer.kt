package co.xenastudios.core.common.text

/**
 * Draws our [Component]s to a server's own text, picked once at startup (DECISIONS.md, M0):
 * Adventure's components on Paper, Folia, Velocity and Sponge, Bukkit's `BaseComponent[]` on
 * Spigot (both in `core-jvm`), Minecraft's own text on the mod loaders, Pumpkin's on Pumpkin,
 * and [LegacyRenderer]'s `§` strings where nothing else will do.
 *
 * A renderer draws; it doesn't cache. Messages with no placeholders are drawn once per
 * language and kept by [co.xenastudios.core.common.i18n.MessageDrawer], so a send is a lookup.
 */
public fun interface TextRenderer<out T> {
    /** [component] in the server's own form. Never throws. */
    public fun draw(component: Component): T
}

/** The text with every style gone: for logs, and consoles without colour. */
public object PlainRenderer : TextRenderer<String> {
    override fun draw(component: Component): String = component.plain()
}

/**
 * The legacy `§` codes every server since 1.8 reads, for places that take nothing else
 * (old scoreboards, a kick screen before 1.16, a console). Clicks and hovers are dropped.
 *
 * With [hex] (1.16 and later), a colour that isn't one of the sixteen is written as
 * `§x§r§r§g§g§b§b`; without it, as the nearest of the sixteen.
 */
public class LegacyRenderer(private val hex: Boolean = false) : TextRenderer<String> {

    override fun draw(component: Component): String {
        val out = StringBuilder()
        val state = State()
        walk(component, Style.EMPTY, out, state)
        return out.toString()
    }

    private class State {
        var color: TextColor? = null
        var decorations: Int = 0
        var started = false
    }

    private fun walk(component: Component, inherited: Style, out: StringBuilder, state: State) {
        val style = inherited.merge(component.style)
        if (component.text.isNotEmpty()) {
            val color = style.color
            val decorations = decorations(style)
            if (!state.started || color != state.color || (state.decorations and decorations.inv()) != 0) {
                // A colour code turns every decoration off, so changing colour or dropping a
                // decoration starts over: the colour (or §r for none), then each decoration.
                if (color != null) appendColor(out, color) else if (state.started) out.append(SECTION).append('r')
                appendDecorations(out, decorations)
            } else if (decorations != state.decorations) {
                appendDecorations(out, decorations and state.decorations.inv())
            }
            state.color = color
            state.decorations = decorations
            state.started = true
            out.append(component.text)
        }
        for (child in component.children) walk(child, style, out, state)
    }

    private fun appendColor(out: StringBuilder, color: TextColor) {
        val named = LEGACY_CODES[color]
        if (named != null) {
            out.append(SECTION).append(named)
        } else if (hex) {
            out.append(SECTION).append('x')
            for (c in color.hex().substring(1)) out.append(SECTION).append(c)
        } else {
            out.append(SECTION).append(LEGACY_CODES.getValue(nearest(color)))
        }
    }

    private fun appendDecorations(out: StringBuilder, decorations: Int) {
        for (i in DECORATION_CODES.indices) if (decorations and (1 shl i) != 0) out.append(SECTION).append(DECORATION_CODES[i])
    }

    private fun decorations(style: Style): Int {
        var bits = 0
        if (style.obfuscated == true) bits = bits or 1
        if (style.bold == true) bits = bits or 2
        if (style.strikethrough == true) bits = bits or 4
        if (style.underlined == true) bits = bits or 8
        if (style.italic == true) bits = bits or 16
        return bits
    }

    public companion object {
        private const val SECTION = '§'
        private val DECORATION_CODES = charArrayOf('k', 'l', 'm', 'n', 'o')

        /** The sixteen named colours' codes, `0` to `f`, in Minecraft's order. */
        private val LEGACY_CODES: Map<TextColor, Char> = listOf(
            "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold", "gray",
            "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white",
        ).mapIndexed { i, name -> TextColor.NAMED.getValue(name) to "0123456789abcdef"[i] }.toMap()

        /**
         * The named colour closest to [color], for clients before 1.16, which have only the
         * sixteen: by distance in RGB, weighted for how the eye sees each channel.
         */
        public fun nearest(color: TextColor): TextColor {
            if (color in LEGACY_CODES) return color
            var best = LEGACY_CODES.keys.first()
            var bestDistance = Long.MAX_VALUE
            for (candidate in LEGACY_CODES.keys) {
                val dr = ((color.rgb shr 16) and 0xFF) - ((candidate.rgb shr 16) and 0xFF)
                val dg = ((color.rgb shr 8) and 0xFF) - ((candidate.rgb shr 8) and 0xFF)
                val db = (color.rgb and 0xFF) - (candidate.rgb and 0xFF)
                val distance = 3L * dr * dr + 4L * dg * dg + 2L * db * db
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = candidate
                }
            }
            return best
        }
    }
}
