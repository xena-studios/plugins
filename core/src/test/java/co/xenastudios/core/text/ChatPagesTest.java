package co.xenastudios.core.text;

import static co.xenastudios.core.text.TextTest.plain;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.IntStream;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.junit.jupiter.api.Test;

class ChatPagesTest {

    final List<Integer> twenty = IntStream.rangeClosed(1, 20).boxed().toList();

    @Test
    void pagesOfAListInMemory() {
        ChatPages.Page<Integer> second = ChatPages.of(twenty, 2, 7);
        assertEquals(List.of(8, 9, 10, 11, 12, 13, 14), second.items());
        assertEquals(3, second.pages());
        assertEquals(20, second.total());
        assertTrue(second.hasNext() && second.hasPrevious());

        assertEquals(List.of(15, 16, 17, 18, 19, 20), ChatPages.of(twenty, 99, 7).items(), "past the end is the last page");
        assertEquals(1, ChatPages.of(twenty, -3, 7).number());
        ChatPages.Page<Integer> empty = ChatPages.of(List.<Integer>of(), 1, 7);
        assertEquals(1, empty.pages());
        assertFalse(empty.hasNext() || empty.hasPrevious());
        assertEquals(14, ChatPages.offset(3, 7));
        assertEquals(1, ChatPages.pages(0, 7));
        assertEquals(1, ChatPages.pages(7, 7));
        assertEquals(2, ChatPages.pages(8, 7));
    }

    @Test
    void theFooterLinksOnlyToPagesThereAre() {
        Message footer = Message.of(ChatPages.FOOTER);
        List<Component> first = ChatPages.lines(ChatPages.of(twenty, 1, 7), n -> Component.text("#" + n), footer, "/homes {page}");
        assertEquals(8, first.size());
        assertEquals("#1", plain(first.getFirst()));
        assertEquals("Page 1 of 3 Next »", plain(first.getLast()));
        assertEquals(List.of("/homes 2"), clicks(first.getLast()));

        List<Component> middle = ChatPages.lines(ChatPages.of(twenty, 2, 7), n -> Component.text("#" + n), footer, "/homes {page}");
        assertEquals("Page 2 of 3 « Previous Next »", plain(middle.getLast()));
        assertEquals(List.of("/homes 1", "/homes 3"), clicks(middle.getLast()));

        List<Component> last = ChatPages.lines(ChatPages.of(twenty, 3, 7), n -> Component.text("#" + n), footer, "/homes {page}");
        assertEquals("Page 3 of 3 « Previous", plain(last.getLast()));

        List<Component> one = ChatPages.lines(ChatPages.of(List.of(1, 2), 1, 7), n -> Component.text("#" + n), footer, "/homes {page}");
        assertEquals(2, one.size(), "no footer for a single page");
    }

    @Test
    void aPageReadFromADatabase() {
        ChatPages.Page<String> page = ChatPages.of(List.of("a", "b"), 1_000, 4, 7);
        assertEquals(143, page.pages());
        Message footer = Message.of("<page>/<pages> <next> <previous>");
        assertEquals("4/143 /lookup 5 /lookup 3", plain(ChatPages.lines(page, Component::text, footer, "/lookup {page}").getLast()));
    }

    @Test
    void aCommandCantAddFormatting() {
        Message footer = Message.of("<click:run_command:'<next>'>next</click> <next>");
        Component line = ChatPages.lines(ChatPages.of(twenty, 1, 7), n -> Component.text(n), footer, "/history <red>x {page}").getLast();
        assertEquals("next /history <red>x 2", plain(line));
        assertTrue(clicks(line).getFirst().endsWith("x 2"));
    }

    private static List<String> clicks(Component component) {
        List<String> found = new java.util.ArrayList<>();
        collect(component, found);
        return found;
    }

    private static void collect(Component component, List<String> found) {
        ClickEvent click = component.clickEvent();
        if (click != null && click.payload() instanceof ClickEvent.Payload.Text text && !found.contains(text.value())) {
            found.add(text.value());
        }
        component.children().forEach(child -> collect(child, found));
    }
}
