package co.xenastudios.core.common.text

import kotlin.concurrent.Volatile

/**
 * A message in our MiniMessage-style markup, parsed once (when the config or language file
 * loads) and rendered to a [Component] each time it's sent, with that send's placeholders.
 *
 * The tags are the ones today's plugins' messages use, read as MiniMessage reads them:
 * - colours: `<red>`, `<dark_gray>`, `<#B48CFF>`, `<color:gold>` (`colour`, `c`);
 * - decorations: `<bold>` (`b`), `<italic>` (`i`, `em`), `<underlined>` (`u`),
 *   `<strikethrough>` (`st`), `<obfuscated>` (`obf`), and `<!bold>` to turn one off;
 * - `<gradient:#B48CFF:#5EE08A>`, over every character inside it;
 * - `<click:run_command:'/home <name>'>`, with `suggest_command`, `open_url` and
 *   `copy_to_clipboard`, and `<hover:show_text:'<red>Go'>`, whose text is markup too;
 * - `<newline>` (`br`), and `<reset>`, which closes everything open;
 * - the caller's own [MarkupTag]s, given to [parse]: the theme's `<highlight>`, `<success>`,
 *   `<error>` and `<prefix>`;
 * - placeholders, filled in by [render]: `<player>` for a value, and
 *   `<has_next>...</has_next>` for a part shown or not.
 *
 * Every tag closes with `</name>` or `</>`, or at the end. `\<` is a `<` that starts no tag.
 * A tag it doesn't know stays as text, as in MiniMessage, so a typo shows up instead of
 * vanishing; so does a placeholder [render] isn't given. Parsing and rendering never throw.
 */
