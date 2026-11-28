package co.xenastudios.xvote

import co.xenastudios.core.api.newPending
import co.xenastudios.core.api.onSettled
import co.xenastudios.core.api.pendingOf
import co.xenastudios.core.api.resolve
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.admin.Debug
import co.xenastudios.core.common.admin.HealthChecks
import co.xenastudios.core.common.admin.PlayerData
import co.xenastudios.core.common.admin.PlayerDataRegistry
import co.xenastudios.core.common.admin.PlayerLookup
import co.xenastudios.xvote.store.Ledger
import co.xenastudios.xvote.store.Tx

/** What `/xvote debug` and `/xvote forget` run on, made the first time each runs: not as the server starts. */
internal object AdminParts {
    fun debug(plugin: XVotePlugin): Debug = Debug(
        plugin.platform, "${XVotePlugin.NAME} ${plugin.version}",
        listOf(
            HealthChecks.threading(plugin.platform), HealthChecks.modules(plugin.modules), HealthChecks.config { plugin.problems },
            HealthChecks.database(plugin.platform) { plugin.backend?.database },
        ),
        about = {
            val backend = plugin.backend
            val status = plugin.receiver?.status
            plugin.about() + mapOf(
                "Voters" to plugin.voters.size.toString(),
                "Receiver" to when {
                    status == null -> "off"
                    status.listening -> "listening on ${status.port}, Votifier ${if (status.v1) "v1 and v2" else "v2"}, ${status.tokens} tokens"
                    else -> "stopped on ${status.port}"
                },
                "Network" to when {
                    backend == null -> "no database: ${plugin.failure ?: "opening"}"
                    backend.shared -> "sharing votes with the other servers" + if (plugin.server.proxy) ", received on the proxy" else ""
                    else -> "this server alone"
                },
                "Economy" to (plugin.economy?.let { if (it.ready) it.name else "${it.name}, not ready" } ?: "none"),
                "Reward types" to plugin.rewardTypes.keys.sorted().joinToString(", ").ifEmpty { "none" },
            )
        },
        errors = plugin.log, paste = plugin.paste,
    )

    /**
     * `/xvote forget <player>`: everything xVote keeps about them, their votes (counted and waiting),
     * their totals and streak, and the reward items held for them.
     */
    fun playerData(plugin: XVotePlugin): PlayerDataRegistry =
        PlayerDataRegistry(plugin.cache?.asLookup() ?: PlayerLookup { pendingOf(null) }).apply {
            register("votes", PlayerData { player ->
                val backend = plugin.backend ?: return@PlayerData pendingOf(Outcome.Err("the database isn't open"))
                val db = backend.database
                val done = newPending<Outcome<Long, String>>()
                db.transaction { session ->
                    Tx.run(session, db.sql) { tx ->
                        val count = Ledger.forget(tx, player)
                        tx.publish(backend.feed, "player", player.toString())
                        count
                    }
                }.onSettled { result, error ->
                    if (result is Outcome.Ok) {
                        plugin.voters.remove(player)
                        done.resolve(Outcome.Ok(result.value.toLong()))
                    } else {
                        done.resolve(Outcome.Err((result as? Outcome.Err)?.error?.detail ?: error.toString()))
                    }
                }
                done
            })
        }
}
