package co.xenastudios.m0

/** Our own text tree: drawn to Adventure on the JVM and to Pumpkin's text on Pumpkin. */
data class Style(
    val color: TextColor? = null,
    val bold: Boolean? = null,
    val italic: Boolean? = null,
    val underlined: Boolean? = null,
    val strikethrough: Boolean? = null,
    val obfuscated: Boolean? = null,
    val click: ClickAction? = null,
    val hover: Component? = null,
) {
    companion object { val EMPTY = Style() }
}

data class TextColor(val rgb: Int) {
    fun hex(): String = "#" + rgb.toString(16).padStart(6, '0')

    companion object {
        val NAMED: Map<String, TextColor> = mapOf(
            "black" to 0x000000, "dark_blue" to 0x0000AA, "dark_green" to 0x00AA00, "dark_aqua" to 0x00AAAA,
            "dark_red" to 0xAA0000, "dark_purple" to 0xAA00AA, "gold" to 0xFFAA00, "gray" to 0xAAAAAA,
            "dark_gray" to 0x555555, "blue" to 0x5555FF, "green" to 0x55FF55, "aqua" to 0x55FFFF,
            "red" to 0xFF5555, "light_purple" to 0xFF55FF, "yellow" to 0xFFFF55, "white" to 0xFFFFFF,
        ).mapValues { TextColor(it.value) }
    }
}

sealed interface ClickAction {
    val value: String
    data class RunCommand(override val value: String) : ClickAction
    data class SuggestCommand(override val value: String) : ClickAction
    data class OpenUrl(override val value: String) : ClickAction
    data class CopyToClipboard(override val value: String) : ClickAction
}

data class Component(val text: String = "", val style: Style = Style.EMPTY, val children: List<Component> = emptyList()) {
    /** The text with every tag gone, as a console without colors shows it. */
    fun plain(): String = buildString { appendPlain(this@Component) }

    private fun StringBuilder.appendPlain(c: Component) {
        append(c.text)
        c.children.forEach { appendPlain(it) }
    }
}

/**
 * A MiniMessage subset: colors (`<red>`, `<#ff8800>`, `<color:red>`), decorations (`<bold>`/`<b>`,
 * `<italic>`/`<i>`/`<em>`, `<underlined>`/`<u>`, `<strikethrough>`/`<st>`, `<obfuscated>`/`<obf>`,
 * and `<!bold>` to turn one off), `<click:run_command:/x>`, `<hover:show_text:'...'>`,
 * closing tags (`</red>`, `</>`), `<reset>`, and `\<` to escape. Unknown tags stay as text, as in
 * MiniMessage, so a typo shows up instead of vanishing. Never throws.
 */
object Markup {
    fun parse(input: String): Component {
        val root = Frame("", Style.EMPTY)
        val stack = ArrayDeque<Frame>().apply { addLast(root) }
        val text = StringBuilder()

        fun flush() {
            if (text.isNotEmpty()) {
                stack.last().children += Component(text.toString())
                text.clear()
            }
        }

        var i = 0
        while (i < input.length) {
            val c = input[i]
            if (c == '\\' && i + 1 < input.length && (input[i + 1] == '<' || input[i + 1] == '\\')) {
                text.append(input[i + 1]); i += 2; continue
            }
            if (c != '<') { text.append(c); i++; continue }
            val end = findTagEnd(input, i + 1)
            if (end < 0) { text.append(c); i++; continue }
            val tag = input.substring(i + 1, end)
            when {
                tag == "reset" -> {
                    flush()
                    while (stack.size > 1) closeTop(stack)
                }
                tag.startsWith("/") -> {
                    val name = canonical(tag.substring(1).substringBefore(':').lowercase())
                    flush()
                    if (name.isEmpty()) {
                        if (stack.size > 1) closeTop(stack)
                    } else {
                        val at = stack.indexOfLast { it.name == name }
                        if (at > 0) repeat(stack.size - at) { closeTop(stack) } else text.append('<').append(tag).append('>')
                    }
                }
                else -> {
                    val style = styleFor(tag)
                    if (style == null) {
                        text.append('<').append(tag).append('>')
                    } else {
                        flush()
                        stack.addLast(Frame(tag.substringBefore(':').lowercase().removePrefix("!").let { canonical(it) }, style))
                    }
                }
            }
            i = end + 1
        }
        flush()
        while (stack.size > 1) closeTop(stack)
        return root.toComponent()
    }

