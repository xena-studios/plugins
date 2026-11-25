package co.xenastudios.zchat.bukkit

import me.clip.placeholderapi.PlaceholderAPI
import org.bukkit.entity.Player

/** The only class that touches PlaceholderAPI, so zChat loads without it. Called only once it's found enabled. */
internal object Papi {
    fun value(player: Player, identifier: String): String = PlaceholderAPI.setPlaceholders(player, "%$identifier%")
}
