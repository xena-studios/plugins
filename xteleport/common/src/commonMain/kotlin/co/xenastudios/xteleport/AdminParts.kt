package co.xenastudios.xteleport

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

/** What `/xteleport debug` and `/xteleport forget` run on, made the first time each runs: not as the server starts. */
internal object AdminParts {
    fun debug(plugin: XTeleportPlugin, about: () -> Map<String, String>, paste: PasteService?): Debug = Debug(
        plugin.platform, "${XTeleportPlugin.NAME} ${plugin.version}",
        listOf(
            HealthChecks.threading(plugin.platform), HealthChecks.modules(plugin.modules), HealthChecks.config { plugin.problems },
            HealthChecks.database(plugin.platform) { plugin.store?.database },
        ),
        about = { about() + mapOf("Teleports" to plugin.server.features.loading, "Economy" to (plugin.server.economy()?.name ?: "none")) },
        errors = plugin.log, paste = paste,
    )

    /** `/xteleport forget <player>`: their homes and TPA setting, in the database and in memory, and their cooldowns. */
    fun playerData(plugin: XTeleportPlugin, players: PlayerCache?): PlayerDataRegistry =
        PlayerDataRegistry(players?.asLookup() ?: PlayerLookup { pendingOf(null) }).apply {
            register("homes", PlayerData { player ->
                val store = plugin.store ?: return@PlayerData pendingOf(Outcome.Err("the database isn't open"))
                plugin.homes.forget(player)
                plugin.requests.forget(player)
                store.forget(player).map { r -> if (r is Outcome.Ok) Outcome.Ok(r.value.toLong()) else Outcome.Err((r as Outcome.Err).error.toString()) }
            })
        }
}
