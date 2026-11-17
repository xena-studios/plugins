package co.xenastudios.xrewind.query;

import co.xenastudios.core.text.Durations;
import co.xenastudios.core.text.Placeholders;
import co.xenastudios.xrewind.config.XRConfig;
import co.xenastudios.xrewind.log.Actions;
import co.xenastudios.xrewind.storage.Clock;
import java.time.Duration;
import net.kyori.adventure.text.Component;

/** Turns rows into the lines a lookup sends. */
public final class Formats {

    private Formats() {}

    public static Component line(XRConfig.LookupMessages messages, Row row) {
        XRConfig.Words words = messages.words();
        String action;
        String block;
        if (row.table() == Row.Table.SESSION) {
            action = row.action() == Actions.JOIN ? words.joined() : words.quit();
            block = "";
        } else {
            boolean broke = row.action() == Actions.BREAK;
            action = broke ? words.broke() : words.placed();
            block = name(broke ? row.before() : row.after());
        }
        long ago = Math.max(0, Clock.now() - row.time());
        return (row.rolledBack() ? messages.lineUndone() : messages.line()).chat(
            Placeholders.text("ago", Durations.format(Duration.ofSeconds(ago))),
            Placeholders.text("user", row.user()),
            Placeholders.text("action", action),
            Placeholders.text("block", block),
            Placeholders.text("x", row.x()),
            Placeholders.text("y", row.y()),
            Placeholders.text("z", row.z()),
            Placeholders.text("world", row.world()));
    }

    /** {@code minecraft:oak_door[half=lower]} as players know it: {@code oak_door}. */
    public static String name(String state) {
        if (state == null) {
            return "";
        }
        int bracket = state.indexOf('[');
        String id = bracket < 0 ? state : state.substring(0, bracket);
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }
}
