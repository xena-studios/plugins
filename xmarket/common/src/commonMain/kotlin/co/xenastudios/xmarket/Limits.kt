package co.xenastudios.xmarket

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.onSettled
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.concurrentMap
import co.xenastudios.core.common.platform.Log
import co.xenastudios.xmarket.api.TradeKind
import co.xenastudios.xmarket.price.Limit
import co.xenastudios.xmarket.price.Period
import co.xenastudios.xmarket.store.Store

/**
 * How much of each limited entry each player, and the whole server, has had this period: from
 * memory, for online players and the server, read from the database as a player joins and as
 * the database opens. A period starts again by comparing dates as the count is read, never on
 * a timer: a day is the server's calendar day (`rules.timezone` hours from UTC), a week starts
 * on Monday, and `ever` never ends.
 */
public class Limits(private val now: () -> Long, private val log: Log) {
    private class Count(val period: Long, val amount: Int)

    // holder (a player's UUID, or "*") -> limit key -> count.
    private val counts = concurrentMap<String, MutableMap<String, Count>>()

    @kotlin.concurrent.Volatile
    public var offsetHours: Int = 0

    @kotlin.concurrent.Volatile
    public var store: Store? = null
        set(value) {
            field = value
            // Made after the database opened (the first limit asked about): the server's counts now.
            if (value != null && !loadedServer) {
                loadedServer = true
                load(SERVER)
            }
        }

    private var loadedServer = false

    /** The period [period] is in now: the day's or week's number since 1970, or 0 for `ever`. */
    public fun current(period: Period): Long {
        val days = (now() + offsetHours * HOUR).floorDiv(DAY)
        return when (period) {
            Period.DAY -> days
            // 1 January 1970 was a Thursday: day 4 is the first Monday.
            Period.WEEK -> (days - 4).floorDiv(7L)
            Period.EVER -> 0L
        }
    }

    private fun holder(player: PlayerRef, limit: Limit): String = if (limit.server) SERVER else player.toString()

    /** The key a limit's count is kept under: the entry, what it limits, and its period. */
    public fun key(entry: String, limit: Limit): String = "$entry:${limit.kind.name.lowercase()}:${limit.period.word}"

    /** How many more [player] may trade of [entry] under [limit] now. */
    public fun left(player: PlayerRef, entry: String, limit: Limit): Int {
        val count = counts[holder(player, limit)]?.get(key(entry, limit))
        val used = if (count == null || count.period != current(limit.period)) 0 else count.amount
        return (limit.amount - used).coerceAtLeast(0)
    }

    /** The least [player] has left of [entry] for [kind] under every limit in [limits], or `null` with none. */
    public fun left(player: PlayerRef, entry: String, limits: List<Limit>, kind: TradeKind): Int? {
        var least: Int? = null
        for (l in limits) if (l.kind == kind) {
            val left = left(player, entry, l)
            if (least == null || left < least) least = left
        }
        return least
    }

    /** Counts [amount] more of [entry] for [player] under each of [limits] for [kind], and saves it. */
    public fun add(player: PlayerRef, entry: String, limits: List<Limit>, kind: TradeKind, amount: Int) {
        for (l in limits) {
            if (l.kind != kind) continue
            val holder = holder(player, l)
            val key = key(entry, l)
            val period = current(l.period)
            val map = counts.getOrPut(holder) { concurrentMap() }
            val before = map[key]
            val used = (if (before == null || before.period != period) 0 else before.amount) + amount
            map[key] = Count(period, used)
            store?.setUsed(Store.Used(holder, key, period, used))?.onSettled { r, e ->
                if (r !is Outcome.Ok) log.warn("Couldn't save a limit's count for $holder: ${(r as? Outcome.Err)?.error ?: e}")
            }
        }
    }

    /** Reads [holder]'s counts (a player's UUID, or the server's `*`) from the database. */
    public fun load(holder: String) {
        val s = store ?: return
        s.used(holder).onSettled { r, e ->
            if (r is Outcome.Ok) {
                val map = counts.getOrPut(holder) { concurrentMap() }
                for (u in r.value) map[u.key] = Count(u.period, u.amount)
            } else {
                log.warn("Couldn't read the limits' counts for $holder: ${(r as? Outcome.Err)?.error ?: e}")
            }
        }
    }

    public fun load(player: PlayerRef) {
        load(player.toString())
    }

    public fun forget(player: PlayerRef) {
        counts.remove(player.toString())
    }

    public fun clear() {
        counts.clear()
    }

    public companion object {
        public const val SERVER: String = "*"
        private const val HOUR = 3_600_000L
        private const val DAY = 86_400_000L
    }
}
