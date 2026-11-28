package co.xenastudios.xstats.data

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.xstats.api.LeaderboardEntry
import co.xenastudios.xstats.api.Period
import co.xenastudios.xstats.stat.StatDef
import kotlin.concurrent.Volatile

/**
 * One leaderboard, worked out at a save: every player with something for its stat and period, best
 * first, and who's left out. Its places never change once made, so any thread reads it.
 */
public class Board(
    public val stat: StatDef,
    public val period: Period,
    /** The period's day, week or month it was worked out for. */
    public val key: Int,
    public val order: Array<PlayerRecord>,
    public val values: LongArray,
) {
    /** How many private players are above each place, for the website's places, as of one hidden version. */
    private class Hidden(val version: Int, val above: IntArray)

    @Volatile
    private var hidden: Hidden? = null

    public val size: Int get() = order.size

    /** The place of a value: one more than how many are above it, so ties share a place. */
    public fun rankOf(value: Long): Int = if (value <= 0) 0 else above(value) + 1

    // How many places have a value above this one: values is best first.
    private fun above(value: Long): Int {
        var low = 0
        var high = values.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (values[mid] > value) low = mid + 1 else high = mid
        }
        return low
    }

    /**
     * The place of a value among players the website shows: private players above it don't count, so
     * a place never gives away that someone private is there. [version] is the engine's hidden
     * version, so a hide since the board was made counts.
     */
    public fun visibleRankOf(value: Long, version: Int): Int {
        if (value <= 0) return 0
        val above = above(value)
        return above - hiddenAbove(version)[above] + 1
    }

    // [i] is how many private players are in the first i places. Made once per hide.
    private fun hiddenAbove(version: Int): IntArray {
        hidden?.let { if (it.version == version) return it.above }
        val above = IntArray(order.size + 1)
        for (i in order.indices) above[i + 1] = above[i] + if (order[i].hidden) 1 else 0
        hidden = Hidden(version, above)
        return above
    }

    /** The place shown beside entry [i]: the place of the first entry with its value. */
    public fun rankAt(i: Int): Int = rankOf(values[i])

    /** The first [count] places. With [skipHidden] (for the website), private players are left out and places are counted among those shown. */
    public fun top(count: Int, skipHidden: Boolean): List<LeaderboardEntry> {
        val entries = ArrayList<LeaderboardEntry>(minOf(count, order.size))
        var rank = 0
        var last = Long.MIN_VALUE
        for (i in order.indices) {
            if (entries.size >= count) break
            val player = order[i]
            if (skipHidden && player.hidden) continue
            if (!skipHidden) {
                rank = rankAt(i)
            } else if (values[i] != last) {
                // Ties share a place; the next value's place is one more than how many are shown above it.
                rank = entries.size + 1
                last = values[i]
            }
            entries += LeaderboardEntry(rank, player.id, player.name ?: player.id.toString(), values[i])
        }
        return entries
    }

    /** A page of places, from [from] (0-based). */
    public fun slice(from: Int, count: Int): List<LeaderboardEntry> {
        val entries = ArrayList<LeaderboardEntry>(count)
        var i = from
        while (i < order.size && i < from + count) {
            entries += LeaderboardEntry(rankAt(i), order[i].id, order[i].name ?: order[i].id.toString(), values[i])
            i++
        }
        return entries
    }

    public val leader: PlayerRecord? get() = order.firstOrNull()
}

/**
 * Every leaderboard, worked out during a save and swapped in whole, so placeholders, `/leaderboard`
 * and the website read them from memory and never ask the database.
 *
 * Only boards whose stat changed in a save, or whose day, week or month just turned over, are worked
 * out again; the rest are kept as they were.
 */
public class Leaderboards {
    @Volatile
    private var boards: Map<String, Board> = emptyMap()

    @Volatile
    private var totals: LongArray = LongArray(0)

    @Volatile
    private var leftOut: Set<PlayerRef> = emptySet()

    /** A board, or `null` if there's none. Any thread. */
    public operator fun get(stat: String, period: Period): Board? = boards[key(stat, period)]

    public val all: Collection<Board> get() = boards.values

    /** Every player's all-time value of a stat added up, by stat index, as of the last save. */
    public fun total(index: Int): Long = totals.let { if (index < it.size) it[index] else 0 }

    /**
     * Works out boards again. On xStats' database thread. [changed] says which stats' values changed
     * since the last time; [everything] works out every board, as when who's left out changed;
     * [leftOut] players are never on a board (banned, excluded, vanished); [newLeader] hears of each
     * board with a new leader, with the one before (`null` if none).
     */
    public fun compute(
        players: Collection<PlayerRecord>,
        stats: List<StatDef>,
        calendar: Calendar,
        today: Int,
        changed: BooleanArray,
        everything: Boolean,
        leftOut: Set<PlayerRef>,
        newLeader: (Board, PlayerRecord?) -> Unit,
    ) {
        val old = boards
        val exclusionsChanged = leftOut != this.leftOut
        val next = HashMap<String, Board>()
        val width = stats.maxOfOrNull { it.index + 1 } ?: 0
        val sums = totals.copyOf(maxOf(width, totals.size))
        for (stat in stats) {
            val periods = if (stat.periods) PERIODS else ALL_ONLY
            for (period in periods) {
                val key = calendar.key(period, today)
                val name = key(stat.id, period)
                val before = old[name]
                val fresh = everything || exclusionsChanged || before == null || before.key != key ||
                    (stat.index < changed.size && changed[stat.index]) || before.stat !== stat
                if (!fresh) {
                    next[name] = before
                    continue
                }
                val board = build(players, stat, period, calendar, today, key, leftOut, sums)
                next[name] = board
                val leader = board.leader
                val previous = before?.leader
                // A new leader on the same board: not a new day emptying it, nor the first boards after a start.
                if (before != null && before.key == key && leader != null && (previous == null || previous.id != leader.id)) {
                    newLeader(board, previous)
                }
            }
        }
        this.leftOut = leftOut.toSet()
        totals = sums
        boards = next
    }

    private fun build(
        players: Collection<PlayerRecord>,
        stat: StatDef,
        period: Period,
        calendar: Calendar,
        today: Int,
        key: Int,
        leftOut: Set<PlayerRef>,
        sums: LongArray,
    ): Board {
        val kept = ArrayList<PlayerRecord>()
        val keptValues = ArrayList<Long>()
        var sum = 0L
        for (player in players) {
            val value = player.value(stat.index, period, calendar, today)
            if (period == Period.ALL) sum += value
            if (value > 0 && player.name != null && !player.excluded && player.id !in leftOut) {
                kept += player
                keptValues += value
            }
        }
        if (period == Period.ALL && stat.index < sums.size) sums[stat.index] = sum
        val order = kept.indices.sortedWith(Comparator { a, b ->
            val byValue = keptValues[b].compareTo(keptValues[a])
            if (byValue != 0) byValue else (kept[a].name ?: "").compareTo(kept[b].name ?: "", ignoreCase = true)
        })
        val players2 = Array(order.size) { kept[order[it]] }
        val values = LongArray(order.size) { keptValues[order[it]] }
        return Board(stat, period, key, players2, values)
    }

    public companion object {
        internal fun key(stat: String, period: Period): String = stat + ":" + period.id

        private val PERIODS = Period.entries.toList()
        private val ALL_ONLY = listOf(Period.ALL)
    }
}
