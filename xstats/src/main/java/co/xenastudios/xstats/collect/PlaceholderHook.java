package co.xenastudios.xstats.collect;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.entity.Player;

/** Fills in a placeholder. Only loaded when PlaceholderAPI is enabled, so a server without it never sees its classes. */
final class PlaceholderHook {

    private PlaceholderHook() {}

    static String fill(Player player, String placeholder) {
        return PlaceholderAPI.setPlaceholders(player, placeholder);
    }
}
