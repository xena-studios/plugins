package co.xenastudios.xstats.data

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.concurrentMap
import co.xenastudios.core.common.db.ChangeFeed
import co.xenastudios.core.common.platform.Log
import co.xenastudios.xstats.api.Period
import co.xenastudios.xstats.stat.Catalog
import co.xenastudios.xstats.stat.StatDef
import co.xenastudios.xstats.store.DbFailed
import co.xenastudios.xstats.store.Q
import co.xenastudios.xstats.store.Store
import kotlin.concurrent.Volatile

/**
 * Every player's stats in memory, and the save that keeps them and the database in step: 1.x's
 * engine. Everything here that changes runs on xStats' database thread (the core database's
 * writer, through `Database.session`); other threads read players and leaderboards straight from
 * memory.
 *
 * Readings, joins and other plugins' changes queue up as [Event]s ([Inbox]) and are applied in
 * order at the next save. A save then writes what changed in one transaction: every changed
 * all-time row, and one daily row per stat that rose (for stats with periods), then works out
 * leaderboards again.
 *
 * Minecraft's own stats are kept as running totals beside the last reading of the counter. A save
 * lost to a crash is made up at the next reading, since the counter kept counting; a counter that
 * went backwards (its stats file deleted) is taken to have restarted from 0, so the all-time number
 * keeps growing.
 *
 * On the proxy ([readOnly]) nothing is applied or written: it reads the network's numbers and works
 * out the network's leaderboards.
 */
