package co.xenastudios.zchat.chat;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.entity.Player;

/**
 * The only class that touches PlaceholderAPI, so zChat loads without it. Call only after
 * checking {@link Formats#papiInstalled()}.
 */
final class Papi {

    private Papi() {}

    static String value(Player player, String placeholder) {
        return PlaceholderAPI.setPlaceholders(player, "%" + placeholder + "%");
    }
}
