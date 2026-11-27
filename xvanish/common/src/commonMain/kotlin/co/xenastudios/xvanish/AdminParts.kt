package co.xenastudios.xvanish

import co.xenastudios.core.api.pendingOf
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.admin.Debug
import co.xenastudios.core.common.admin.HealthChecks
import co.xenastudios.core.common.admin.PasteService
import co.xenastudios.core.common.admin.PlayerData
import co.xenastudios.core.common.admin.PlayerDataRegistry
import co.xenastudios.core.common.admin.PlayerLookup
import co.xenastudios.core.common.family.PlayerCache

/** What `/xvanish debug` and `/xvanish forget` run on, made the first time each runs: not as the server starts. */
internal object AdminParts {
    fun debug(plugin: XVanishPlugin, about: () -> Map<String, String>, paste: PasteService?): Debug {
        val checks = mutableListOf(HealthChecks.threading(plugin.platform), HealthChecks.modules(plugin.modules), HealthChecks.config { plugin.problems })
        if (plugin.network != null) checks += HealthChecks.database(plugin.platform) { plugin.network?.database }
        return Debug(
            plugin.platform, "${XVanishPlugin.NAME} ${plugin.version}", checks,
            about = {
                about() + mapOf(
                    "Hiding" to plugin.server.features.hiding,
                    "Vanished" to plugin.vanished().size.toString(),
                    "Switches here" to plugin.server.features.switches.sorted().joinToString(", ") { Switch.NAMES[it] },
                    "Network" to if (plugin.network != null) "on" else "off",
                )
            },
            errors = plugin.log, paste = paste,
        )
    }

    /** `/xvanish forget <player>`: whether they stay vanished, in `vanished.txt` and the network's table. */
    fun playerData(plugin: XVanishPlugin, players: PlayerCache?): PlayerDataRegistry =
        PlayerDataRegistry(players?.asLookup() ?: PlayerLookup { pendingOf(null) }).apply {
            register("vanish", PlayerData { player ->
                val removed = if (plugin.remembered.remove(player)) 1L else 0L
                players?.setVanished(player, false)
                val network = plugin.network ?: return@PlayerData pendingOf(Outcome.Ok(removed))
                network.forget(player)
            })
        }
}
