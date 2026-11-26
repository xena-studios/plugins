package co.xenastudios.xmoney.ledger

import co.xenastudios.core.api.PlayerRef

/**
 * The top list: every player's account, richest first, then by uuid so ties stay put. Changed
 * under the account's lock, read from any thread. On the JVM a skip list, so reading never waits;
 * on Wasm, which has one thread, a sorted list.
 */
internal interface Ranking {
    fun add(balance: Long, id: PlayerRef)

    fun remove(balance: Long, id: PlayerRef)

    /** [count] places from rank [from] (1 is the richest): each balance and account. */
    fun top(from: Int, count: Int): List<Pair<Long, PlayerRef>>

    val size: Int
}

/** A new, empty [Ranking]: a skip list on the JVM, a sorted list on Wasm. */
internal expect fun newRanking(): Ranking

/** The order of [Ranking]: richest first, then the uuid's text. */
internal fun rankOrder(aBalance: Long, a: PlayerRef, bBalance: Long, b: PlayerRef): Int {
    val byBalance = bBalance.compareTo(aBalance)
    return if (byBalance != 0) byBalance else a.toString().compareTo(b.toString())
}
