package co.xenastudios.xvanish

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.placeholder.PlaceholderSet
import co.xenastudios.core.common.placeholder.placeholders

/**
 * xVanish's placeholders, answered from memory: `vanished` (`true` or `false`), `online` (the
 * player count without the vanished), `count` (how many are vanished) and `level`.
 * PlaceholderAPI reads them as `%xvanish_vanished%` on Bukkit.
 */
public class Placeholders(private val plugin: XVanishPlugin) {
    private var made: PlaceholderSet? = null

    /** The ones the docs list, made the first time they're asked for. */
    public fun set(): PlaceholderSet = made ?: build().also { made = it }

    private fun build(): PlaceholderSet = placeholders(XVanishPlugin.PLUGIN_ID) {
        player("vanished", "Whether the player is vanished: true or false.", "false") { resolve("vanished", it) }
        global("online", "Players online, less the vanished: what a scoreboard or tab header should show.", "12") { resolve("online", null) }
        global("count", "How many players are vanished here.", "1") { resolve("count", null) }
        player("level", "The player's vanish level (xvanish.level.<n>), 1 without one.", "1") { resolve("level", it) }
    }

    /** The value of the placeholder called [name] (without `xvanish_`) for [player], or `null` if there's no such placeholder. */
    public fun resolve(name: String, player: PlayerRef?): String? = when (name) {
        "online" -> plugin.visibleOnlineCount().toString()
        "count" -> plugin.vanished().size.toString()
        "vanished" -> if (player == null) "" else plugin.isVanished(player).toString()
        "level" -> if (player == null) "" else plugin.level(player).toString()
        else -> null
    }
}
