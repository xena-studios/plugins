package co.xenastudios.xrewind

import co.xenastudios.core.api.map
import co.xenastudios.core.api.pendingOf
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.admin.Debug
import co.xenastudios.core.common.admin.HealthChecks
import co.xenastudios.core.common.admin.PlayerData
import co.xenastudios.core.common.admin.PlayerDataRegistry
import co.xenastudios.core.common.admin.PlayerLookup
import co.xenastudios.core.common.db.SqlValue

/** What `/xrewind debug` and `/xrewind forget` run on, made the first time each runs. */
internal object AdminParts {
    fun debug(plugin: XRewindPlugin): Debug = Debug(
        plugin.platform, "${XRewindPlugin.NAME} ${plugin.version}",
        listOf(
            HealthChecks.threading(plugin.platform), HealthChecks.modules(plugin.modules), HealthChecks.config { plugin.problems },
            HealthChecks.database(plugin.platform) { plugin.database },
        ),
        about = {
            plugin.about() + mapOf(
                "Logging" to "${plugin.writer.written} written, ${plugin.queue.waiting} waiting, ${plugin.queue.missed + plugin.writer.dropped} missed" +
                    (plugin.writer.problem?.let { "; $it" } ?: ""),
                "Rollback" to if (plugin.rollbacks.isRunning) "running" else "none running",
            )
        },
        errors = plugin.log, paste = plugin.paste,
    )

    /**
     * `/xrewind forget <player>`: their chat, commands and joins and quits, which are about them.
     * What they did to the world (blocks, containers, kills, signs) is kept, as a ban is in xBans:
     * it's a record of what happened there, which a rollback needs, and a griefer could otherwise
     * erase their own tracks by asking (decisions/2026-11-27-xrewind-2.md).
     */
    fun playerData(plugin: XRewindPlugin): PlayerDataRegistry =
        PlayerDataRegistry(plugin.cache?.asLookup() ?: PlayerLookup { pendingOf(null) }).apply {
            register("chat, commands and sessions", PlayerData { player ->
                val db = plugin.database ?: return@PlayerData pendingOf(Outcome.Err("the database isn't open"))
                var most = 0L
                var least = 0L
                player.uuid.toLongs { m, l ->
                    most = m
                    least = l
                }
                val users = "SELECT id FROM {p}user WHERE uuid_most = ? AND uuid_least = ?"
                db.transaction { session ->
                    val texts = session.execute(db.sql.raw("DELETE FROM {p}text WHERE kind IN (1, 2) AND user IN ($users)", SqlValue.Integer(most), SqlValue.Integer(least)))
                    if (texts is Outcome.Err) return@transaction texts
                    val sessions = session.execute(db.sql.raw("DELETE FROM {p}session WHERE user IN ($users)", SqlValue.Integer(most), SqlValue.Integer(least)))
                    if (sessions is Outcome.Err) return@transaction sessions
                    Outcome.Ok(((texts as Outcome.Ok).value + (sessions as Outcome.Ok).value).toLong())
                }.map { r -> if (r is Outcome.Ok) Outcome.Ok(r.value) else Outcome.Err((r as Outcome.Err).error.toString()) }
            })
            plugin.cache?.let { register("the family's player cache", it.asPlayerData()) }
        }
}
