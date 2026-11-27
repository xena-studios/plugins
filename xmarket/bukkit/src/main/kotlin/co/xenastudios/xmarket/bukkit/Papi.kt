package co.xenastudios.xmarket.bukkit

import me.clip.placeholderapi.PlaceholderAPI
import org.bukkit.entity.Player

/**
 * Requirements' placeholders through PlaceholderAPI, while it's installed: the one class that
 * names PlaceholderAPI's, so it's never loaded without it (r8/xmarket.pro pins it).
 */
public object PapiHook {
    @JvmStatic
    public fun fill(player: Player, text: String): String = PlaceholderAPI.setPlaceholders(player, text)
}