    private class Frame(val name: String, val style: Style) {
        val children = mutableListOf<Component>()
        fun toComponent(): Component =
            if (children.size == 1 && style == Style.EMPTY && name.isEmpty()) children[0]
            else Component("", style, children.toList())
    }

    private fun closeTop(stack: ArrayDeque<Frame>) {
        val top = stack.removeLast()
        stack.last().children += top.toComponent()
    }

    /** The index of the `>` closing a tag that starts at [from], skipping quoted arguments. */
    private fun findTagEnd(s: String, from: Int): Int {
        var quote: Char? = null
        var i = from
        while (i < s.length) {
            val c = s[i]
            when {
                quote != null && c == '\\' -> i++
                quote != null && c == quote -> quote = null
                quote == null && (c == '\'' || c == '"') -> quote = c
                quote == null && c == '<' -> return -1
                quote == null && c == '>' -> return i
            }
            i++
        }
        return -1
    }

    private val aliases = mapOf(
        "b" to "bold", "i" to "italic", "em" to "italic", "u" to "underlined",
        "st" to "strikethrough", "obf" to "obfuscated", "colour" to "color", "c" to "color",
    )

    private fun canonical(name: String) = aliases[name] ?: name

    private fun styleFor(tag: String): Style? {
        if (tag.isEmpty()) return null
        val negate = tag.startsWith("!")
        val args = splitArgs(tag.removePrefix("!"))
        val name = canonical(args[0].lowercase())
        color(name)?.let { return if (negate) null else Style(color = it) }
        return when (name) {
            "bold" -> Style(bold = !negate)
            "italic" -> Style(italic = !negate)
            "underlined" -> Style(underlined = !negate)
            "strikethrough" -> Style(strikethrough = !negate)
            "obfuscated" -> Style(obfuscated = !negate)
            "color" -> args.getOrNull(1)?.let { color(it.lowercase()) }?.let { Style(color = it) }
            "click" -> {
                val value = args.getOrNull(2) ?: return null
                val action = when (args[1].lowercase()) {
                    "run_command" -> ClickAction.RunCommand(value)
                    "suggest_command" -> ClickAction.SuggestCommand(value)
                    "open_url" -> ClickAction.OpenUrl(value)
                    "copy_to_clipboard" -> ClickAction.CopyToClipboard(value)
                    else -> return null
                }
                Style(click = action)
            }
            "hover" -> if (args.getOrNull(1)?.lowercase() == "show_text" && args.size > 2) Style(hover = parse(args[2])) else null
            else -> null
        }
    }

    private fun color(name: String): TextColor? {
        TextColor.NAMED[name]?.let { return it }
        if (name.length == 7 && name[0] == '#') return name.substring(1).toIntOrNull(16)?.let(::TextColor)
        return null
    }

    /** Splits `click:run_command:'/tp a:b'` into its parts, unquoting quoted ones. */
    private fun splitArgs(tag: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quote: Char? = null
        var i = 0
        while (i < tag.length) {
            val c = tag[i]
            when {
                quote != null && c == '\\' && i + 1 < tag.length -> { cur.append(tag[i + 1]); i++ }
                quote != null && c == quote -> quote = null
                quote == null && (c == '\'' || c == '"') -> quote = c
                quote == null && c == ':' && !(out.size >= 2 && out[0] == "click") -> { out += cur.toString(); cur.clear() }
                else -> cur.append(c)
            }
            i++
        }
        out += cur.toString()
        return out
    }
}
