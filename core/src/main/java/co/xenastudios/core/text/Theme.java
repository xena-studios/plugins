package co.xenastudios.core.text;

import co.xenastudios.core.config.Comment;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/**
 * The look every message shares: a prefix and four styles, used in messages as tags.
 * Changing one here restyles every message that uses it.
 */
public record Theme(
    @Comment({
        "<prefix> starts the default messages. Make it your server's name, such as",
        "\"<dark_gray>[<gradient:#B48CFF:#5EE08A>MyServer</gradient>]</dark_gray> \", or blank for none."})
    String prefix,
    @Comment("The colour of message text that has no tag of its own.") TextStyle base,
    @Comment("<highlight>: names and values, such as a player or a speed.") TextStyle highlight,
    @Comment("<success>: something turned on or done.") TextStyle success,
    @Comment("<error>: something refused or turned off.") TextStyle error) {

    public static final Theme DEFAULTS = new Theme(
        "<#B48CFF>»</#B48CFF> ",
        TextStyle.parse("<gray>"),
        TextStyle.parse("<#B48CFF>"),
        TextStyle.parse("<#5EE08A>"),
        TextStyle.parse("<#FF6B6B>"));

    /** The theme as MiniMessage tags, ready to render with. */
    Compiled compile() {
        TagResolver styles = TagResolver.resolver(
            style("highlight", highlight),
            style("success", success),
            style("error", error));
        Component prefixed = prefix.isEmpty() ? Component.empty() : MiniMessage.miniMessage().deserialize(prefix, styles);
        return new Compiled(TagResolver.resolver(styles, Placeholder.component("prefix", prefixed)), base.style());
    }

    private static TagResolver style(String name, TextStyle style) {
        return TagResolver.resolver(name, Tag.styling(builder -> builder.merge(style.style())));
    }

    record Compiled(TagResolver tags, Style base) {}
}
