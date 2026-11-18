package co.xenastudios.core.text;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Modifying;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/**
 * Long lists in chat, a page at a time, with a footer players click for the next page:
 * {@code /homes}, {@code /history}, {@code /vote top}, a lookup's results.
 *
 * <p>A footer message gets {@code <page>}, {@code <pages>}, and {@code <next>} and
 * {@code <previous>}: the list's command for those pages, for a
 * {@code <click:run_command:'<next>'>}. Text inside {@code <has_next>...</has_next>} or
 * {@code <has_previous>...</has_previous>} only shows when there is such a page, so a
 * footer can offer "« Previous" from page 2 on. {@link #FOOTER} is one.
 */
public final class ChatPages {

    /** A footer in the theme's styles, for a plugin's defaults. */
    public static final String FOOTER = "<gray>Page <page> of <pages></gray>"
        + "<has_previous> <click:run_command:'<previous>'><hover:show_text:'<previous>'><highlight>« Previous</highlight></hover></click></has_previous>"
        + "<has_next> <click:run_command:'<next>'><hover:show_text:'<next>'><highlight>Next »</highlight></hover></click></has_next>";

    private static final Tag HIDDEN = (Modifying) (current, depth) -> Component.empty();
    private static final Tag SHOWN = Tag.styling(style -> {});

    private ChatPages() {}

    /**
     * One page of a list.
     *
     * @param items what's on this page
     * @param number this page, from 1
     * @param pages how many pages there are, at least 1
     * @param total how many items there are on every page together
     */
    public record Page<T>(List<T> items, int number, int pages, long total) {

        public boolean hasNext() {
            return number < pages;
        }

        public boolean hasPrevious() {
            return number > 1;
        }
    }

    /** How many pages {@code total} items take, at least 1. */
    public static int pages(long total, int perPage) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1, (total + perPage - 1) / perPage));
    }

    /** Rows to skip for a page, for a query's {@code OFFSET}. */
    public static long offset(int page, int perPage) {
        return (long) (Math.max(1, page) - 1) * perPage;
    }

    /** A page of a list in memory. A page past the end shows the last, and one before the start the first. */
    public static <T> Page<T> of(List<T> all, int page, int perPage) {
        int pages = pages(all.size(), perPage);
        int number = Math.max(1, Math.min(page, pages));
        int from = (number - 1) * perPage;
        return new Page<>(List.copyOf(all.subList(from, Math.min(all.size(), from + perPage))), number, pages, all.size());
    }

    /** A page already read, as from a database with {@link #offset}: {@code rows} is this page, {@code total} counted. */
    public static <T> Page<T> of(List<T> rows, long total, int page, int perPage) {
        return new Page<>(rows, page, pages(total, perPage), total);
    }

    /**
     * The footer's placeholders.
     *
     * @param command the list's command with {@code {page}} where the page number goes,
     *     such as {@code "/homes {page}"}. Built by the plugin: a {@code '} would end the
     *     click's argument.
     */
    public static TagResolver footer(Page<?> page, String command) {
        return TagResolver.resolver(
            Placeholders.text("page", page.number()),
            Placeholders.text("pages", page.pages()),
            command("next", command, Math.min(page.pages(), page.number() + 1)),
            command("previous", command, Math.max(1, page.number() - 1)),
            TagResolver.resolver("has_next", page.hasNext() ? SHOWN : HIDDEN),
            TagResolver.resolver("has_previous", page.hasPrevious() ? SHOWN : HIDDEN));
    }

    // Inside a click's argument only pre-processed tags are filled in; an inserted one would
    // run "<next>" itself. Escaped, so nothing in the command can add formatting.
    private static TagResolver command(String name, String command, int page) {
        String filled = command.replace("{page}", Integer.toString(page));
        return TagResolver.resolver(name, Tag.preProcessParsed(MiniMessage.miniMessage().escapeTags(filled)));
    }

    /**
     * A page's lines for chat: one per item, then the footer when there's more than one page.
     * The caller adds a header, and says something else when the list is empty.
     */
    public static <T> List<Component> lines(Page<T> page, Function<? super T, Component> line, Message footer, String command) {
        List<Component> lines = new ArrayList<>(page.items().size() + 1);
        for (T item : page.items()) {
            lines.add(line.apply(item));
        }
        if (page.pages() > 1 && !footer.isEmpty()) {
            lines.add(footer.chat(footer(page, command)));
        }
        return lines;
    }
}
