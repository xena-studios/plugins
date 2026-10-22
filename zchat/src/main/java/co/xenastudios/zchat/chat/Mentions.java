package co.xenastudios.zchat.chat;

import co.xenastudios.core.text.Placeholders;
import co.xenastudios.zchat.config.Settings;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import org.bukkit.entity.Player;

/** Finds players named in a message, highlights them, and says who to ping. */
final class Mentions {

    /** A name as a word on its own, with or without @: Minecraft names are 3 to 16 of these. */
    private static final Pattern NAME = Pattern.compile("(?<![\\w@])@?[A-Za-z0-9_]{3,16}(?![\\w])");

    /**
     * The message with mentions highlighted.
     *
     * @param pinged the players mentioned by name
     * @param everyone whether @everyone was used, by someone allowed to
     */
    record Applied(Component message, Set<Player> pinged, boolean everyone) {}

    private Mentions() {}

    static Applied apply(Component message, Player sender, Settings.Mentions settings, ChatState state) {
        Set<Player> pinged = new HashSet<>();
        boolean[] everyone = {false};
        boolean mayPingEveryone = !settings.everyonePermission().isBlank() && sender.hasPermission(settings.everyonePermission());
        Component highlighted = message.replaceText(TextReplacementConfig.builder()
            .match(NAME)
            .replacement((match, original) -> {
                String raw = match.group();
                boolean at = raw.charAt(0) == '@';
                String name = at ? raw.substring(1) : raw;
                if (at && mayPingEveryone && name.equalsIgnoreCase("everyone")) {
                    everyone[0] = true;
                    return settings.format().render(Placeholders.text("name", "everyone"));
                }
                Player target = state.online(name);
                if (target == null || target == sender || !sender.canSee(target)) {
                    return original;
                }
                pinged.add(target);
                return settings.format().render(Placeholders.text("name", target.getName()));
            })
            .build());
        return new Applied(highlighted, pinged, everyone[0]);
    }
}
