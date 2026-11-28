package co.xenastudios.xlimbo

import co.xenastudios.core.api.newPending
import co.xenastudios.core.api.pendingOf
import co.xenastudios.core.api.resolve
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.admin.Debug
import co.xenastudios.core.common.admin.HealthChecks
import co.xenastudios.core.common.admin.PasteService
import co.xenastudios.core.common.admin.PlayerData
import co.xenastudios.core.common.admin.PlayerDataRegistry
import co.xenastudios.core.common.admin.PlayerLookup
import co.xenastudios.core.common.family.PlayerCache
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.scheduler.now
import co.xenastudios.xlimbo.config.XLimboConfig

/** What `/xlimbo debug` and `/xlimbo forget` run on, made the first time each runs: not as the server starts. */
internal object AdminParts {
    fun debug(plugin: XLimboPlugin, about: () -> Map<String, String>, paste: PasteService?): Debug = Debug(
        plugin.platform, "${XLimboPlugin.NAME} ${plugin.version}",
        listOf(HealthChecks.threading(plugin.platform), HealthChecks.modules(plugin.modules), HealthChecks.config { plugin.problems }),
        about = {
            val config = plugin.config
            about() + mapOf(
                "Send back" to "${plugin.route.name.lowercase()} to ${plugin.destination}",
                "Server check" to if (plugin.isServerCheckEnabled()) "on, ${config[XLimboConfig.serverCheck.address]}: " +
                    when (plugin.returns?.up) { true -> "up"; false -> "down"; null -> "not asked yet" } else "off",
                "In limbo" to plugin.playersInLimbo().size.toString(),
                "Waiting" to plugin.queueSize().toString(),
                "World ready" to plugin.server.isReady().toString(),
            )
        },
        errors = plugin.log, paste = paste,
    )

    /** `/xlimbo forget <player>`: their return tries (the three-in-five-minutes limit) and their /join cooldown. */
    fun playerData(plugin: XLimboPlugin, players: PlayerCache?): PlayerDataRegistry =
        PlayerDataRegistry(players?.asLookup() ?: PlayerLookup { pendingOf(null) }).apply {
            register("returns", PlayerData { player ->
                val done = newPending<Outcome<Long, String>>()
                plugin.platform.scheduler.now(Where.Global) { done.resolve(Outcome.Ok(plugin.forget(player))) }
                done
            })
        }
}
