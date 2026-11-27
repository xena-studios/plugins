package co.xenastudios.xmarket

import co.xenastudios.core.api.map
import co.xenastudios.core.api.pendingOf
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.admin.Debug
import co.xenastudios.core.common.admin.HealthChecks
import co.xenastudios.core.common.admin.PasteService
import co.xenastudios.core.common.admin.PlayerData
import co.xenastudios.core.common.admin.PlayerDataRegistry
import co.xenastudios.core.common.admin.PlayerLookup
import co.xenastudios.core.common.family.PlayerCache

/** What `/xmarket debug` and `/xmarket forget` run on, made the first time each runs. */
internal object AdminParts {
    fun debug(plugin: XMarketPlugin, about: () -> Map<String, String>, paste: PasteService?): Debug = Debug(
        plugin.platform, "${XMarketPlugin.NAME} ${plugin.version}",
        listOf(
            HealthChecks.threading(plugin.platform), HealthChecks.modules(plugin.modules), HealthChecks.config { plugin.problems },
            HealthChecks.database(plugin.platform) { plugin.store?.database },
        ),
        about = {
            about() + mapOf(
                "Economy" to (plugin.economy()?.name ?: "none"),
                "Price list" to "${plugin.prices.categories.sumOf { it.entries.size }} items in ${plugin.prices.categories.size} categories",
                "Items" to plugin.server.features.items,
                "Placeholders" to if (plugin.server.features.placeholders) "PlaceholderAPI" else "none",
            )
        },
        errors = plugin.log, paste = paste,
    )

    /** `/xmarket forget <player>`: their trades and limits' counts. */
    fun playerData(plugin: XMarketPlugin, players: PlayerCache?): PlayerDataRegistry =
        PlayerDataRegistry(players?.asLookup() ?: PlayerLookup { pendingOf(null) }).apply {
            register("trades and limits", PlayerData { player ->
                val store = plugin.store ?: return@PlayerData pendingOf(Outcome.Err("the database isn't open"))
                plugin.limits.forget(player)
                store.forget(player).map { r -> if (r is Outcome.Ok) Outcome.Ok(r.value.toLong()) else Outcome.Err((r as Outcome.Err).error.toString()) }
            })
        }
}
