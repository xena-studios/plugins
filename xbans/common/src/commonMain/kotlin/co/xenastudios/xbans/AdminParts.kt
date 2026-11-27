package co.xenastudios.xbans

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

/** What `/xbans debug` and `/xbans forget` run on, made the first time each runs: not as the server starts. */
internal object AdminParts {
    fun debug(plugin: XBansPlugin, about: () -> Map<String, String>, paste: PasteService?): Debug = Debug(
        plugin.platform, "${XBansPlugin.NAME} ${plugin.version}",
        listOf(
            HealthChecks.threading(plugin.platform), HealthChecks.modules(plugin.modules), HealthChecks.config { plugin.problems },
            HealthChecks.database(plugin.platform) { plugin.backend?.database },
        ),
        about = {
            val backend = plugin.backend
            about() + mapOf(
                "In force" to (backend?.active?.size?.toString() ?: "not read yet"),
                "Network" to when {
                    backend == null -> "no database: ${plugin.failure ?: "opening"}"
                    backend.feed.isOn -> "sharing changes with the other servers" + if (plugin.server.proxy) ", enforced at the proxy's login" else ""
                    else -> "this server alone"
                },
                "IPs" to when {
                    backend == null -> "?"
                    backend.keys.hashing -> "hashed"
                    else -> "as written"
                },
            )
        },
        errors = plugin.log, paste = paste,
    )

    /**
     * `/xbans forget <player>`: what xBans keeps about them besides their punishments, the IPs they
     * used and their name, weight and exemption when last seen. Punishments stay: they're the
     * server's moderation record, and forgetting one would lift a ban (decisions/2026-11-26-xbans-2.md).
     * `/prunehistory` deletes the ones no longer in force.
     */
    fun playerData(plugin: XBansPlugin, players: PlayerCache?): PlayerDataRegistry =
        PlayerDataRegistry(players?.asLookup() ?: PlayerLookup { pendingOf(null) }).apply {
            register("ips", PlayerData { player ->
                val backend = plugin.backend ?: return@PlayerData pendingOf(Outcome.Err("the database isn't open"))
                backend.forget(player).map { r -> if (r is Outcome.Ok) Outcome.Ok(r.value.toLong()) else Outcome.Err((r as Outcome.Err).error.toString()) }
            })
        }
}
