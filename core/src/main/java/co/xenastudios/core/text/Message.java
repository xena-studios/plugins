package co.xenastudios.core.text;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/**
 * A MiniMessage message from the config. Parsed once when the config loads; parsed
 * again only when it's sent with placeholders. A blank message sends nothing.
 */
public final class Message {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final Message EMPTY = new Message("");

    private final String template;
    private final Component parsed;

    private Message(String template) {
        this.template = template;
        this.parsed = template.isBlank() ? Component.empty() : MINI_MESSAGE.deserialize(template);
    }

    public static Message of(String template) {
        return template.isBlank() ? EMPTY : new Message(template);
    }

    public static Message empty() {
        return EMPTY;
    }

    public String template() {
        return template;
    }

    public boolean isEmpty() {
        return this == EMPTY;
    }

    /** The message as a component, with these placeholders filled in. */
    public Component render(TagResolver... placeholders) {
        return placeholders.length == 0 ? parsed : MINI_MESSAGE.deserialize(template, placeholders);
    }

    /** Sends the message, unless it's blank. */
    public void send(Audience audience, TagResolver... placeholders) {
        if (!isEmpty()) {
            audience.sendMessage(render(placeholders));
        }
    }

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
