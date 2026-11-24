package co.xenastudios.core.common.i18n

import co.xenastudios.core.common.text.Markup
import co.xenastudios.core.common.text.TextColor
import co.xenastudios.core.common.text.Theme

/**
 * The tags a message's markup uses, by name, for checking a translation against its English
 * message: `<highlight><player></highlight>` uses `highlight` and `player`. Placeholders
 * inside a click's value (`/home <name>`) and a hover's text count too.
 *
 * It reads tags the way [Markup] does, with its own helpers (`\<` starts none, a quoted
 * argument may hold a `>`), but only to list them.
 */
internal object MarkupTags {

    /** What a tag is to a translation. */
    enum class Kind {
        /** Colours, decorations, gradients, `<reset>`, `<newline>`: any message may use them. */
        STYLE,

        /** The theme's tags, which every message may use: `<prefix>`, `<highlight>`, ... */
        THEME,

        /** `<click>` and `<hover>`: a translation may use them only where its English does. */
        INTERACTIVE,

        /** Anything else: a placeholder, or a typo. Only those the message fills are allowed. */
        OTHER,
    }

    private val DECORATIONS = setOf(
        "bold", "b", "italic", "i", "em", "underlined", "u", "strikethrough", "st", "obfuscated", "obf",
    )
    private val STYLES = DECORATIONS + setOf("color", "colour", "c", "gradient", "reset", "newline", "br")

    fun kind(name: String): Kind = when {
        name in STYLES || TextColor.parse(name) != null -> Kind.STYLE
        name in Theme.TAG_NAMES -> Kind.THEME
        name == "click" || name == "hover" -> Kind.INTERACTIVE
        else -> Kind.OTHER
    }

    /** Every tag name [text] opens, in lower case, in order, repeats included. */
    fun names(text: String): List<String> {
        val out = ArrayList<String>()
        collect(text, out, 0)
        return out
    }

    private fun collect(text: String, out: MutableList<String>, depth: Int) {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length && (text[i + 1] == '<' || text[i + 1] == '\\')) {
                i += 2
                continue
            }
            if (c != '<') {
                i++
                continue
            }
            val end = Markup.tagEnd(text, i + 1)
            if (end < 0) {
                i++
                continue
            }
            val tag = text.substring(i + 1, end)
            i = end + 1
            if (tag.isEmpty() || tag.startsWith("/")) continue
            val body = tag.removePrefix("!")
            val name = body.substringBefore(':').lowercase()
            if (name.isEmpty()) continue
            out += name
            if (depth < 4 && ':' in body) {
                val args = Markup.splitArgs(body, 3)
                when {
                    name == "hover" && args.size >= 3 -> collect(args[2], out, depth + 1)
                    name == "click" && args.size >= 3 -> collectSlots(args[2], out)
                }
            }
        }
    }

    // A click's value is no markup: only `<name>` placeholders are filled in it.
    private fun collectSlots(value: String, out: MutableList<String>) {
        var i = value.indexOf('<')
        while (i >= 0) {
            val end = value.indexOf('>', i + 1)
            if (end < 0) return
            val name = value.substring(i + 1, end)
            if (Markup.isPlaceholderName(name)) out += name
            i = value.indexOf('<', end + 1)
        }
    }
}
