package co.xenastudios.xrewind.log;

import java.util.UUID;
import org.bukkit.entity.Player;

/**
 * Who did something: a player, or a source like {@code #tnt} or {@code #fire}, which
 * has no uuid.
 */
public record Actor(UUID uuid, String name) {

    public static Actor of(Player player) {
        return new Actor(player.getUniqueId(), player.getName());
    }

    /** A non-player source, named {@code #<what>}. */
    public static Actor source(String name) {
        return new Actor(null, "#" + name);
    }
}
