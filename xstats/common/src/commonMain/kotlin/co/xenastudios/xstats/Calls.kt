package co.xenastudios.xstats

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.event.Event as ApiEvent
import co.xenastudios.core.api.event.EventBus
import co.xenastudios.core.api.event.EventType
import co.xenastudios.core.api.newPending
import co.xenastudios.core.api.onSettled
import co.xenastudios.core.api.pendingOf
import co.xenastudios.core.api.resolve
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.admin.PasteService
import co.xenastudios.core.common.admin.RecentErrors
import co.xenastudios.core.common.command.AdminCommands
import co.xenastudios.core.common.command.CommandDispatcher
import co.xenastudios.core.common.command.CommandRegistrar
import co.xenastudios.core.common.command.ModuleReloader
import co.xenastudios.core.common.command.OnlinePlayers
import co.xenastudios.core.common.config.Config
import co.xenastudios.core.common.db.ChangeFeed
import co.xenastudios.core.common.db.Database
import co.xenastudios.core.common.db.DbError
import co.xenastudios.core.common.db.migrate
import co.xenastudios.core.common.event.SimpleEventBus
import co.xenastudios.core.common.family.PlayerCache
import co.xenastudios.core.common.i18n.LanguageSource
import co.xenastudios.core.common.i18n.MessageKey
import co.xenastudios.core.common.i18n.Translations
import co.xenastudios.core.common.locked
import co.xenastudios.core.common.module.Module
import co.xenastudios.core.common.module.ModuleContext
import co.xenastudios.core.common.module.ModuleSet
import co.xenastudios.core.common.permission.PermissionRegistrar
import co.xenastudios.core.common.platform.Platform
import co.xenastudios.core.common.scheduler.TICK
import co.xenastudios.core.common.scheduler.Task
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.update.UpdateChecker
import co.xenastudios.core.common.update.UpdateSettings
import co.xenastudios.xstats.api.CustomStat
import co.xenastudios.xstats.api.LeaderboardEntry
import co.xenastudios.xstats.api.Period
import co.xenastudios.xstats.api.ProfileSection
import co.xenastudios.xstats.api.StatInfo
import co.xenastudios.xstats.api.StatUnit
import co.xenastudios.xstats.api.StatsProfile
import co.xenastudios.xstats.api.XStats
import co.xenastudios.xstats.api.XStatsApi
import co.xenastudios.xstats.api.event.LeaderboardChangeEvent
import co.xenastudios.xstats.api.event.StatChangeEvent
import co.xenastudios.xstats.api.event.StatsSavedEvent
import co.xenastudios.xstats.config.XStatsConfig
import co.xenastudios.xstats.data.Board
import co.xenastudios.xstats.data.Calendar
import co.xenastudios.xstats.data.Engine
import co.xenastudios.xstats.data.Event
import co.xenastudios.xstats.data.PlayerRecord
import co.xenastudios.xstats.data.Zones
import co.xenastudios.xstats.stat.Catalog
import co.xenastudios.xstats.stat.Definitions
import co.xenastudios.xstats.store.Q
import co.xenastudios.xstats.store.Store
import co.xenastudios.xstats.store.Tables
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

// What runs with the first save, the server stopping or another plugin's first call: XStatsPlugin's
// bodies for them, as extension functions in a file of their own, so their code isn't loaded as the
// server starts (decisions/2026-11-25-a-lighter-core.md).

internal fun XStatsPlugin.saveNow(done: ((Boolean) -> Unit)?): Boolean {
    val db = database ?: return false
    if (!engine.ready) return false
    val start = locked(saveLock) { if (saving) false else true.also { saving = true } }
    if (!start) return false
    try {
        val started = now()
        if (server.proxy) {
            db.session(SaveRun(this, db, started, done))
            return true
        }
        val online = server.online()
        val each = config[XStatsConfig.collecting.readsPerTick].coerceAtLeast(1)
        val stats = catalog.snapshot
        for ((i, player) in online.withIndex()) {
            platform.scheduler.run(Where.On(player.uuid), delay = TICK * (1 + i / each), action = Reading(this, player, stats))
        }
        if (config[XStatsConfig.leaderboards.excludeBanned]) platform.scheduler.run(Where.Global, action = Banned(this))
        // Every reading is in by then, with room for a slow tick or two; a late one waits for the next save.
        val wait = (2L + (online.size + each - 1) / each + 10) * 50
        platform.scheduler.run(Where.Async, delay = wait.milliseconds) { _ -> db.session(SaveRun(this, db, started, done)) }
        return true
    } catch (e: RuntimeException) {
        locked(saveLock) { saving = false }
        throw e
    }
}

internal fun XStatsPlugin.stopNow(lastReadings: List<Event.Sample>): Pending<Unit> {
    running = false
    XStats.register(null)
    updates?.close()
    updates = null
    modules.clear()
    collector?.cancel()
    collector = null
    registrar?.clear()
    permissionRegistrar?.sync(emptyList())
    val db = database ?: return pendingOf(Unit)
    for (reading in lastReadings) engine.queue(reading)
    val done = newPending<Unit>()
    db.session { session ->
        Q.run(session, db.sql) { q -> engine.save(q, now()) }
        Outcome.Ok(Unit)
    }.onSettled { _, _ ->
        engine.feed?.close()
        db.close().onSettled { _, _ -> done.resolve(Unit) }
    }
    return done
}

