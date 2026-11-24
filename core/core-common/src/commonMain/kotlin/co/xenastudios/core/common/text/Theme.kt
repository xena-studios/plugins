package co.xenastudios.core.common.text

import co.xenastudios.core.common.Outcome

/**
 * The look every message shares, set once in `config.yml` for every language: a [prefix] and
 * four styles, used in messages as tags. Changing one restyles every message that uses it.
 *
 * - `<prefix>` stands for the prefix, which may itself use the three styles below;
 * - [base] colours message text that has no tag of its own;
 * - `<highlight>` for names and values, `<success>` for something done or turned on,
 *   `<error>` for something refused or turned off.
 *
 * The tags are given to the parser ([tags]), so a theme change means parsing again: messages
 * are parsed once per language for a theme, on load or reload, never per send.
 */
public class Theme(
    /** The prefix in markup, or blank for none. */
    public val prefix: String = DEFAULT_PREFIX,
    public val base: Style = DEFAULT_BASE,
    public val highlight: Style = DEFAULT_HIGHLIGHT,
    public val success: Style = DEFAULT_SUCCESS,
    public val error: Style = DEFAULT_ERROR,
) {
    /** The theme as markup tags, by name, for [Markup.parse]. */
    public val tags: Map<String, MarkupTag> = run {
        val styles = mapOf(
            "highlight" to MarkupTag.Styling(highlight),
            "success" to MarkupTag.Styling(success),
            "error" to MarkupTag.Styling(error),
        )
        val prefixed = if (prefix.isBlank()) Component.EMPTY else Markup.parse(prefix, styles).render()
        styles + ("prefix" to MarkupTag.Inserting(prefixed))
    }

    /** [component] as sent in chat: in the [base] style where it sets none. */
    public fun chat(component: Component): Component =
        if (base.isEmpty()) component else Component(style = base, children = listOf(component))

    public companion object {
        /** The theme's tag names, which every message may use. */
        public val TAG_NAMES: Set<String> = setOf("prefix", "highlight", "success", "error")

        public const val DEFAULT_PREFIX: String = "<#B48CFF>»</#B48CFF> "
        public val DEFAULT_BASE: Style = Style(color = TextColor.NAMED.getValue("gray"))
        public val DEFAULT_HIGHLIGHT: Style = Style(color = TextColor(0xB48CFF))
        public val DEFAULT_SUCCESS: Style = Style(color = TextColor(0x5EE08A))
        public val DEFAULT_ERROR: Style = Style(color = TextColor(0xFF6B6B))

        /** Today's default theme. */
        public val DEFAULT: Theme = Theme()

        // Stands in for the text the tags would style, to find the style they give it.
        private const val MARK = ''

        /**
         * A style written as tags, as the theme's settings are: `<gray>`, `<#B48CFF><bold>`, or
         * bare names, `gold bold`. Blank is no style. Only colours and decorations: a
         * gradient, a click or a hover, or a tag it doesn't know, is refused, with the reason.
         */
        public fun style(tags: String): Outcome<Style, String> {
            val trimmed = tags.trim()
            if (trimmed.isEmpty()) return Outcome.Ok(Style.EMPTY)
            val markup = if (trimmed.startsWith("<")) trimmed else trimmed.split(Regex("\\s+")).joinToString("") { "<$it>" }
            // Two marks: a gradient would colour them differently.
            val rendered = Markup.parse("$markup$MARK$MARK").render()
            if (rendered.plain() != "$MARK$MARK") return Outcome.Err("isn't a colour or style, such as <gray> or <#B48CFF><bold>")
            val styles = ArrayList<Style>(2)
            collect(rendered, Style.EMPTY, styles)
            if (styles.distinct().size > 1) return Outcome.Err("can't be a gradient; put those in the prefix or a message instead")
            val style = styles.firstOrNull() ?: Style.EMPTY
            if (style.click != null || style.hover != null) return Outcome.Err("can only be colours and decorations, not clicks or hovers")
            return Outcome.Ok(style)
        }

        private fun collect(component: Component, inherited: Style, out: MutableList<Style>) {
            val style = inherited.merge(component.style)
            for (c in component.text) if (c == MARK) out += style
            for (child in component.children) collect(child, style, out)
        }
    }
}
