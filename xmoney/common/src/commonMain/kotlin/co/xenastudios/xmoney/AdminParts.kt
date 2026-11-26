package co.xenastudios.xmoney

import co.xenastudios.core.api.map
import co.xenastudios.core.api.pendingOf
import co.xenastudios.core.common.Outcome as DbOutcome
import co.xenastudios.core.common.admin.Debug
import co.xenastudios.core.common.admin.HealthChecks
import co.xenastudios.core.common.admin.PasteService
import co.xenastudios.core.common.admin.PlayerData
import co.xenastudios.core.common.admin.PlayerDataRegistry
import co.xenastudios.core.common.admin.PlayerLookup
import co.xenastudios.core.common.family.PlayerCache

/** What `/xmoney debug` and `/xmoney forget` run on, made the first time each runs: not as the server starts. */
internal object AdminParts {
    fun debug(plugin: XMoneyPlugin, about: () -> Map<String, String>, paste: PasteService?): Debug = Debug(
        plugin.platform, "${XMoneyPlugin.NAME} ${plugin.version}",
        listOf(
            HealthChecks.threading(plugin.platform), HealthChecks.modules(plugin.modules), HealthChecks.config { plugin.problems },
            HealthChecks.database(plugin.platform) { plugin.database },
        ),
        about = about, errors = plugin.log, paste = paste,
    )

    fun playerData(plugin: XMoneyPlugin, players: PlayerCache?): PlayerDataRegistry =
        PlayerDataRegistry(players?.asLookup() ?: PlayerLookup { pendingOf(null) }).apply {
            register("balances", PlayerData { player ->
                val ledger = plugin.ledger ?: return@PlayerData pendingOf(DbOutcome.Err("the database isn't open"))
                ledger.forget(player).map { r -> if (r is DbOutcome.Ok) DbOutcome.Ok(r.value) else DbOutcome.Err((r as DbOutcome.Err).error.toString()) }
            })
        }
}
