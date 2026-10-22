package co.xenastudios.core.text;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/**
 * A MiniMessage message from the config, which can use the {@link Theme}'s tags. Parsed
 * the first time it's used and kept until the theme changes; parsed again only when
 * it's sent with placeholders. A blank message sends nothing.
 */
public final class Message {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final Message EMPTY = new Message("");

    // Each plugin carries its own copy of the core, so this is the plugin's theme.
    private static volatile Theme.Compiled theme = Theme.DEFAULTS.compile();

    private final String template;
    private volatile Parsed parsed;

    private Message(String template) {
        this.template = template;
    }

    public static Message of(String template) {
        return template.isBlank() ? EMPTY : new Message(template);
    }

    public static Message empty() {
        return EMPTY;
    }

    /** Sets the theme every message renders with. */
    public static void theme(Theme theme) {
        Message.theme = theme.compile();
    }

    public String template() {
        return template;
    }

    public boolean isEmpty() {
        return this == EMPTY;
    }

    /** The message as a component, with these placeholders filled in. */
    public Component render(TagResolver... placeholders) {
        if (isEmpty()) {
            return Component.empty();
        }
        Theme.Compiled current = theme;
        if (placeholders.length > 0) {
            return MINI_MESSAGE.deserialize(template, TagResolver.resolver(current.tags(), TagResolver.resolver(placeholders)));
        }
        Parsed cached = parsed;
        if (cached == null || cached.theme != current) {
            cached = new Parsed(current, MINI_MESSAGE.deserialize(template, current.tags()));
            parsed = cached;
        }
        return cached.component;
    }

    /** Sends the message in the theme's base colour, unless it's blank. */
    public void send(Audience audience, TagResolver... placeholders) {
        if (!isEmpty()) {
            audience.sendMessage(chat(placeholders));
        }
    }

    /** The message as sent in chat: in the theme's base colour where it sets none. */
    public Component chat(TagResolver... placeholders) {
        Component rendered = render(placeholders);
        Theme.Compiled current = theme;
        return current.base().isEmpty() ? rendered : Component.text().style(current.base()).append(rendered).build();
    }

    private record Parsed(Theme.Compiled theme, Component component) {}

    @Override
    public boolean equals(Object other) {
        return other instanceof Message message && template.equals(message.template);
    }

    @Override
    public int hashCode() {
        return template.hashCode();
    }

    @Override
    public String toString() {
        return template;
    }
}
