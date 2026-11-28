package co.xenastudios.xstats

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
import co.xenastudios.xstats.data.Engine
import co.xenastudios.xstats.store.Q
import co.xenastudios.xstats.store.forget
import co.xenastudios.xstats.data.forgot

/** What `/xstats debug` and `/xstats forget` run on, made the first time each runs: not as the server starts. */
internal object AdminParts {
    fun debug(plugin: XStatsPlugin): Debug = Debug(
        plugin.platform, "${XStatsPlugin.NAME} ${plugin.version}",
        listOf(
            HealthChecks.threading(plugin.platform), HealthChecks.modules(plugin.modules), HealthChecks.config { plugin.problems },
            HealthChecks.database(plugin.platform) { plugin.database },
        ),
        about = {
            val engine = plugin.engine
            plugin.server.about() + mapOf(
                "Players known" to engine.playerCount.toString(),
                "Stats" to plugin.catalog.snapshot.stats.joinToString(", ") { it.id },
                "Last save" to (engine.lastSave ?: Engine.LastSave(0, 0, 0, null)).let { if (it.at == 0L) "not yet" else "${it.players} players, ${it.millis} ms" + (it.problem?.let { p -> ", $p" } ?: "") },
                "Waiting" to "${engine.waiting} changes, ${engine.dropped} dropped",
                "Network" to when {
                    plugin.database == null -> "no database: ${plugin.failure ?: "opening"}"
                    engine.feed != null -> "sharing stats with the other servers" + if (plugin.server.proxy) ", read on the proxy" else ""
                    else -> "this server alone"
                },
                "Website" to if (plugin.extrasIfMade?.webPort?.let { it > 0 } == true) "on port ${plugin.extrasIfMade?.webPort}" else "off",
                "Integrations" to Integrations.describe(plugin),
            )
        },
        errors = plugin.log, paste = plugin.paste,
    )

    /** `/xstats forget <player>`: every row xStats keeps about them, on every server, and them in memory. */
    fun playerData(plugin: XStatsPlugin): PlayerDataRegistry =
        PlayerDataRegistry(plugin.cache?.asLookup() ?: PlayerLookup { pendingOf(null) }).apply {
            register("stats", PlayerData { player ->
                val db = plugin.database ?: return@PlayerData pendingOf(Outcome.Err("the database isn't open"))
                val store = plugin.engine.store ?: return@PlayerData pendingOf(Outcome.Err("the database isn't open"))
                val done = newPending<Outcome<Long, String>>()
                db.session { session ->
                    val result = Q.run(session, db.sql) { q ->
                        val count = store.forget(q, player, plugin.engine.feed)
                        plugin.engine.forgot(player)
                        count
                    }
                    Outcome.Ok(result)
                }.onSettled { result, error ->
                    val ran = (result as? Outcome.Ok)?.value
                    done.resolve(
                        when (ran) {
                            is Outcome.Ok -> Outcome.Ok(ran.value)
                            is Outcome.Err -> Outcome.Err(ran.error.detail)
                            null -> Outcome.Err(error.toString())
                        },
                    )
                }
                done
            })
        }
}
