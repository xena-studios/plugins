package co.xenastudios.gradle

/**
 * Turns a changelog section's Markdown into the BBCode voxel.shop shows update posts in.
 *
 * Covers what the changelogs use: `##`/`###` headings, `-` lists (with wrapped lines),
 * paragraphs, `**bold**`, `*italic*`, `[links](url)` and `` `code` ``. Code spans keep their
 * text as it is, without the backticks, so a `**` inside one isn't taken for bold.
 */
object BbCode {
    private val heading = Regex("""^#{1,6}\s+(.*)$""")
    private val item = Regex("""^\s*[-*+]\s+(.*)$""")
    private val link = Regex("""\[([^\]]+)]\(([^)\s]+)\)""")
    private val bold = Regex("""\*\*(.+?)\*\*""")
    private val italic = Regex("""(?<![*\w])\*(?!\s)(.+?)(?<!\s)\*(?![*\w])""")

    fun fromMarkdown(markdown: String): String {
        val out = mutableListOf<String>()
        var list: MutableList<String>? = null
        var paragraph: MutableList<String>? = null

        fun flush() {
            list?.let { items -> out += "[list]\n" + items.joinToString("\n") { "[*]" + inline(it) } + "\n[/list]" }
            paragraph?.let { out += inline(it.joinToString(" ")) }
            list = null
            paragraph = null
        }

        for (raw in markdown.lines()) {
            val line = raw.trimEnd()
            when {
                line.isBlank() -> flush()
                heading.matches(line) -> {
                    flush()
                    out += "[b]" + inline(heading.find(line)!!.groupValues[1]) + "[/b]"
                }
                item.matches(line) -> {
                    paragraph?.let { flush() }
                    (list ?: mutableListOf<String>().also { list = it }) += item.find(line)!!.groupValues[1]
                }
                // A wrapped line of the item above.
                list != null && raw.startsWith(" ") -> list!![list!!.lastIndex] += " " + line.trim()
                else -> {
                    list?.let { flush() }
                    (paragraph ?: mutableListOf<String>().also { paragraph = it }) += line.trim()
                }
            }
        }
        flush()
        return out.joinToString("\n\n")
    }

    /** Inline formatting, leaving code spans' text alone. */
    private fun inline(text: String): String =
        text.split('`').mapIndexed { i, part ->
            if (i % 2 == 1) {
                part
            } else {
                part
                    .replace(link) { "[url=${it.groupValues[2]}]${it.groupValues[1]}[/url]" }
                    .replace(bold) { "[b]${it.groupValues[1]}[/b]" }
                    .replace(italic) { "[i]${it.groupValues[1]}[/i]" }
            }
        }.joinToString("")
}
