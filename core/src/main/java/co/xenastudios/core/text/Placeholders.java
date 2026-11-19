package co.xenastudios.core.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/** Placeholders for {@link Message#send}. Text is inserted literally, so player names can't add formatting. */
public final class Placeholders {

    private Placeholders() {}

    public static TagResolver text(String name, Object value) {
        return Placeholder.unparsed(name, String.valueOf(value));
    }

    public static TagResolver component(String name, Component value) {
        return Placeholder.component(name, value);
    }

    /**
     * A command to click, such as {@code <command>} in {@code <click:run_command:'<command>'>}.
     * Inside a tag's argument only pre-processed tags are filled in: {@link #text} there would
     * leave the click running {@code <command>} itself. Escaped, so nothing in it can add
     * formatting; it works outside a click too.
     */
    public static TagResolver click(String name, String command) {
        return TagResolver.resolver(name, Tag.preProcessParsed(MiniMessage.miniMessage().escapeTags(command)));
    }
}
