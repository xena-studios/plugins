package co.xenastudios.zchat

import co.xenastudios.core.common.text.Component
import co.xenastudios.core.common.text.Markup
import co.xenastudios.core.common.text.Style
import co.xenastudios.core.common.text.TextColor

/** Turning what players (and other plugins) write into lines that can't act: styles only. */
public object ChatText {

    /** `<rainbow>`, which MiniMessage has and our markup doesn't, as the gradient it looks like. */
    private const val RAINBOW = "<gradient:#FF5555:#FFAA00:#FFFF55:#55FF55:#55FFFF:#5555FF:#FF55FF>"

    /**
     * What a player typed, as it shows in chat: literal, or with [styled] their colour and style
     * tags read (never clicks, hovers or line breaks).
     */
    public fun player(text: String, styled: Boolean): Component =
        if (!styled) Component.text(text) else styles(text)

    /** [text] in our markup with only its colours and styles kept. */
    public fun styles(text: String): Component {
        val markup = if ("rainbow" in text) text.replace("<rainbow>", RAINBOW).replace("</rainbow>", "</gradient>") else text
        return inert(Markup.parse(markup).render())
    }

    /** What a styled message says, with its tags taken out: what the filter must check. */
    public fun withoutStyles(text: String): String = styles(text).plain()

    /** [component] with its clicks, hovers and line breaks taken out, all the way down. */
    public fun inert(component: Component): Component {
        val style = if (component.style.click == null && component.style.hover == null) component.style
        else component.style.copy(click = null, hover = null)
        val text = if ('\n' in component.text) component.text.replace('\n', ' ') else component.text
        val children = if (component.children.isEmpty()) component.children else component.children.map(::inert)
        return if (style === component.style && text === component.text && children === component.children) component
        else Component(text, style, children)
    }

    /**
     * Text with `&` or `§` colour codes (`&6`, `&l`, `&#B48CFF`, `&x&B&4...`), as PlaceholderAPI
     * values and Bukkit display names have them, as components; MiniMessage tags in it are read as
     * [styles]. Never clicks or hovers.
     */
    public fun legacy(text: String): Component {
        if ('&' !in text && '§' !in text) return if ('<' in text) styles(text) else Component.text(text)
        val parts = ArrayList<Component>()
        var style = Style.EMPTY
        val run = StringBuilder()
        fun flush() {
            if (run.isNotEmpty()) parts += Component(run.toString(), style)
            run.setLength(0)
        }
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if ((c == '&' || c == '§') && i + 1 < text.length) {
                val code = text[i + 1].lowercaseChar()
                // &#RRGGBB
                if (code == '#' && i + 8 <= text.length) {
                    val color = TextColor.parse(text.substring(i + 1, i + 8))
                    if (color != null) {
                        flush()
                        style = Style(color = color)
                        i += 8
                        continue
                    }
                }
                // &x&R&R&G&G&B&B, Bukkit's own form.
                if (code == 'x' && i + 14 <= text.length) {
                    val hex = buildString { for (k in 0 until 6) append(text[i + 3 + k * 2]) }
                    val color = TextColor.parse("#$hex")
                    if (color != null) {
                        flush()
                        style = Style(color = color)
                        i += 14
                        continue
                    }
                }
                val named = LEGACY_COLORS[code]
                val next = when {
                    named != null -> Style(color = named)
                    code == 'l' -> style.copy(bold = true)
                    code == 'o' -> style.copy(italic = true)
                    code == 'n' -> style.copy(underlined = true)
                    code == 'm' -> style.copy(strikethrough = true)
                    code == 'k' -> style.copy(obfuscated = true)
                    code == 'r' -> Style.EMPTY
                    else -> null
                }
                if (next != null) {
                    flush()
                    style = next
                    i += 2
                    continue
                }
            }
            run.append(c)
            i++
        }
        flush()
        return when (parts.size) {
            0 -> Component.EMPTY
            1 -> parts[0]
            else -> Component(children = parts)
        }
    }

    private val LEGACY_COLORS: Map<Char, TextColor> = mapOf(
        '0' to "black", '1' to "dark_blue", '2' to "dark_green", '3' to "dark_aqua", '4' to "dark_red", '5' to "dark_purple",
        '6' to "gold", '7' to "gray", '8' to "dark_gray", '9' to "blue", 'a' to "green", 'b' to "aqua", 'c' to "red",
        'd' to "light_purple", 'e' to "yellow", 'f' to "white",
    ).mapValues { TextColor.NAMED.getValue(it.value) }

    /**
     * [component] with each match of [pattern] in its texts replaced by what [replace] gives
     * (`null` keeps the match as it was): mentions, highlighted. A match never spans two texts.
     */
    public fun replace(component: Component, pattern: Regex, replace: (MatchResult) -> Component?): Component {
        var changed = false
        val text = component.text
        var head: List<Component>? = null
        if (text.isNotEmpty()) {
            var last = 0
            val pieces = ArrayList<Component>()
            for (match in pattern.findAll(text)) {
                val replacement = replace(match) ?: continue
                if (match.range.first > last) pieces += Component.text(text.substring(last, match.range.first))
                pieces += replacement
                last = match.range.last + 1
            }
            if (pieces.isNotEmpty()) {
                if (last < text.length) pieces += Component.text(text.substring(last))
                head = pieces
                changed = true
            }
        }
        val children = component.children.map { child -> replace(child, pattern, replace).also { if (it !== child) changed = true } }
        if (!changed) return component
        // The replaced text becomes the first children, in the node's style, before its own children.
        return Component("", component.style, (head ?: listOfNotNull(if (text.isEmpty()) null else Component.text(text))) + children)
    }
}