internal fun XStatsPlugin.profileNow(player: PlayerRef): Pending<StatsProfile?> {
    val record = engine.player(player) ?: return pendingOf(null)
    val stats = catalog.snapshot.stats
    val values = LinkedHashMap<Period, Map<String, Long>>()
    for (period in Period.entries) {
        val map = LinkedHashMap<String, Long>()
        for (stat in stats) if (period == Period.ALL || stat.periods) map[stat.id] = engine.value(record, stat, period)
        values[period] = map
    }
    return pendingOf(StatsProfile(record.id, record.name, record.firstJoin, record.lastSeen, record.hidden, values))
}

internal fun XStatsPlugin.registerStatNow(owner: String, id: String, name: String, unit: StatUnit, latest: Boolean, decimals: Int): CustomStat {
    require(Definitions.ID.matches(id)) { "a stat's id is 1 to 32 lowercase letters, digits and _, not \"$id\"" }
    val full = owner.lowercase().filter { it.isLetterOrDigit() || it == '_' } + "_" + id
    require(Definitions.ID.matches(full)) { "\"$full\" is too long for a stat's id: 32 characters at most" }
    Definitions.clash(full)?.let { throw IllegalArgumentException(it) }
    require(full !in config[XStatsConfig.stats]) { "the config already has a stat called $full" }
    val stat = Definitions.Registered(full, name, unit, owner, latest, decimals.coerceIn(0, 4))
    val changed = locked(registered) {
        val old = registered.firstOrNull { it.id == full }
        if (old != null && old.name == stat.name && old.unit == stat.unit && old.latest == stat.latest && old.decimals == stat.decimals) {
            false
        } else {
            registered.removeAll { it.id == full }
            registered += stat
            true
        }
    }
    if (changed) {
        rebuildStats()
        onDatabase { _ -> engine.recomputeBoards() }
    }
    return Custom(this, full)
}

internal fun XStatsPlugin.statsNow(): List<StatInfo> = catalog.snapshot.stats.map { it.info }

internal fun XStatsPlugin.valueNow(player: PlayerRef, stat: String, period: Period): Long {
    val def = catalog.snapshot[stat] ?: return 0
    val record = engine.player(player) ?: return 0
    return engine.value(record, def, period)
}

internal fun XStatsPlugin.rankNow(player: PlayerRef, stat: String, period: Period): Int {
    val def = catalog.snapshot[stat] ?: return 0
    val record = engine.player(player) ?: return 0
    return engine.rank(record, def, period)
}

internal fun XStatsPlugin.topNow(stat: String, period: Period, count: Int): List<LeaderboardEntry> {
    val board = engine.leaderboards[stat, period] ?: return emptyList()
    return board.top(count.coerceIn(0, config[XStatsConfig.leaderboards.size]), false)
}

// The website's module key: its settings, so a change restarts it, and a count bumped after it
// failed to start (the port was taken), so a reload tries again.
internal fun XStatsPlugin.webKey(): String {
    val w = XStatsConfig.web
    val c = config
    return "web:" + listOf(c[w.bind], c[w.port], c[w.site], c[w.corsOrigins], c[w.requestsPerMinute], c[w.maxConcurrent], c[w.trustedProxies],
        c[w.clientIpHeader], c[w.avatars.url], c[w.avatars.proxy]).hashCode() + ":" + webFailures
}

/** One player's reading for a save, on their thread. */
internal class Reading(private val plugin: XStatsPlugin, private val player: PlayerRef, private val stats: Catalog.Snapshot) : (Task) -> Unit {
    override fun invoke(task: Task) {
        plugin.server.read(player, stats, quit = false, placeholders = true)?.let(plugin.engine::queue)
    }
}

/** The server's ban list, on the global thread where it's kept. */
internal class Banned(private val plugin: XStatsPlugin) : (Task) -> Unit {
    override fun invoke(task: Task) {
        plugin.engine.bannedPlayers(plugin.server.banned())
    }
}

internal class SaveRun(private val plugin: XStatsPlugin, private val db: Database, private val started: Long, private val done: ((Boolean) -> Unit)?) :
    (co.xenastudios.core.common.db.SqlSession) -> Outcome<Boolean, DbError> {
    override fun invoke(session: co.xenastudios.core.common.db.SqlSession): Outcome<Boolean, DbError> {
        var written = false
        try {
            written = when (val ran = Q.run(session, db.sql) { q -> plugin.engine.save(q, started) }) {
                is Outcome.Ok -> ran.value
                is Outcome.Err -> false.also { plugin.log.warn("The save couldn't use ${db.description}: ${ran.error.detail}") }
            }
            plugin.afterSave()
            if (written && plugin.hasListeners(StatsSavedEvent.TYPE)) {
                val last = plugin.engine.lastSave
                if (last != null) plugin.bus.fire(StatsSavedEvent.TYPE, StatsSavedEvent(last.players, last.millis))
            }
        } catch (e: Exception) {
            plugin.log.error("The save went wrong", e)
        } finally {
            locked(plugin.saveLock) { plugin.saving = false }
            done?.invoke(written)
        }
        return Outcome.Ok(written)
    }
}

/** A registered stat: each change waits in memory for the next save. */
internal class Custom(private val plugin: XStatsPlugin, override val id: String) : CustomStat {
    override fun increment(player: PlayerRef, amount: Long) {
        change(player, amount, false)
    }

    override fun set(player: PlayerRef, value: Long) {
        change(player, value, true)
    }

    private fun change(player: PlayerRef, amount: Long, set: Boolean) {
        var value = amount
        if (plugin.hasListeners(StatChangeEvent.TYPE)) {
            val event = plugin.bus.fire(StatChangeEvent.TYPE, StatChangeEvent(player, id, amount, set))
            if (event.isCancelled) return
            value = event.amount
        }
        if (!set && value == 0L) return
        plugin.engine.queue(if (set) Event.Set(player, id, value, value, 0) else Event.Increment(player, id, value))
    }
}
