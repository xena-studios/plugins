package co.xenastudios.xstats.bukkit

import co.xenastudios.core.bukkit.ref
import co.xenastudios.xstats.XStatsPlugin
import me.clip.placeholderapi.PlaceholderAPI
import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player

/**
 * Where [XStatsBukkit] reaches PlaceholderAPI: only this class and [Placeholders] name its classes, so
 * the entry point is verified without it, on a server that doesn't have it.
 */
internal object PlaceholderHook {
    fun register(plugin: XStatsPlugin, version: String): Any = Placeholders(plugin, version).also { it.register() }

    fun unregister(expansion: Any) {
        (expansion as Placeholders).unregister()
    }

    /** Fills in another plugin's placeholder for a placeholder stat (`papi:%vault_eco_balance%`). */
    fun fill(player: Player, placeholder: String): String = PlaceholderAPI.setPlaceholders(player, placeholder)
}

/** Fills a placeholder stat through PlaceholderAPI: a class, not a lambda (decisions/2026-11-25-a-lighter-core.md). */
internal object Fill : (Player, String) -> String {
    override fun invoke(player: Player, placeholder: String): String = PlaceholderHook.fill(player, placeholder)
}

/**
 * xStats' PlaceholderAPI placeholders, 1.x's (`%xstats_kills%`, `%xstats_top_kills_monthly_1_name%`),
 * all answered from memory ([co.xenastudios.xstats.Placeholders]). Loaded only while PlaceholderAPI is on.
 */
internal class Placeholders(private val plugin: XStatsPlugin, private val version: String) : PlaceholderExpansion() {
    override fun getIdentifier(): String = "xstats"

    override fun getAuthor(): String = "Xena Studios"

    override fun getVersion(): String = version

    override fun persist(): Boolean = true

    override fun onRequest(player: OfflinePlayer?, params: String): String? =
        co.xenastudios.xstats.Placeholders.resolve(plugin, player?.ref, params)
}
