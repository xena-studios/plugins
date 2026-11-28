package co.xenastudios.xstats.data

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.concurrentMap
import co.xenastudios.core.common.db.ChangeFeed
import co.xenastudios.core.common.locked
import co.xenastudios.core.common.platform.Log
import co.xenastudios.xstats.api.Period
import co.xenastudios.xstats.stat.Catalog
import co.xenastudios.xstats.stat.StatDef
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
    internal val log: Log,
    private val clock: co.xenastudios.core.common.platform.Clock,
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

    internal val players = concurrentMap<PlayerRef, PlayerRecord>()
    internal val byName = concurrentMap<String, PlayerRecord>()
    @Volatile
    private var madeEvents: Inbox? = null

    /** What waits for the next save: made with the first event (a join, a reading, another plugin's change), or the first save. */
    internal val events: Inbox
        get() = madeEvents ?: locked(players) { madeEvents ?: Inbox(log).also { madeEvents = it } }
    @Volatile
    private var madeBoards: Leaderboards? = null

    // No board was worked out because nobody had played yet: the next one works them all out. Under players' lock.
    private var boardsSkipped = false

    /**
     * Every stat's boards, as of the last save. Made with the first player, or the first time
     * they're asked for: before anyone has played, empty boards for every stat, as a save makes.
     */
    public val leaderboards: Leaderboards
        get() = madeBoards ?: locked(players) {
            madeBoards ?: Leaderboards().also { made ->
                if (boardsSkipped) {
                    val stats = catalog.snapshot.stats
                    made.compute(emptyList(), stats, settings.calendar, today(), BooleanArray(stats.size), true, emptySet(), null)
                }
                madeBoards = made
            }
        }
    internal var changedSinceBoards = BooleanArray(0)

    @Volatile public var store: Store? = null
        internal set

    @Volatile public var feed: ChangeFeed? = null
        internal set

    @Volatile public var ready: Boolean = false
        internal set

    @Volatile internal var networkDirty = false

    @Volatile internal var banned: Set<PlayerRef> = emptySet()

    /** Players kept off leaderboards while it says so: xVanish's vanished players, from the family's cache. */
    @Volatile public var vanished: ((PlayerRef) -> Boolean)? = null

    /** The family's player cache, which xVanish keeps: its vanished players are kept off leaderboards too. */
    @Volatile public var vanishedIn: co.xenastudios.core.common.family.PlayerCache? = null

    @Volatile internal var sortedNames: Array<String> = emptyArray()

    @Volatile internal var sortedPlayers: Array<PlayerRecord> = emptyArray()

    @Volatile public var lastSave: LastSave? = null
        internal set

    /** When the network's numbers were last read, on the proxy. */
    @Volatile public var lastRead: Long = 0
        internal set

    /** Goes up each time someone is hidden or shown again, so pages made from boards know to leave them out. */
    @Volatile public var hiddenVersion: Int = 0
        internal set

    /** What each stat of Minecraft's counters was read from, as stored; `null` until read. Database thread. */
    internal var knownSources: MutableMap<String, String>? = null

    /** Told on the database thread when someone is hidden from the website or shown again, to make its pages again. */
    @Volatile public var onHiddenChanged: (() -> Unit)? = null

    /** Told on the database thread of each board with a new leader, with the one before. */
    @Volatile public var onNewLeader: ((Board, PlayerRecord?) -> Unit)? = null

    internal var namesChanged = true

    /** The stats the last load read from the database. One added since has its saved values read before it's counted. */
    internal var loadedStats: Set<String> = emptySet()
    internal var failing = false
    internal var lastFold = -1

    // ---- reading, from any thread ----

    internal fun now(): Long = clock.now().toEpochMilliseconds()

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
    public fun leftOut(player: PlayerRecord): Boolean = player.excluded || player.id in banned || isVanished(player.id)

    /** A player's place on a board, or 0. Players left out of boards have none. */
    public fun rank(player: PlayerRecord, stat: StatDef, period: Period): Int {
        val board = leaderboards[stat.id, period] ?: return 0
        if (leftOut(player)) return 0
        return board.rankOf(value(player, stat, period))
    }

    private fun isVanished(player: PlayerRef): Boolean = vanishedIn?.isVanished(player) == true || vanished?.invoke(player) == true

    /** Stat changes waiting for the next save. */
    public val waiting: Int get() = madeEvents?.size ?: 0

    /** Changes dropped because too many waited. */
    public val dropped: Long get() = madeEvents?.dropped ?: 0

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

    /**
     * Applies every queued event, writes what changed, and works out leaderboards again. [started] is
     * when the save's first reading was asked for. Returns whether the database took it; if not, what
     * changed stays in memory for the next one. The save itself is in Saving.kt, loaded with the
     * first save rather than as the server starts.
     */
    public fun save(q: Q, started: Long): Boolean = saveNow(q, started)

    /** Hides a player from the website, or shows them again, and saves it right away. On the database thread. */
    public fun hide(q: Q, player: PlayerRecord, hidden: Boolean): Unit = hideNow(q, player, hidden)

    /** Another server changed whether a player is hidden. On the database thread. */
    public fun refreshHidden(q: Q, id: PlayerRef): Unit = refreshHiddenNow(q, id)

    /** After an import part: what xStats counts itself is raised in memory to the file's value where that's higher. On the database thread. */
    public fun raiseImported(imported: List<Store.Imported>): Unit = raiseImportedNow(imported)

    /** After an import: read everything again, keeping what's in memory. On the database thread. */
    public fun afterImport(q: Q): Unit = afterImportNow(q)

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
        if (hiddenChanged) onHiddenChanged?.invoke()
    }

    internal fun rename(player: PlayerRecord, name: String?) {
        if (name == null || name == player.name && byName[name.lowercase()] === player) return
        player.name?.let { old -> if (byName[old.lowercase()] === player) byName.remove(old.lowercase()) }
        player.name = name
        byName[name.lowercase()] = player
        namesChanged = true
    }

















    internal fun boards(everything: Boolean, today: Int) {
        // Nobody has played: there's nothing to rank, so a server that's never had a player doesn't
        // load the boards' code. Decided under players' lock, which making the boards takes.
        val skipped = locked(players) {
            if (players.isEmpty() && madeBoards == null) {
                boardsSkipped = true
                null
            } else {
                boardsSkipped.also { boardsSkipped = false }
            }
        } ?: return
        val all = everything || skipped
        val stats = catalog.snapshot
        val leftOut = HashSet<PlayerRef>(banned)
        for (player in players.values) {
            if (player.excluded || isVanished(player.id)) leftOut += player.id
        }
        val changed = changedSinceBoards
        changedSinceBoards = BooleanArray(changed.size)
        leaderboards.compute(players.values, stats.stats, settings.calendar, today, changed, all, leftOut, onNewLeader)
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





    public companion object {
        /** A player's last reading of a stat whose source changed since: the next reading is its new value. */
        internal const val REBASE: Long = -2
    }
}
