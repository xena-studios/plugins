package co.xenastudios.xplots

import co.xenastudios.core.api.pendingOf
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.admin.Debug
import co.xenastudios.core.common.admin.HealthChecks
import co.xenastudios.core.common.admin.PlayerData
import co.xenastudios.core.common.admin.PlayerDataRegistry
import co.xenastudios.core.common.admin.PlayerLookup
import co.xenastudios.core.common.locked
import co.xenastudios.xplots.api.EndReason

/** What `/xplots debug` and `/xplots forget` run on, made the first time each runs. */
internal object AdminParts {
    fun debug(plugin: XPlotsPlugin): Debug = Debug(
        plugin.platform, "${XPlotsPlugin.NAME} ${plugin.version}",
        listOf(
            HealthChecks.threading(plugin.platform), HealthChecks.modules(plugin.modules), HealthChecks.config { plugin.problems },
            HealthChecks.database(plugin.platform) { plugin.database },
        ),
        about = {
            val fields = plugin.plots.fields()
            val plots = fields.flatMap { it.plots() }
            plugin.about() + mapOf(
                "Plots" to "${fields.size} fields, ${plots.size} plots, ${plots.count { !it.isFree }} rented" + (plugin.failure?.let { "; $it" } ?: ""),
                "Rent" to (plugin.rentals.wallet.item()?.let { "the item ${it.material}" } ?: plugin.hooks.economy()?.name ?: "no economy yet"),
                "Renewing" to plots.count { plugin.renewals.active(it) }.toString(),
                "Regions" to (plugin.server.regions()?.let { "WorldGuard" } ?: "none"),
            )
        },
        errors = plugin.log, paste = plugin.paste,
    )

    /**
     * `/xplots forget <player>`: their memberships, and the plots they rent, whose rents end as an
     * eviction does (put back if their field says so), and renewal notices kept for them. Their name goes with them.
     */
    fun playerData(plugin: XPlotsPlugin): PlayerDataRegistry =
        PlayerDataRegistry(plugin.cache?.asLookup() ?: PlayerLookup { pendingOf(null) }).apply {
            register("plots and memberships", PlayerData { player ->
                if (!plugin.isReady) return@PlayerData pendingOf(Outcome.Err("the plots haven't been read yet"))
                var count = 0L
                for (plot in plugin.plots.owned(player).toList()) {
                    if (plugin.rentals.end(plot, EndReason.EVICTED)) count++
                }
                for (field in plugin.plots.fields()) {
                    for (plot in field.plots()) {
                        val removed = locked(plugin.lock) {
                            val lease = plot.lease ?: return@locked false
                            if (player !in lease.members) return@locked false
                            plugin.plots.lease(plot, lease.with(members = lease.members - player))
                            true
                        }
                        if (removed) {
                            count++
                            plugin.rentals.saved(plot)
                        }
                    }
                }
                count += plugin.renewals.forget(player)
                pendingOf(Outcome.Ok(count))
            })
            plugin.cache?.let { register("the family's player cache", it.asPlayerData()) }
        }
}
