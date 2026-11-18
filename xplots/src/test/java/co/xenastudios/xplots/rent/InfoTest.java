package co.xenastudios.xplots.rent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import co.xenastudios.core.text.Message;
import co.xenastudios.core.text.Placeholders;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

class InfoTest {

    private static final Info PLOT = new Info("box-6", "128 Coins", "3d", "Steve", "2d 23h", 1, 2, "9×9", "Alex", "50 Coins");

    private static String render(String template, Info info, net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... extra) {
        return PlainTextComponentSerializer.plainText().serialize(Message.of(template).render(info.placeholders(extra)));
    }

    @Test
    void aButtonsOwnValuesWinOverThePlots() {
        // The 3× pay button: its price and time, not the plot's.
        assertEquals("Costs 384 Coins, adds 9d",
            render("Costs <price>, adds <time>", PLOT, Placeholders.text("price", "384 Coins"), Placeholders.text("time", "9d")));
    }

    @Test
    void thePlotsDetailsFillTheRest() {
        assertEquals("box-6: Steve, 2d 23h left, 1/2", render("<plot>: <owner>, <time> left, <slots>/<max-slots>", PLOT));
    }

    @Test
    void timeIsToTheMinute() {
        assertEquals("3d", Info.time(3 * 86_400_000L));
        assertEquals("2d 23h", Info.time(3 * 86_400_000L - 1_000));
        assertEquals("<1m", Info.time(59_000));
    }
}
