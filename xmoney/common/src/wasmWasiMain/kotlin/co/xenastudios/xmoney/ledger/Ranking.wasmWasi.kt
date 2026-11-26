package co.xenastudios.xmoney.ledger

import co.xenastudios.core.api.PlayerRef

internal actual fun newRanking(): Ranking = SortedRanking()

private class SortedRanking : Ranking {
    private val balances = ArrayList<Long>()
    private val ids = ArrayList<PlayerRef>()

    private fun find(balance: Long, id: PlayerRef): Int {
        var low = 0
        var high = ids.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val c = rankOrder(balances[mid], ids[mid], balance, id)
            if (c < 0) low = mid + 1 else if (c > 0) high = mid - 1 else return mid
        }
        return -(low + 1)
    }

    override fun add(balance: Long, id: PlayerRef) {
        val at = find(balance, id)
        if (at >= 0) return
        balances.add(-(at + 1), balance)
        ids.add(-(at + 1), id)
    }

    override fun remove(balance: Long, id: PlayerRef) {
        val at = find(balance, id)
        if (at < 0) return
        balances.removeAt(at)
        ids.removeAt(at)
    }

    override fun top(from: Int, count: Int): List<Pair<Long, PlayerRef>> {
        val start = maxOf(0, from - 1)
        val end = minOf(ids.size, start + count)
        return (start until end).map { balances[it] to ids[it] }
    }

    override val size: Int get() = ids.size
}
