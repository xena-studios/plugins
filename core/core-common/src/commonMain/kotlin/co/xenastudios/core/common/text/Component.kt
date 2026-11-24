package co.xenastudios.core.common.text

/**
 * Our own text tree, which [Markup] parses into and each platform draws once per language to
 * its own text: Adventure where it's native, Bukkit's components on Spigot, Minecraft's text on
 * the mod loaders, Pumpkin's text on Pumpkin (DECISIONS.md, M0).
 *
 * A component is its own [text] followed by its [children], all in its [style]; a child's
 * style overrides the parts it sets and inherits the rest.
 */
public data class Component(
    public val text: String = "",
    public val style: Style = Style.EMPTY,
    public val children: List<Component> = emptyList(),
) {
    /** The text with every style gone, as a console without colours shows it. */
    public fun plain(): String = buildString { appendPlain(this@Component) }

    private fun StringBuilder.appendPlain(component: Component) {
        append(component.text)
        for (child in component.children) appendPlain(child)
    }

    public companion object {
        /** No text at all. */
        public val EMPTY: Component = Component()

        /** [text] as it is, with no style: never read as markup. */
        public fun text(text: String): Component = Component(text)
    }
}

/**
 * How a [Component] looks and what it does. A `null` part is inherited from the parent; a
 * decoration set to `false` turns off one the parent turned on.
 */
public data class Style(
    public val color: TextColor? = null,
    public val bold: Boolean? = null,
    public val italic: Boolean? = null,
    public val underlined: Boolean? = null,
    public val strikethrough: Boolean? = null,
    public val obfuscated: Boolean? = null,
    public val click: ClickEvent? = null,
    public val hover: Component? = null,
) {
    /** This style with every part [other] sets taking the place of this one's. */
    public fun merge(other: Style): Style = Style(
        color = other.color ?: color,
        bold = other.bold ?: bold,
        italic = other.italic ?: italic,
        underlined = other.underlined ?: underlined,
        strikethrough = other.strikethrough ?: strikethrough,
        obfuscated = other.obfuscated ?: obfuscated,
        click = other.click ?: click,
        hover = other.hover ?: hover,
    )

    /** Whether it sets nothing. */
    public fun isEmpty(): Boolean = this == EMPTY

    public companion object {
        /** Sets nothing: everything is inherited. */
        public val EMPTY: Style = Style()
    }
}

/** A colour as 24-bit RGB, such as `0xB48CFF`. */
public data class TextColor(public val rgb: Int) {

    /** The colour as `#rrggbb`, in lower case. */
    public fun hex(): String = "#" + (rgb and 0xFFFFFF).toString(16).padStart(6, '0')

    override fun toString(): String = NAMES[this] ?: hex()

    public companion object {
        /** Minecraft's sixteen named colours, by their names in markup. */
        public val NAMED: Map<String, TextColor> = listOf(
            "black" to 0x000000, "dark_blue" to 0x0000AA, "dark_green" to 0x00AA00,
            "dark_aqua" to 0x00AAAA, "dark_red" to 0xAA0000, "dark_purple" to 0xAA00AA,
            "gold" to 0xFFAA00, "gray" to 0xAAAAAA, "dark_gray" to 0x555555, "blue" to 0x5555FF,
            "green" to 0x55FF55, "aqua" to 0x55FFFF, "red" to 0xFF5555,
            "light_purple" to 0xFF55FF, "yellow" to 0xFFFF55, "white" to 0xFFFFFF,
        ).associate { (name, rgb) -> name to TextColor(rgb) }

        // Spellings MiniMessage also reads.
        private val ALIASES: Map<String, String> = mapOf("grey" to "gray", "dark_grey" to "dark_gray")

        private val NAMES: Map<TextColor, String> = NAMED.entries.associate { (name, color) -> color to name }

        /**
         * A colour from markup: a name (`red`, `dark_gray`, `grey`) or `#rrggbb`, in any case;
         * `null` if it's neither.
         */
        public fun parse(text: String): TextColor? {
            val name = text.lowercase()
            NAMED[ALIASES[name] ?: name]?.let { return it }
            if (name.length != 7 || name[0] != '#') return null
            for (i in 1 until 7) if (name[i] !in '0'..'9' && name[i] !in 'a'..'f') return null
            return TextColor(name.substring(1).toInt(16))
        }
    }
}

/** What clicking a [Component] does: an [action] with its [value]. */
public data class ClickEvent(public val action: Action, public val value: String) {

    /** The click actions markup can ask for, by their names in `<click:...>`. */
    public enum class Action(public val markup: String) {
        /** Runs [value] as the player's command (or chat, without a `/`). */
        RUN_COMMAND("run_command"),

        /** Puts [value] in the player's chat box to edit. */
        SUGGEST_COMMAND("suggest_command"),

        /** Offers to open [value] in a browser. */
        OPEN_URL("open_url"),

        /** Copies [value] to the player's clipboard. */
        COPY_TO_CLIPBOARD("copy_to_clipboard"),
    }
}
