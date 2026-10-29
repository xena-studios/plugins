package co.xenastudios.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.xenastudios.core.text.Placeholders;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

class UpdateCheckerTest {

    private static Component notice(String version) {
        return UpdateCheckerSettings.DEFAULTS.message().chat(
            Placeholders.text("plugin", "xUtilities"),
            Placeholders.text("current", "2.0.0"),
            Placeholders.text("latest", version),
            UpdateChecker.url("xutilities", version));
    }

    // The first click in the component, depth first.
    private static ClickEvent click(Component component) {
        if (component.clickEvent() != null) {
            return component.clickEvent();
        }
        for (Component child : component.children()) {
            ClickEvent found = click(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    @Test
    void theNoticeLinksToTheNewVersion() {
        Component notice = notice("2.1.0");
        String url = "https://modrinth.com/plugin/xutilities/version/2.1.0";
        assertEquals(ClickEvent.openUrl(url), click(notice), "the click opens the page, not \"<url>\"");
        String text = PlainTextComponentSerializer.plainText().serialize(notice);
        assertEquals("» xUtilities 2.1.0 is out (you have 2.0.0): " + url, text);
    }

    @Test
    void aVersionNumberCantAddTags() {
        Component notice = notice("2.1.0'><red>x");
        assertEquals(ClickEvent.openUrl("https://modrinth.com/plugin/xutilities/version/2.1.0%27%3E%3Cred%3Ex"), click(notice));
        // Shown as written, so no tag took effect.
        assertTrue(PlainTextComponentSerializer.plainText().serialize(notice).startsWith("» xUtilities 2.1.0'><red>x is out"));
    }
}
