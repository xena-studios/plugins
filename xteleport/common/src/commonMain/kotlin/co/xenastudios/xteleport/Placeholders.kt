package co.xenastudios.xteleport

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.placeholder.PlaceholderSet
import co.xenastudios.core.common.placeholder.placeholders
import co.xenastudios.xteleport.config.XTeleportConfig

/**
 * xTeleport's placeholders, answered from memory, as 1.x had them: `homes`, `homes_limit`,
 * `warps`, `requests` (TPA requests waiting) and `cooldown_<command>` (seconds left, 0 when
 * ready). PlaceholderAPI reads them as `%xteleport_homes%` on Bukkit.
 */
public class Placeholders(private val plugin: XTeleportPlugin) {
    private var made: PlaceholderSet? = null

    /** The ones the docs list, made the first time they're asked for (the reference does; PlaceholderAPI calls [resolve]). */
    public fun set(): PlaceholderSet = made ?: build().also { made = it }

    private fun build(): PlaceholderSet = placeholders(XTeleportPlugin.PLUGIN_ID) {
        player("homes", "How many homes the player has.", "2") { resolve("homes", it) }
        player("homes_limit", "How many they may have; ∞ for no limit.", "5") { resolve("homes_limit", it) }
        global("warps", "How many warps there are.", "7") { resolve("warps", null) }
        player("requests", "Teleport requests waiting for the player.", "1") { resolve("requests", it) }
        player("cooldown_home", "Seconds until the player may use /home again, 0 when ready: any command's name in place of home.", "42") { resolve("cooldown_home", it) }
    }

    /** The value of the placeholder called [name] (without `xteleport_`) for [player], or `null` if there's no such placeholder. */
    public fun resolve(name: String, player: PlayerRef?): String? {
        if (name == "warps") return plugin.places.warps().size.toString()
        if (player == null) return ""
        if (name.startsWith(COOLDOWN)) return ((plugin.cooldownMillis(player, name.removePrefix(COOLDOWN)) + 999) / 1000).toString()
        return when (name) {
            "homes" -> (plugin.homes.cached(player)?.homes?.size ?: 0).toString()
            "homes_limit" -> if (!plugin.server.isOnline(player)) "" else plugin.homeLimits.cached(player).let { if (it == Int.MAX_VALUE) "∞" else it.toString() }
            "requests" -> plugin.requests.pending(player, plugin.config[XTeleportConfig.tpa.expireAfter] * 1000L).size.toString()
            else -> null
        }
    }

    private companion object {
        const val COOLDOWN = "cooldown_"
    }
}
