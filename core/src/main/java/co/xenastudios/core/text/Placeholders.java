package co.xenastudios.core.text;

import net.kyori.adventure.text.Component;
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
}