public class Engine(
    public val catalog: Catalog,
    @Volatile public var settings: Settings,
    private val log: Log,
    private val now: () -> Long,
    /** The proxy: reads, never writes. */
    public val readOnly: Boolean = false,
) {
    /** What the engine needs from the config, swapped whole on reload. */
    public class Settings(
        public val calendar: Calendar,
        public val afkAware: Boolean,
        public val saveEveryMillis: Long,
        public val keepDailyDays: Int,
        public val network: Boolean,
    )

    /** How the last save went, for `/xstats status`. */
    public class LastSave(public val at: Long, public val players: Int, public val millis: Long, public val problem: String?)

    private val players = concurrentMap<PlayerRef, PlayerRecord>()
    private val byName = concurrentMap<String, PlayerRecord>()
    internal val events: Inbox = Inbox(log)
    public val leaderboards: Leaderboards = Leaderboards()
    private var changedSinceBoards = BooleanArray(0)

    @Volatile public var store: Store? = null
        private set

    @Volatile public var feed: ChangeFeed? = null
        private set

    @Volatile public var ready: Boolean = false
        private set

    @Volatile private var networkDirty = false

    @Volatile private var banned: Set<PlayerRef> = emptySet()

    /** Players kept off leaderboards while it says so: xVanish's vanished players, from the family's cache. */
    @Volatile public var vanished: (PlayerRef) -> Boolean = NOBODY

    @Volatile private var sortedNames: Array<String> = emptyArray()

    @Volatile private var sortedPlayers: Array<PlayerRecord> = emptyArray()

    @Volatile public var lastSave: LastSave = LastSave(0, 0, 0, null)
        private set

    /** When the network's numbers were last read, on the proxy. */
    @Volatile public var lastRead: Long = 0
        private set

    /** Goes up each time someone is hidden or shown again, so pages made from boards know to leave them out. */
    @Volatile public var hiddenVersion: Int = 0
        private set

    /** What each stat of Minecraft's counters was read from, as stored; `null` until read. Database thread. */
    private var knownSources: MutableMap<String, String>? = null

    /** Told on the database thread when someone is hidden from the website or shown again, to make its pages again. */
    @Volatile public var onHiddenChanged: () -> Unit = NOTHING

    /** Told on the database thread of each board with a new leader, with the one before. */
    @Volatile public var onNewLeader: (Board, PlayerRecord?) -> Unit = NO_LEADER

    private var namesChanged = true

    /** The stats the last load read from the database. One added since has its saved values read before it's counted. */
    private var loadedStats: Set<String> = emptySet()
    private var failing = false
    private var lastFold = -1

    // ---- reading, from any thread ----

    public fun today(): Int = settings.calendar.today(now())

    public fun player(id: PlayerRef): PlayerRecord? = players[id]

    public fun byName(name: String): PlayerRecord? = byName[name.lowercase()]

    public val all: Collection<PlayerRecord> get() = players.values

    public val playerCount: Int get() = players.size

    public fun value(player: PlayerRecord, stat: StatDef, period: Period): Long {
        val current = settings
        return player.value(stat.index, period, current.calendar, current.calendar.today(now()))
    }

    /** Whether [player] is left out of leaderboards: excluded, banned (as last read) or vanished. */
    public fun leftOut(player: PlayerRecord): Boolean = player.excluded || player.id in banned || vanished(player.id)

    /** A player's place on a board, or 0. Players left out of boards have none. */
    public fun rank(player: PlayerRecord, stat: StatDef, period: Period): Int {
        val board = leaderboards[stat.id, period] ?: return 0
        if (leftOut(player)) return 0
        return board.rankOf(value(player, stat, period))
    }

    /**
     * A player's place among those the website shows: players who keep their stats private don't
     * count, so a place never gives away that someone private is above. 0 if none.
     */
    public fun visibleRank(player: PlayerRecord, stat: StatDef, period: Period): Int {
        val board = leaderboards[stat.id, period] ?: return 0
        if (leftOut(player)) return 0
        return board.visibleRankOf(value(player, stat, period), hiddenVersion)
    }

    /** Names starting with [prefix], ignoring case, at most [limit], skipping players [skip] says to. */
    public fun search(prefix: String, limit: Int, skip: (PlayerRecord) -> Boolean): List<PlayerRecord> {
        val lower = prefix.lowercase()
        val names = sortedNames
        val records = sortedPlayers
        // The first name not before the prefix, as sorted.
        var low = 0
        var high = names.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (names[mid] < lower) low = mid + 1 else high = mid
        }
        val at = low
        val found = ArrayList<PlayerRecord>()
        var i = at
        while (i < names.size && found.size < limit && names[i].startsWith(lower)) {
            if (!skip(records[i])) found += records[i]
            i++
        }
        return found
    }

    /** Stat changes waiting for the next save. */
    public val waiting: Int get() = events.size

    /** Changes dropped because too many waited. */
    public val dropped: Long get() = events.dropped

    // ---- queueing, from any thread ----

    public fun queue(event: Event) {
        if (!readOnly) events.add(event)
    }

    public fun bannedPlayers(banned: Set<PlayerRef>) {
        this.banned = banned.toSet()
    }

    /** Another server of the network saved: read the network's numbers at the next save. */
    public fun networkChanged() {
        networkDirty = true
    }

    public val dirty: Boolean get() = networkDirty

    // ---- the database thread ----

    /** Uses this database from now on and loads every player from it. */
    public fun open(q: Q, store: Store, feed: ChangeFeed?) {
        this.store = store
        this.feed = feed
        reload(q)
        ready = true
    }

    /**
     * Reads every player's all-time and period numbers again, keeping what's only in memory, and
     * works out every board again: any player's numbers may have changed (on a network, other
     * servers' saves).
     */
    public fun reload(q: Q) {
        val store = store ?: return
        val current = settings
        val today = current.calendar.today(now())
        val loaded = store.load(q, current.network, current.calendar.earliestNeeded(today))
        val stats = catalog.snapshot
        val width = stats.width
        loadedStats = stats.byId.keys.toSet()
        val byDbId = HashMap<Int, PlayerRecord>()
        var hiddenChanged = false
        for (row in loaded.players) {
            val player = players.getOrPut(row.uuid) { PlayerRecord(row.uuid, row.name) }
            player.dbId = row.dbId
            if (player.name == null || !player.online) rename(player, row.name)
            if (player.hidden != row.hidden) {
                player.hidden = row.hidden
                hiddenChanged = true
            }
            player.excluded = row.excluded
            player.firstJoin = if (player.firstJoin == 0L) row.firstJoin else minOf(player.firstJoin, maxOf(1, row.firstJoin))
            player.lastSeen = maxOf(player.lastSeen, row.lastSeen)
            byDbId[row.dbId] = player
        }
        val totals = HashMap<PlayerRecord, LongArray>()
        for (value in loaded.totals) {
            val player = byDbId[value.player] ?: continue
            val stat = stats.byId[value.stat] ?: continue
            // On a network, counters add up every server; a gauge (a balance) is the value written
            // last, not N times over, nor the highest one a server still holds.
            totals.getOrPut(player) { LongArray(width) }[stat.index] = if (stat.counter) value.value else value.latest
        }
        for ((player, values) in totals) {
            for (stat in stats.stats) {
                val i = stat.index
                if (stat.counter) {
                    // Rises not saved yet are only in memory; keep them on top of what was read.
                    values[i] += if (i < player.pendingDaily.size) player.pendingDaily[i] else 0
                } else if (player.localLoaded && i < player.local.size && i < player.changed.size && player.changed[i]) {
                    // A gauge set here since the last save: this server's value is the latest.
                    values[i] = player.local[i]
                }
            }
            player.values = values
        }
        val calendar = current.calendar
        val periods = HashMap<PlayerRecord, Array<LongArray>>()
        val week = calendar.week(today)
        val month = Calendar.month(today)
        for (value in loaded.days) {
            val player = byDbId[value.player] ?: continue
            val stat = stats.byId[value.stat] ?: continue
            val buckets = periods.getOrPut(player) { arrayOf(LongArray(width), LongArray(width), LongArray(width)) }
            if (value.day == today) buckets[0][stat.index] += value.value
            if (calendar.week(value.day) == week) buckets[1][stat.index] += value.value
            if (Calendar.month(value.day) == month) buckets[2][stat.index] += value.value
        }
        for (player in players.values) {
            var buckets = periods[player]
            val pending = player.pendingDaily.any { it > 0 }
            if (buckets == null && !pending) {
                // Most players did nothing this month: empty buckets, grown when they do.
                player.setBucket(Period.DAILY, Bucket(today, Bucket.NONE))
                player.setBucket(Period.WEEKLY, Bucket(week, Bucket.NONE))
                player.setBucket(Period.MONTHLY, Bucket(month, Bucket.NONE))
                player.ensureWidth(width)
                continue
            }
            if (buckets == null) buckets = arrayOf(LongArray(width), LongArray(width), LongArray(width))
            for (i in 0 until minOf(width, player.pendingDaily.size)) {
                buckets[0][i] += player.pendingDaily[i]
                buckets[1][i] += player.pendingDaily[i]
                buckets[2][i] += player.pendingDaily[i]
            }
            player.setBucket(Period.DAILY, Bucket(today, buckets[0]))
            player.setBucket(Period.WEEKLY, Bucket(week, buckets[1]))
            player.setBucket(Period.MONTHLY, Bucket(month, buckets[2]))
            player.ensureWidth(width)
        }
        namesChanged = true
        if (hiddenChanged) hiddenVersion++
        lastRead = now()
        networkDirty = false
        boards(true, today)
        if (hiddenChanged) onHiddenChanged()
    }

    private fun rename(player: PlayerRecord, name: String?) {
        if (name == null || name == player.name && byName[name.lowercase()] === player) return
        player.name?.let { old -> if (byName[old.lowercase()] === player) byName.remove(old.lowercase()) }
        player.name = name
        byName[name.lowercase()] = player
        namesChanged = true
    }

    /**
     * Applies every queued event, writes what changed, and works out leaderboards again. [started] is
     * when the save's first reading was asked for. Returns whether the database took it; if not, what
     * changed stays in memory for the next one.
     */
    public fun save(q: Q, started: Long): Boolean {
        val store = store ?: return false
        if (readOnly) {
            if (networkDirty) refresh(q)
            boards(false, today())
            return true
        }
        val current = settings
        val at = now()
        val today = current.calendar.today(at)
        val stats = catalog.snapshot
        if (!loadedStats.containsAll(stats.byId.keys)) {
            // A stat added since the last load (a reload, or another plugin registering one) may have
            // values saved from before: read them before counting on top, or its all-time number
            // would start again from 0.
            if (!write(q, store, stats, today)) return false
            for (player in players.values) player.localLoaded = false
            try {
                reload(q)
            } catch (e: DbFailed) {
                problem("Couldn't read the stats added since the last load: ${e.error.detail}")
                return false
            }
        }
        checkSources(q, store, stats)
        var touched = 0
        val seen = HashSet<PlayerRef>()
        // A player whose rows can't be read has their events kept, in order, for the next save;
        // everyone else's are applied and saved now.
        val unread = events.drain { event ->
            val player = apply(q, store, event, stats, current, today)
            if (player != null && seen.add(player.id)) touched++
        }
        if (unread != null) {
            problem("Couldn't read ${events.heldPlayers} player(s)' stats: $unread. Their changes are kept and tried again at the next save.")
        }
        val written = write(q, store, stats, today)
        if (written && lastFold != today) fold(q, store, today, current)
        if (written && networkDirty && current.network) refresh(q)
        boards(false, today)
        val took = now() - started
        lastSave = LastSave(at, touched, took, if (written && unread == null) null else lastSave.problem)
        return written
    }

    // The network's numbers again, and every board with them.
    private fun refresh(q: Q) {
        try {
            reload(q)
        } catch (e: DbFailed) {
            networkDirty = true
            log.warn("Couldn't read the network's stats: ${e.error.detail}")
        }
    }

    private fun apply(q: Q, store: Store, event: Event, stats: Catalog.Snapshot, current: Settings, today: Int): PlayerRecord? {
        val width = stats.width
        when (event) {
            is Event.Joined -> {
                val player = players.getOrPut(event.player) { PlayerRecord(event.player, event.name) }
                prepare(q, store, player, width)
                rename(player, event.name)
                val first = if (event.firstPlayed > 0) event.firstPlayed else event.at
                player.firstJoin = if (player.firstJoin == 0L) first else minOf(player.firstJoin, first)
                player.lastSeen = event.at
                player.online = true
                player.lastSample = event.at
                player.yaw = event.yaw
                player.pitch = event.pitch
                player.rowChanged = true
                for (stat in stats.stats) {
                    val source = stat.source
                    if (source is StatDef.Tracked && !source.playtime) add(player, stat, 1, current, today, width)
                }
                return player
            }
            is Event.Sample -> {
                val player = players.getOrPut(event.player) { PlayerRecord(event.player, event.name) }
                prepare(q, store, player, width)
                rename(player, event.name)
                if (player.excluded != event.excluded) player.excluded = event.excluded
                player.lastSeen = maxOf(player.lastSeen, event.at)
                player.lastRead = maxOf(player.lastRead, event.at)
                player.rowChanged = true
                var tracksPlaytime = false
                for (stat in stats.stats) {
                    val index = stat.index
                    val source = stat.source
                    if (source is StatDef.Tracked && source.playtime) tracksPlaytime = true
                    if (index >= event.raw.size) continue
                    val reading = event.raw[index]
                    if (reading != Event.Sample.NOT_READ) {
                        reading(player, stat, reading, current, today, width)
                    } else if (source is StatDef.Tracked && source.playtime) {
                        playtime(player, stat, event, current, today, width)
                    }
                }
                if (!tracksPlaytime) look(player, event)
                if (event.quit) player.online = false
                return player
            }
            is Event.Increment -> {
                val player = players[event.player] ?: return null
                val stat = stats.byId[event.stat] ?: return null
                prepare(q, store, player, width)
                add(player, stat, event.amount, current, today, width)
                return player
            }
            is Event.Set -> {
                val player = players[event.player] ?: return null
                val stat = stats.byId[event.stat] ?: return null
                prepare(q, store, player, width)
                player.lastRead = maxOf(player.lastRead, now())
                gauge(player, stat, event.first, current, today, width)
                // Set again since: the last value, with the rises between counted as they happened.
                if (event.value != event.first || event.rise > 0) settle(player, stat, event.value, event.rise, current, today, width)
                return player
            }
        }
    }

    /** Makes sure a player's own rows on this server are in memory, reading them the first time. */
    private fun prepare(q: Q, store: Store, player: PlayerRecord, width: Int) {
        player.ensureWidth(width)
        if (player.localLoaded) return
        if (player.dbId > 0) {
            val rows = try {
                store.local(q, player.dbId)
            } catch (e: DbFailed) {
                throw Inbox.Unread(e.error.detail)
            }
            player.startLocal(width)
            val stats = catalog.snapshot
            for (row in rows) {
                val stat = stats.byId[row.stat] ?: continue
                if (stat.index < width) {
                    player.local[stat.index] = row.value
                    player.raw[stat.index] = row.raw
                }
            }
        } else {
            player.startLocal(width)
        }
        player.ensureWidth(width)
    }

    /**
     * Notices a stat whose source changed in the config (more of Minecraft's counters added up, or
     * fewer): every player's last reading of it is marked, here and in the database, so the next
     * reading is its new all-time value, not a rise or a drop from the old one.
     */
    private fun checkSources(q: Q, store: Store, stats: Catalog.Snapshot) {
        val known = knownSources ?: try {
            HashMap(store.sources(q)).also { knownSources = it }
        } catch (e: DbFailed) {
            return // Asked again at the next save.
        }
        for (stat in stats.stats) {
            val vanillas = stat.source as? StatDef.Vanillas ?: continue
            val source = vanillas.fingerprint
            val before = known[stat.id]
            if (source == before) continue
            val changed = before != null
            try {
                store.source(q, stat.id, source, changed)
            } catch (e: DbFailed) {
                log.warn("Couldn't note the source of ${stat.id}: ${e.error.detail}")
                continue
            }
            known[stat.id] = source
            if (changed) {
                val index = stat.index
                for (player in players.values) {
                    if (player.localLoaded && index < player.raw.size && player.raw[index] >= 0) player.raw[index] = REBASE
                }
                log.info("${stat.id}'s source changed: each player's next reading is its new all-time value.")
            }
        }
    }

    private fun markChanged(player: PlayerRecord, index: Int) {
        if (index < player.changed.size) player.changed[index] = true
        if (changedSinceBoards.size <= index) changedSinceBoards = changedSinceBoards.copyOf(index + 1)
        changedSinceBoards[index] = true
    }

    /** A reading of a counter (Minecraft's stats) or a gauge (a placeholder). */
    private fun reading(player: PlayerRecord, stat: StatDef, reading: Long, current: Settings, today: Int, width: Int) {
        val index = stat.index
        if (!stat.counter) {
            gauge(player, stat, reading, current, today, width)
            return
        }
        val last = player.raw[index]
        if (last == REBASE) {
            // The stat's source changed: Minecraft's own count from the new counters is its all-time
            // value. None of it is today's, this week's or this month's.
            val change = reading - player.local[index]
            player.local[index] = reading
            player.values[index] += change
            player.raw[index] = reading
            markChanged(player, index)
            return
        }
        if (last < 0) {
            // The first reading: what they had before xStats counted them is all time, not today.
            val rise = reading - player.local[index]
            if (rise > 0) {
                player.local[index] += rise
                player.values[index] += rise
            }
            player.raw[index] = reading
            markChanged(player, index)
            return
        }
        if (reading == last) return
        player.raw[index] = reading
        if (index < player.changed.size) player.changed[index] = true
        if (reading < last && last - reading <= maxOf(1, last / 10)) {
            // A little lower (by up to a tenth, or by 1 for a small counter): another plugin took
            // some away (decrementStatistic). All time goes down with it; today, this week and this
            // month only ever count rises. More than a tenth lower is taken as a restart from 0, a
            // world rolled back that far included.
            val drop = minOf(last - reading, player.local[index])
            player.local[index] -= drop
            player.values[index] -= drop
            markChanged(player, index)
            return
        }
        // Much lower than last time: the counter restarted from 0, as when a stats file is deleted.
        val rise = if (reading > last) reading - last else reading
        if (rise > 0) add(player, stat, rise, current, today, width)
    }

    /** A value set outright: all time follows it, and only a rise counts towards periods. */
    private fun gauge(player: PlayerRecord, stat: StatDef, value: Long, current: Settings, today: Int, width: Int) {
        val index = stat.index
        val old = player.local[index]
        // Never set before (on this server): what it is now isn't a rise today, as with a counter's first reading.
        val first = player.raw[index] < 0
        if (value == old && !first) return
        player.raw[index] = maxOf(0, value)
        player.local[index] = value
        // On a network a gauge is its latest value, not every server's added up.
        player.values[index] = if (current.network) value else player.values[index] + value - old
        markChanged(player, index)
        if (!first && value > old && stat.periods) period(player, stat, value - old, current, today, width)
    }

    /** A gauge's later value, after [gauge], with [rise] counted towards periods. */
    private fun settle(player: PlayerRecord, stat: StatDef, value: Long, rise: Long, current: Settings, today: Int, width: Int) {
        val index = stat.index
        val old = player.local[index]
        player.raw[index] = maxOf(0, value)
        player.local[index] = value
        player.values[index] = if (current.network) value else player.values[index] + value - old
        markChanged(player, index)
        if (rise > 0 && stat.periods) period(player, stat, rise, current, today, width)
    }

    /** Adds to a stat: all time, and today, this week and this month for a stat with periods. */
    private fun add(player: PlayerRecord, stat: StatDef, amount: Long, current: Settings, today: Int, width: Int) {
        val index = stat.index
        player.local[index] += amount
        player.values[index] += amount
        markChanged(player, index)
        if (stat.periods) period(player, stat, amount, current, today, width)
    }

    private fun period(player: PlayerRecord, stat: StatDef, amount: Long, current: Settings, today: Int, width: Int) {
        val index = stat.index
        player.pendingDaily[index] += amount
        val calendar = current.calendar
        player.addPeriod(Period.DAILY, today, index, amount, width)
        player.addPeriod(Period.WEEKLY, calendar.week(today), index, amount, width)
        player.addPeriod(Period.MONTHLY, Calendar.month(today), index, amount, width)
    }

    /**
     * Time online since the last reading, counted only if they looked around since: the save's own
     * comparison that makes playtime AFK-aware, with no movement listener.
     */
    private fun playtime(player: PlayerRecord, stat: StatDef, sample: Event.Sample, current: Settings, today: Int, width: Int) {
        if (player.online && player.lastSample > 0) {
            // Never more than one and a half saves, so a stall or a clock change can't add hours.
            val elapsed = minOf(sample.at - player.lastSample, current.saveEveryMillis * 3 / 2)
            val active = !current.afkAware || player.yaw != sample.yaw || player.pitch != sample.pitch
            if (elapsed > 0 && active) {
                player.playMillis += elapsed
                val seconds = player.playMillis / 1000
                player.playMillis %= 1000
                if (seconds > 0) add(player, stat, seconds, current, today, width)
            }
        }
        look(player, sample)
    }

    private fun look(player: PlayerRecord, sample: Event.Sample) {
        player.yaw = sample.yaw
        player.pitch = sample.pitch
        player.lastSample = sample.at
        player.online = !sample.quit
    }

    /** Writes every changed row in one transaction. */
    private fun write(q: Q, store: Store, stats: Catalog.Snapshot, today: Int): Boolean {
        val changed = ArrayList<PlayerRecord>()
        val rows = ArrayList<Store.PlayerRow>()
        val totals = ArrayList<Store.TotalRow>()
        val daily = ArrayList<Store.DailyRow>()
        for (player in players.values) {
            if (!player.rowChanged && !player.anyChanged()) continue
            val at = changed.size
            changed += player
            rows += Store.PlayerRow(player.id, player.dbId, player.name, player.firstJoin, player.lastSeen, player.hidden, player.excluded, player.hidePending)
            for (stat in stats.stats) {
                val index = stat.index
                if (player.localLoaded && index < player.local.size && index < player.changed.size && player.changed[index]) {
                    // The last reading: Minecraft's counter, or a placeholder's or another plugin's
                    // last value, so the first one after a restart isn't taken as new.
                    totals += Store.TotalRow(at, stat.id, player.local[index], player.raw[index], player.lastRead)
                }
                if (index < player.pendingDaily.size && player.pendingDaily[index] > 0 && stat.periods) {
                    daily += Store.DailyRow(at, stat.id, player.pendingDaily[index])
                }
            }
        }
        if (changed.isEmpty()) return true
        return try {
            val ids = store.save(q, Store.Save(rows, totals, daily, today, now()), feed)
            for ((i, player) in changed.withIndex()) {
                player.dbId = ids[i]
                player.rowChanged = false
                player.hidePending = false
                player.changed.fill(false)
                player.pendingDaily.fill(0)
            }
            if (failing && events.heldPlayers == 0) {
                failing = false
                log.info("Saving works again.")
            }
            true
        } catch (e: DbFailed) {
            problem("Couldn't save stats: ${e.error.detail}. They're kept in memory and saved at the next save.")
            false
        }
    }

    private fun problem(message: String) {
        lastSave = LastSave(lastSave.at, lastSave.players, lastSave.millis, message)
        if (!failing) {
            failing = true
            log.warn(message)
        }
    }

    private fun fold(q: Q, store: Store, today: Int, current: Settings) {
        try {
            val folded = store.fold(q, today - current.keepDailyDays, 31)
            if (folded < 31) lastFold = today
        } catch (e: DbFailed) {
            log.warn("Couldn't add old days into their months; trying again tomorrow: ${e.error.detail}")
            lastFold = today
        }
    }

    private fun boards(everything: Boolean, today: Int) {
        val stats = catalog.snapshot
        val leftOut = HashSet<PlayerRef>(banned)
        val vanish = vanished
        for (player in players.values) {
            if (player.excluded || vanish(player.id)) leftOut += player.id
        }
        val changed = changedSinceBoards
        changedSinceBoards = BooleanArray(changed.size)
        leaderboards.compute(players.values, stats.stats, settings.calendar, today, changed, everything, leftOut, onNewLeader)
        if (namesChanged) {
            namesChanged = false
            val named = players.values.filter { it.name != null }.sortedWith(Comparator { a, b -> a.name!!.compareTo(b.name!!, ignoreCase = true) })
            sortedNames = Array(named.size) { named[it].name!!.lowercase() }
            sortedPlayers = named.toTypedArray()
        }
    }

    /** Works out every board again, as after a reload changed the stats. On the database thread. */
    public fun recomputeBoards() {
        boards(true, today())
    }

    /** The player's record, made now if xStats hasn't seen them yet. On the database thread. */
    public fun ensure(id: PlayerRef, name: String): PlayerRecord {
        val player = players.getOrPut(id) { PlayerRecord(id, name) }
        rename(player, name)
        return player
    }

    /** Hides a player from the website, or shows them again, and saves it right away. On the database thread. */
    public fun hide(q: Q, player: PlayerRecord, hidden: Boolean) {
        if (player.hidden != hidden) {
            player.hidden = hidden
            hiddenVersion++
            onHiddenChanged()
        }
        val store = store
        if (player.dbId == 0 || store == null) {
            player.rowChanged = true
            player.hidePending = true
            return
        }
        try {
            store.hide(q, player.dbId, player.id, hidden, feed)
            player.hidePending = false
        } catch (e: DbFailed) {
            player.rowChanged = true
            player.hidePending = true
            log.warn("Couldn't save /stats private for ${player.name} now; it's saved with the next save: ${e.error.detail}")
        }
    }

    /** Another server changed whether a player is hidden. On the database thread. */
    public fun refreshHidden(q: Q, id: PlayerRef) {
        val player = players[id] ?: return
        val store = store ?: return
        try {
            val hidden = store.hidden(q, id)
            if (hidden == null) {
                // Forgotten on another server: gone here too.
                forgot(id)
                return
            }
            if (hidden != player.hidden) {
                player.hidden = hidden
                hiddenVersion++
                onHiddenChanged()
            }
        } catch (e: DbFailed) {
            // The next network reload reads everyone's again, this player's included.
            networkDirty = true
        }
    }

    /** `/xstats forget` took [id]'s rows: forget them in memory too, and rank everyone again. On the database thread. */
    public fun forgot(id: PlayerRef) {
        val player = players.remove(id) ?: return
        player.name?.let { if (byName[it.lowercase()] === player) byName.remove(it.lowercase()) }
        namesChanged = true
        hiddenVersion++
        boards(true, today())
        onHiddenChanged()
    }

    /**
     * After an import part: what xStats counts itself (playtime, sessions) is raised in memory to the
     * file's value where that's higher, as the database row just was. On the database thread.
     */
    public fun raiseImported(imported: List<Store.Imported>) {
        val stats = catalog.snapshot
        for (entry in imported) {
            val player = players[entry.uuid] ?: continue
            if (!player.localLoaded) continue // Read from the database when they're next needed.
            for ((id, value) in entry.values) {
                val stat = stats.byId[id] ?: continue
                if (entry.counters[id] == true || stat.index >= player.local.size) continue
                val rise = value - player.local[stat.index]
                if (rise > 0) {
                    player.local[stat.index] += rise
                    player.values[stat.index] += rise
                    markChanged(player, stat.index)
                }
            }
        }
    }

    /** After an import: read everything again, keeping what's in memory. On the database thread. */
    public fun afterImport(q: Q) {
        for (player in players.values) player.localLoaded = false
        reload(q)
    }

    public companion object {
        /** A player's last reading of a stat whose source changed since: the next reading is its new value. */
        internal const val REBASE: Long = -2

        private val NOTHING: () -> Unit = {}
        private val NO_LEADER: (Board, PlayerRecord?) -> Unit = { _, _ -> }
        private val NOBODY: (PlayerRef) -> Boolean = { false }
    }
}
