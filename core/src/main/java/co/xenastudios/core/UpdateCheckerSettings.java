package co.xenastudios.core;

import co.xenastudios.core.config.Comment;
import co.xenastudios.core.text.Message;

/** The {@code update-checker} section every plugin's config has. */
public record UpdateCheckerSettings(
    @Comment({
        "Ask Modrinth for the newest version at startup and every 24 hours. It sends",
        "only the plugin version, the platform and the Minecraft version."})
    boolean enabled,
    @Comment({
        "Logged, and shown to admins when they join, when a newer version is out.",
        "<plugin>, <current>, <latest> and <url> are filled in."})
    Message message) {

    public static final UpdateCheckerSettings DEFAULTS = new UpdateCheckerSettings(
        true,
        Message.of("<prefix><plugin> <highlight><latest></highlight> is out (you have <current>): <click:open_url:'<url>'><hover:show_text:'Open on Modrinth'><highlight><underlined><url>"));
}
