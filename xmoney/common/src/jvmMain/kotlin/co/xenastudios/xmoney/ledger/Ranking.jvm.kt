package co.xenastudios.xmoney.ledger

import co.xenastudios.core.api.PlayerRef
import java.util.concurrent.ConcurrentSkipListSet

internal actual fun newRanking(): Ranking = SkipListRanking()

private class SkipListRanking : Ranking {
    // Comparable, rather than a comparator lambda: a lambda is a class of its own as it's made.
    private class Rank(val balance: Long, val id: PlayerRef) : Comparable<Rank> {
        override fun compareTo(other: Rank): Int = rankOrder(balance, id, other.balance, other.id)
    }

    private val set = ConcurrentSkipListSet<Rank>()

    override fun add(balance: Long, id: PlayerRef) {
        set.add(Rank(balance, id))
    }

    override fun remove(balance: Long, id: PlayerRef) {
        set.remove(Rank(balance, id))
    }

    override fun top(from: Int, count: Int): List<Pair<Long, PlayerRef>> {
        val out = ArrayList<Pair<Long, PlayerRef>>(minOf(count, 100))
        var rank = 0
        for (entry in set) {
            if (out.size >= count) break
            rank++
            if (rank >= from) out += entry.balance to entry.id
        }
        return out
    }

    override val size: Int get() = set.size
}
