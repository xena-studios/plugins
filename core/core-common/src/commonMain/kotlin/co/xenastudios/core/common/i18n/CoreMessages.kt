package co.xenastudios.core.common.i18n

import co.xenastudios.core.common.text.Placeholder

/**
 * The core's own messages, in every plugin's `lang/` files beside the plugin's own (put it
 * first in the plugin's [MessageCatalog]). More come as the core's features do: reload,
 * errors, the update notice.
 */
public object CoreMessages : MessageBundle() {

    /** The footer under a long list's page: see [ChatPages]. */
    public val pagesFooter: MessageKey = message(
        "core.pages.footer",
        "<gray>Page <page> of <pages></gray>" +
            "<has_previous> <click:run_command:'<previous>'><hover:show_text:'<previous>'><highlight>« Previous</highlight></hover></click></has_previous>" +
            "<has_next> <click:run_command:'<next>'><hover:show_text:'<next>'><highlight>Next »</highlight></hover></click></has_next>",
        "Under a long list in chat (/homes, /history, ...) when it has more than one page.",
        "<previous> and <next> are the list's command for those pages; what's inside",
        "<has_previous>...</has_previous> or <has_next>...</has_next> shows only when there's such a page.",
        placeholders = listOf("page", "pages", "previous", "next", "has_previous", "has_next"),
    )
}

/**
 * Long lists in chat, a page at a time, with a footer players click for the next page:
 * `/homes`, `/history`, `/vote top`, a lookup's results. Today's `ChatPages`, with the footer a
 * message ([CoreMessages.pagesFooter]) in each reader's language.
 */
public object ChatPages {

    /**
     * One page of a list: its [items], its [number] (from 1), how many [pages] there are (at
     * least 1), and how many items there are in all ([total]).
     */
    public class Page<T>(public val items: List<T>, public val number: Int, public val pages: Int, public val total: Long) {
        public val hasNext: Boolean get() = number < pages
        public val hasPrevious: Boolean get() = number > 1
    }

    /** How many pages [total] items take, at least 1. */
    public fun pages(total: Long, perPage: Int): Int {
        val per = perPage.coerceAtLeast(1)
        return ((total + per - 1) / per).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
    }

    /** Rows to skip for [page], for a query's `OFFSET`. */
    public fun offset(page: Int, perPage: Int): Long = (page.coerceAtLeast(1) - 1).toLong() * perPage.coerceAtLeast(1)

    /** A page of a list in memory. A page past the end shows the last, and one before the start the first. */
    public fun <T> of(all: List<T>, page: Int, perPage: Int): Page<T> {
        val per = perPage.coerceAtLeast(1)
        val pages = pages(all.size.toLong(), per)
        val number = page.coerceIn(1, pages)
        val from = (number - 1) * per
        return Page(all.subList(from, minOf(all.size, from + per)).toList(), number, pages, all.size.toLong())
    }

    /** A page already read, as from a database with [offset]: [rows] is this page, [total] counted. */
    public fun <T> of(rows: List<T>, total: Long, page: Int, perPage: Int): Page<T> =
        Page(rows.toList(), page, pages(total, perPage), total)

    /**
     * The footer's placeholders for [page] in [language] (its page numbers written for it).
     * [command] is the list's command with `{page}` where the number goes, such as
     * `"/homes {page}"`.
     */
    public fun footer(page: Page<*>, command: String, language: Language): Map<String, Placeholder> = mapOf(
        "page" to Placeholder.text(language.formats.number(page.number)),
        "pages" to Placeholder.text(language.formats.number(page.pages)),
        "next" to Placeholder.text(command.replace("{page}", minOf(page.pages, page.number + 1).toString())),
        "previous" to Placeholder.text(command.replace("{page}", maxOf(1, page.number - 1).toString())),
        "has_next" to Placeholder.section(page.hasNext),
        "has_previous" to Placeholder.section(page.hasPrevious),
    )
}