public class Markup private constructor(
    /** The markup as written. */
    public val source: String,
    private val nodes: List<Node>,
) {
    // The render with no placeholders, kept: most messages have none. Racing threads may
    // each make one; they're equal, and either is fine to keep.
    @Volatile
    private var cached: Component? = null

    /** The message with no placeholders filled in. Made once, then kept. */
    public fun render(): Component = cached ?: build(nodes, emptyMap()).also { cached = it }

    /** The message with these [placeholders], by name. */
    public fun render(placeholders: Map<String, Placeholder>): Component =
        if (placeholders.isEmpty()) render() else build(nodes, placeholders)

    /** The message with these [placeholders]: `render("player" to Placeholder.text(name))`. */
    public fun render(vararg placeholders: Pair<String, Placeholder>): Component = render(placeholders.toMap())

    override fun toString(): String = source

    public companion object {
        /** An empty message, which renders to [Component.EMPTY]. */
        public val EMPTY: Markup = Markup("", emptyList())

        // How deep hovers can nest inside hovers. Real messages use one level.
        private const val MAX_DEPTH = 4

        /**
         * Parses [input], with the caller's own [tags] (by name, in lower case) besides the
         * built-in ones. A tag of the caller's wins over a built-in one of the same name.
         */
        public fun parse(input: String, tags: Map<String, MarkupTag> = emptyMap()): Markup =
            if (input.isEmpty()) EMPTY else Markup(input, Parser(tags).parse(input, 0))

        /** [text] escaped so [parse] shows it as it is: a player's name or chat, say. */
        public fun escape(text: String): String {
            if ('<' !in text && '\\' !in text) return text
            val out = StringBuilder(text.length + 8)
            for (c in text) {
                if (c == '<' || c == '\\') out.append('\\')
                out.append(c)
            }
            return out.toString()
        }

        private val ALIASES = mapOf(
            "b" to "bold", "i" to "italic", "em" to "italic", "u" to "underlined",
            "st" to "strikethrough", "obf" to "obfuscated", "colour" to "color", "c" to "color",
            "br" to "newline",
        )

        private fun canonical(name: String) = ALIASES[name] ?: name

        private fun isPlaceholderName(name: String) =
            name.isNotEmpty() && name.all { it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-' }

        // The default gradient, as MiniMessage's: white to black.
        private val DEFAULT_GRADIENT = listOf(TextColor(0xFFFFFF), TextColor(0x000000))

        /**
         * Splits a tag's inside at its colons, into at most [limit] parts, unquoting quoted
         * ones: `click:run_command:'/tp a:b'` is `click`, `run_command`, `/tp a:b`.
         */
        private fun splitArgs(tag: String, limit: Int): List<String> {
            val out = mutableListOf<String>()
            val current = StringBuilder()
            var quote: Char? = null
            var i = 0
            while (i < tag.length) {
                val c = tag[i]
                when {
                    quote != null && c == '\\' && i + 1 < tag.length &&
                        (tag[i + 1] == quote || tag[i + 1] == '\\') -> current.append(tag[++i])
                    quote != null && c == quote -> quote = null
                    quote == null && (c == '\'' || c == '"') -> quote = c
                    quote == null && c == ':' && out.size < limit - 1 -> {
                        out += current.toString()
                        current.clear()
                    }
                    else -> current.append(c)
                }
                i++
            }
            out += current.toString()
            return out
        }

        /** The index of the `>` that closes a tag starting at [from], skipping quoted arguments; -1 if none. */
        private fun tagEnd(s: String, from: Int): Int {
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

        /** A click's value with its placeholders, such as `/home <name>`, as text and placeholders in turn. */
        private fun clickParts(value: String): List<ClickPart> {
            val parts = mutableListOf<ClickPart>()
            var i = 0
            var literal = 0
            while (i < value.length) {
                if (value[i] == '<') {
                    val end = value.indexOf('>', i + 1)
                    if (end > i + 1) {
                        val name = value.substring(i + 1, end)
                        if (isPlaceholderName(name)) {
                            if (i > literal) parts += ClickPart.Literal(value.substring(literal, i))
                            parts += ClickPart.Slot(name)
                            literal = end + 1
                            i = end + 1
                            continue
                        }
                    }
                }
                i++
            }
            if (literal < value.length) parts += ClickPart.Literal(value.substring(literal))
            return parts
        }

        private fun build(nodes: List<Node>, placeholders: Map<String, Placeholder>): Component {
            val out = ArrayList<Component>()
            renderInto(nodes, placeholders, out)
            return wrap(out)
        }

        private fun wrap(components: List<Component>): Component = when (components.size) {
            0 -> Component.EMPTY
            1 -> components[0]
            else -> Component(children = components)
        }

        private fun renderInto(nodes: List<Node>, placeholders: Map<String, Placeholder>, out: MutableList<Component>) {
            for (node in nodes) {
                when (node) {
                    is Node.Text -> out += Component(node.text)
                    is Node.Insert -> out += node.component
                    is Node.Styled -> {
                        val children = ArrayList<Component>()
                        renderInto(node.children, placeholders, children)
                        if (children.isEmpty()) continue
                        var style = node.style
                        if (node.click != null) {
                            style = style.copy(click = ClickEvent(node.click.action, node.click.value(placeholders)))
                        }
                        if (node.hover != null) style = style.copy(hover = build(node.hover, placeholders))
                        out += Component(style = style, children = children)
                    }
                    is Node.Gradient -> {
                        val children = ArrayList<Component>()
                        renderInto(node.children, placeholders, children)
                        if (children.isNotEmpty()) out += Gradients.apply(node.colors, children)
                    }
                    is Node.Slot -> when (val value = placeholders[node.name]) {
                        null -> {
                            out += Component(node.open)
                            if (node.children != null) {
                                renderInto(node.children, placeholders, out)
                                out += Component(node.close)
                            }
                        }
                        is Placeholder.Text -> {
                            out += Component(value.text)
                            node.children?.let { renderInto(it, placeholders, out) }
                        }
                        is Placeholder.Insert -> {
                            out += value.component
                            node.children?.let { renderInto(it, placeholders, out) }
                        }
                        is Placeholder.Section ->
                            if (value.shown) node.children?.let { renderInto(it, placeholders, out) }
                    }
                }
            }
        }
    }

    private class Parser(private val tags: Map<String, MarkupTag>) {

        private class Frame(
            val kind: Kind,
            val name: String,
            val style: Style = Style.EMPTY,
            val click: ClickTemplate? = null,
            val hover: List<Node>? = null,
            val colors: List<TextColor> = emptyList(),
            val raw: String = "",
        ) {
            val children = ArrayList<Node>()
        }

        private enum class Kind { ROOT, STYLE, GRADIENT, SLOT }

        fun parse(input: String, depth: Int): List<Node> {
            val stack = ArrayDeque<Frame>()
            stack.addLast(Frame(Kind.ROOT, ""))
            val text = StringBuilder()

            fun flush() {
                if (text.isNotEmpty()) {
                    stack.last().children += Node.Text(text.toString())
                    text.clear()
                }
            }

            var i = 0
            while (i < input.length) {
                val c = input[i]
                if (c == '\\' && i + 1 < input.length && (input[i + 1] == '<' || input[i + 1] == '\\')) {
                    text.append(input[i + 1])
                    i += 2
                    continue
                }
                val end = if (c == '<') tagEnd(input, i + 1) else -1
                if (end < 0) {
                    text.append(c)
                    i++
                    continue
                }
                val raw = input.substring(i, end + 1)
                val tag = input.substring(i + 1, end)
                i = end + 1
                if (tag.startsWith("/")) {
                    val name = canonical(tag.substring(1).substringBefore(':').lowercase())
                    if (!canClose(stack, name)) {
                        text.append(raw)
                        continue
                    }
                    flush()
                    close(stack, name, raw)
                    continue
                }
                val action = open(tag, raw, depth)
                if (action == null) {
                    text.append(raw)
                    continue
                }
                flush()
                when (action) {
                    is Opened.Push -> stack.addLast(action.frame)
                    is Opened.Add -> stack.last().children += action.node
                    Opened.Reset -> while (stack.size > 1) closeTop(stack, explicit = false)
                }
            }
            flush()
            while (stack.size > 1) closeTop(stack, explicit = false)
            return stack.last().children
        }

        private sealed interface Opened {
            class Push(val frame: Frame) : Opened
            class Add(val node: Node) : Opened
            object Reset : Opened
        }

        /** What an opening tag does, or `null` if it isn't one, so it stays as text. */
        private fun open(tag: String, raw: String, depth: Int): Opened? {
            if (tag.isEmpty()) return null
            val negate = tag.startsWith("!")
            val body = if (negate) tag.substring(1) else tag
            val first = body.substringBefore(':').lowercase()
            val hasArgs = ':' in body

            if (!negate && !hasArgs) {
                when (val custom = tags[first]) {
                    is MarkupTag.Styling -> return Opened.Push(Frame(Kind.STYLE, canonical(first), custom.style))
                    is MarkupTag.Inserting -> return Opened.Add(Node.Insert(custom.component))
                    null -> {}
                }
            }

            val name = canonical(first)
            if (!hasArgs) {
                TextColor.parse(name)?.let {
                    return if (negate) null else Opened.Push(Frame(Kind.STYLE, name, Style(color = it)))
                }
                decoration(name, !negate)?.let { return Opened.Push(Frame(Kind.STYLE, name, it)) }
                if (negate) return null
                return when {
                    name == "reset" -> Opened.Reset
                    name == "newline" -> Opened.Add(Node.Text("\n"))
                    name == "gradient" -> Opened.Push(Frame(Kind.GRADIENT, name, colors = DEFAULT_GRADIENT))
                    isPlaceholderName(name) -> Opened.Push(Frame(Kind.SLOT, name, raw = raw))
                    else -> null
                }
            }
            if (negate) return null
            return when (name) {
                "color" -> {
                    val args = splitArgs(body, 2)
                    TextColor.parse(args[1])?.let { Opened.Push(Frame(Kind.STYLE, name, Style(color = it))) }
                }
                "gradient" -> {
                    val colors = splitArgs(body, Int.MAX_VALUE).drop(1).map { TextColor.parse(it) ?: return null }
                    Opened.Push(Frame(Kind.GRADIENT, name, colors = colors))
                }
                "click" -> {
                    val args = splitArgs(body, 3)
                    if (args.size < 3) return null
                    val action = ClickEvent.Action.entries.firstOrNull { it.markup == args[1].lowercase() } ?: return null
                    Opened.Push(Frame(Kind.STYLE, name, click = ClickTemplate(action, clickParts(args[2]))))
                }
                "hover" -> {
                    val args = splitArgs(body, 3)
                    if (args.size < 3 || args[1].lowercase() != "show_text" || depth >= MAX_DEPTH) return null
                    Opened.Push(Frame(Kind.STYLE, name, hover = parse(args[2], depth + 1)))
                }
                else -> null
            }
        }

        private fun decoration(name: String, on: Boolean): Style? = when (name) {
            "bold" -> Style(bold = on)
            "italic" -> Style(italic = on)
            "underlined" -> Style(underlined = on)
            "strikethrough" -> Style(strikethrough = on)
            "obfuscated" -> Style(obfuscated = on)
            else -> null
        }

        /**
         * Whether a closing tag for [name] has something open to close. `</>` closes the
         * innermost tag; a placeholder isn't a tag it closes.
         */
        private fun canClose(stack: ArrayDeque<Frame>, name: String): Boolean =
            if (name.isEmpty()) stack.any { it.kind == Kind.STYLE || it.kind == Kind.GRADIENT }
            else stack.indexOfLast { it.name == name } > 0

        private fun close(stack: ArrayDeque<Frame>, name: String, raw: String) {
            if (name.isEmpty()) {
                while (stack.last().kind == Kind.SLOT) closeTop(stack, explicit = false)
                closeTop(stack, explicit = true, raw)
                return
            }
            val at = stack.indexOfLast { it.name == name }
            while (stack.size - 1 > at) closeTop(stack, explicit = false)
            closeTop(stack, explicit = true, raw)
        }

        /**
         * Closes the innermost open tag. A placeholder closed by its own `</name>` holds what's
         * between, as a part shown or not; one never closed is just a value, and what followed
         * it goes back to the tag around it.
         */
        private fun closeTop(stack: ArrayDeque<Frame>, explicit: Boolean, raw: String = "") {
            val top = stack.removeLast()
            val parent = stack.last().children
            when (top.kind) {
                Kind.SLOT -> if (explicit) {
                    parent += Node.Slot(top.name, top.raw, raw, top.children)
                } else {
                    parent += Node.Slot(top.name, top.raw, "", null)
                    parent += top.children
                }
                Kind.GRADIENT -> parent += Node.Gradient(top.colors, top.children)
                Kind.STYLE -> parent += Node.Styled(top.style, top.click, top.hover, top.children)
                Kind.ROOT -> error("the root is never closed")
            }
        }
    }
}

/** A tag of the caller's own, given to [Markup.parse]: the theme's, say. */
public sealed interface MarkupTag {

    /** A tag that styles what's inside it, as `<highlight>name</highlight>`. */
    public data class Styling(public val style: Style) : MarkupTag

    /** A tag that stands for a component, as `<prefix>`. */
    public data class Inserting(public val component: Component) : MarkupTag
}

/** What a placeholder is filled with when a [Markup] is rendered. */
public sealed interface Placeholder {

    /** Text, shown as it is: never read as markup, so a player's name can't add a colour. */
    public data class Text(public val text: String) : Placeholder

    /** A component, styles and all. */
    public data class Insert(public val component: Component) : Placeholder

    /** For `<name>...</name>`: whether what's between is shown. */
    public data class Section(public val shown: Boolean) : Placeholder

    public companion object {
        /** [value] as text, shown as it is. */
        public fun text(value: Any?): Placeholder = Text(value.toString())

        /** [component], styles and all. */
        public fun component(component: Component): Placeholder = Insert(component)

        /** A part shown only when [shown]. */
        public fun section(shown: Boolean): Placeholder = Section(shown)
    }
}

/** The parsed form of a message: the tree [Markup] renders from. */
private sealed interface Node {
    class Text(val text: String) : Node
    class Insert(val component: Component) : Node
    class Styled(val style: Style, val click: ClickTemplate?, val hover: List<Node>?, val children: List<Node>) : Node
    class Gradient(val colors: List<TextColor>, val children: List<Node>) : Node

    /**
     * A placeholder, written as [open]; [children] is what's between it and its closing tag,
     * [close], if it has one.
     */
    class Slot(val name: String, val open: String, val close: String, val children: List<Node>?) : Node
}

private sealed interface ClickPart {
    class Literal(val text: String) : ClickPart
    class Slot(val name: String) : ClickPart
}

/** A click whose value can hold placeholders, as `<click:run_command:'/home <name>'>`. */
private class ClickTemplate(val action: ClickEvent.Action, private val parts: List<ClickPart>) {
    fun value(placeholders: Map<String, Placeholder>): String = buildString {
        for (part in parts) {
            when (part) {
                is ClickPart.Literal -> append(part.text)
                is ClickPart.Slot -> when (val value = placeholders[part.name]) {
                    is Placeholder.Text -> append(value.text)
                    is Placeholder.Insert -> append(value.component.plain())
                    is Placeholder.Section, null -> append('<').append(part.name).append('>')
                }
            }
        }
    }
}

/** Colours each character inside a `<gradient>` along its colours, as MiniMessage does. */
private object Gradients {

    fun apply(colors: List<TextColor>, children: List<Component>): Component {
        val total = children.sumOf { length(it) }
        val counter = intArrayOf(0)
        return Component(children = children.map { paint(it, colors, total, counter) })
    }

    /** Characters, counting a surrogate pair as one. Hover text isn't counted: it isn't shown in line. */
    private fun length(component: Component): Int =
        codePoints(component.text) + component.children.sumOf { length(it) }

    private fun codePoints(text: String): Int {
        var count = 0
        var i = 0
        while (i < text.length) {
            i += if (text[i].isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) 2 else 1
            count++
        }
        return count
    }

    // A character whose own tag gives it a colour keeps it, but still takes its place along
    // the gradient, so the colours around it don't jump.
    private fun paint(component: Component, colors: List<TextColor>, total: Int, counter: IntArray): Component {
        if (component.style.color != null) {
            counter[0] += length(component)
            return component
        }
        val pieces = ArrayList<Component>()
        val text = component.text
        var i = 0
        while (i < text.length) {
            val step = if (text[i].isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) 2 else 1
            pieces += Component(text.substring(i, i + step), Style(color = colorAt(colors, counter[0]++, total)))
            i += step
        }
        for (child in component.children) pieces += paint(child, colors, total, counter)
        return Component(style = component.style, children = pieces)
    }

    private fun colorAt(colors: List<TextColor>, index: Int, total: Int): TextColor {
        if (colors.size == 1 || total <= 1) return colors[0]
        val position = index.toDouble() / (total - 1) * (colors.size - 1)
        val segment = position.toInt().coerceAtMost(colors.size - 2)
        val t = position - segment
        val from = colors[segment].rgb
        val to = colors[segment + 1].rgb
        fun channel(shift: Int): Int {
            val a = (from shr shift) and 0xFF
            val b = (to shr shift) and 0xFF
            return (a + (b - a) * t + 0.5).toInt().coerceIn(0, 255) shl shift
        }
        return TextColor(channel(16) or channel(8) or channel(0))
    }
}
