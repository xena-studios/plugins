package co.xenastudios.zchat.chat;

import co.xenastudios.core.text.Message;
import co.xenastudios.core.text.Placeholders;
import co.xenastudios.zchat.config.Settings;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** The chat formats, compiled once per config load: which a player gets, and drawing a line with it. */
public final class Formats {

    /** PlaceholderAPI placeholders as written in a format: %vault_prefix%. */
    private static final Pattern PERCENT = Pattern.compile("%([^%\\s<>']+)%");

    /** What players with the colour permission can write: colours and styles, nothing that acts. */
    private static final MiniMessage PLAYER_STYLES = MiniMessage.builder()
        .tags(TagResolver.resolver(
            StandardTags.color(), StandardTags.decorations(), StandardTags.gradient(),
            StandardTags.rainbow(), StandardTags.reset()))
        .build();

    /** PlaceholderAPI values often carry & or § colour codes. */
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
        .character('&').hexColors().useUnusualXRepeatedCharacterHexFormat().build();

    private record GroupFormat(String permission, Message format) {}

    private final boolean enabled;
    private final String colorPermission;
    private final Message defaultFormat;
    private final List<GroupFormat> groups;
    private final boolean usesPlaceholderApi;

    private Formats(boolean enabled, String colorPermission, Message defaultFormat, List<GroupFormat> groups, boolean usesPlaceholderApi) {
        this.enabled = enabled;
        this.colorPermission = colorPermission;
        this.defaultFormat = defaultFormat;
        this.groups = groups;
        this.usesPlaceholderApi = usesPlaceholderApi;
    }

    public static Formats compile(Settings.Formatting settings) {
        List<Map.Entry<String, Settings.Group>> sorted = new ArrayList<>(settings.groups().entrySet());
        sorted.sort(Comparator.comparingInt((Map.Entry<String, Settings.Group> e) -> e.getValue().weight()).reversed());
        boolean papi = usesPercent(settings.defaultFormat());
        List<GroupFormat> groups = new ArrayList<>(sorted.size());
        for (Map.Entry<String, Settings.Group> entry : sorted) {
            Settings.Group group = entry.getValue();
            Message format = group.format().isEmpty() ? settings.defaultFormat() : group.format();
            papi |= usesPercent(format);
            groups.add(new GroupFormat(permission(entry.getKey(), group), withPapiTags(format)));
        }
        papi |= settings.defaultFormat().template().contains("<papi:");
        for (GroupFormat group : groups) {
            papi |= group.format.template().contains("<papi:");
        }
        return new Formats(settings.enabled(), settings.colorPermission(), withPapiTags(settings.defaultFormat()),
            List.copyOf(groups), papi);
    }

    /** A group's permission: its own, or zchat.group.&lt;name&gt; if it has none. */
    public static String permission(String name, Settings.Group group) {
        return group.permission() == null ? "zchat.group." + name : group.permission();
    }

    public boolean enabled() {
        return enabled;
    }

    /** Whether a format uses PlaceholderAPI, to warn when it isn't installed. */
    public boolean usesPlaceholderApi() {
        return usesPlaceholderApi;
    }

    public static boolean papiInstalled() {
        return Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");
    }

    /** Whether a player may style their own messages. */
    public boolean mayStyle(Player player) {
        return !colorPermission.isBlank() && player.hasPermission(colorPermission);
    }

    /** What a styled message says, with its tags taken out: what the filter must check. */
    public static String withoutStyles(String text) {
        return PLAYER_STYLES.stripTags(text);
    }

    /** What a player typed, as it appears in chat: literal, or with their styles if {@code styled}. */
    public static Component playerText(String text, boolean styled) {
        return styled ? PLAYER_STYLES.deserialize(text) : Component.text(text);
    }

    /** One chat line, as everyone sees it. */
    public Component render(Player source, Component displayName, Component message) {
        Message format = defaultFormat;
        for (GroupFormat group : groups) {
            if (group.permission.isBlank() || source.hasPermission(group.permission)) {
                format = group.format;
                break;
            }
        }
        return format.render(
            Placeholders.text("player", source.getName()),
            Placeholders.component("displayname", inert(displayName)),
            Placeholders.component("message", message),
            placeholderApi(source));
    }

    // %name% in a format becomes <papi:'name'>, so the value goes in as text after
    // parsing: a nickname placeholder can't smuggle in a click or hover.
    private static Message withPapiTags(Message format) {
        Matcher matcher = PERCENT.matcher(format.template());
        return matcher.find() ? Message.of(matcher.replaceAll("<papi:'$1'>")) : format;
    }

    private static boolean usesPercent(Message format) {
        return PERCENT.matcher(format.template()).find();
    }

    private static TagResolver placeholderApi(Player player) {
        return TagResolver.resolver("papi", (arguments, context) -> {
            String name = arguments.popOr("<papi> needs a placeholder name").value();
            return Tag.selfClosingInserting(placeholderValue(player, name));
        });
    }

    // A broken expansion must never break chat: its placeholder just shows nothing.
    private static Component placeholderValue(Player player, String name) {
        if (!papiInstalled()) {
            return Component.empty();
        }
        String value;
        try {
            value = Papi.value(player, name);
        } catch (RuntimeException | LinkageError e) {
            return Component.empty();
        }
        // Prefixes come as & or § codes, or as MiniMessage; either way, only styles survive.
        if (value.indexOf('<') >= 0 && value.indexOf('&') < 0 && value.indexOf('§') < 0) {
            return PLAYER_STYLES.deserialize(value);
        }
        return LEGACY.deserialize(value.replace('§', '&'));
    }

    /** A component with its clicks, hovers and insertions taken out, all the way down. */
    static Component inert(Component component) {
        Component stripped = component.clickEvent(null).hoverEvent(null).insertion(null);
        if (stripped.children().isEmpty()) {
            return stripped;
        }
        List<Component> children = new ArrayList<>(stripped.children().size());
        for (Component child : stripped.children()) {
            children.add(inert(child));
        }
        return stripped.children(children);
    }
}
