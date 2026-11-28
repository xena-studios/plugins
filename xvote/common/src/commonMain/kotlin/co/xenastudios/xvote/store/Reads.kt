package co.xenastudios.xvote.store

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.xvote.vote.Periods
import co.xenastudios.xvote.vote.Voter

/**
 * What the ledger reads into memory: every voter's totals and the party's count, as the plugin
 * starts, and one voter's again after a vote. Apart from [Ledger]'s writes, so a start loads only
 * this (decisions/2026-11-25-a-lighter-core.md).
 */
internal object Reads {
    // ---- what's kept in memory ----

    /** One player's totals, as [loadAll] reads everyone's. */
    fun voter(tx: Tx, player: PlayerRef, periods: Periods, now: Long, siteWindowMs: Long): Voter? = load(tx, periods, now, siteWindowMs, player)[player]

    /** Every player who has voted, with their totals. */
    fun loadAll(tx: Tx, periods: Periods, now: Long, siteWindowMs: Long): Map<PlayerRef, Voter> = load(tx, periods, now, siteWindowMs, null)

    private fun load(tx: Tx, periods: Periods, now: Long, siteWindowMs: Long, only: PlayerRef?): Map<PlayerRef, Voter> {
        val where = if (only == null) "" else " WHERE uuid = ?"
        val and = if (only == null) "" else " AND uuid = ?"
        val one = if (only == null) emptyArray() else arrayOf<Any?>(only.toString())
        val base = HashMap<PlayerRef, LongArray>()
        val names = HashMap<PlayerRef, String>()
        for (row in tx.rows("SELECT uuid, name, total, streak, best_streak, last_day, last_vote FROM {p}voters$where", *one)) {
            val id = row.text(0)?.let(PlayerRef::parseOrNull) ?: continue
            names[id] = row.text(1) ?: ""
            base[id] = longArrayOf(row.longAt(2), row.longAt(3), row.longAt(4), row.longAt(5), row.longAt(6), 0, 0, 0)
        }
        if (base.isEmpty()) return emptyMap()
        val dayStart = periods.dayStart(now)
        val weekStart = periods.weekStart(now)
        val monthStart = periods.monthStart(now)
        val from = minOf(weekStart, monthStart)
        for (row in tx.rows(
            "SELECT uuid, SUM(CASE WHEN at >= ? THEN 1 ELSE 0 END), SUM(CASE WHEN at >= ? THEN 1 ELSE 0 END), SUM(CASE WHEN at >= ? THEN 1 ELSE 0 END)" +
                " FROM {p}votes WHERE at >= ? AND uuid IS NOT NULL AND number IS NOT NULL$and GROUP BY uuid",
            dayStart, weekStart, monthStart, from, *one,
        )) {
            val values = row.text(0)?.let(PlayerRef::parseOrNull)?.let(base::get) ?: continue
            values[5] = row.longAt(1)
            values[6] = row.longAt(2)
            values[7] = row.longAt(3)
        }
        val sites = HashMap<PlayerRef, HashMap<String, Long>>()
        for (row in tx.rows("SELECT uuid, site, MAX(at) FROM {p}votes WHERE at >= ? AND uuid IS NOT NULL$and GROUP BY uuid, site", now - siteWindowMs, *one)) {
            val id = row.text(0)?.let(PlayerRef::parseOrNull) ?: continue
            val bySite = sites.getOrPut(id) { HashMap() }
            val key = (row.text(1) ?: "").lowercase()
            val at = row.longAt(2)
            if (at > (bySite[key] ?: Long.MIN_VALUE)) bySite[key] = at
        }
        val dayKey = periods.dayKey(now)
        val weekKey = periods.weekKey(now)
        val monthKey = periods.monthKey(now)
        val voters = HashMap<PlayerRef, Voter>(base.size * 2)
        for ((id, v) in base) {
            voters[id] = Voter(
                id, names[id] ?: "", v[0].toInt(), v[1].toInt(), v[2].toInt(), v[3].toInt(), v[4],
                dayKey, v[5].toInt(), weekKey, v[6].toInt(), monthKey, v[7].toInt(), sites[id] ?: emptyMap(),
            )
        }
        return voters
    }


    /** The party's count, and when the current round started. */
    class PartyState(val votes: Long, val since: Long)

    fun party(tx: Tx, lock: Boolean): PartyState {
        var votes = 0L
        var since = 0L
        for (row in tx.rows("SELECT name, value FROM {p}state WHERE name IN ('party_votes', 'party_since')" + if (lock) tx.forUpdate else "")) {
            if (row.text(0) == "party_votes") votes = row.longAt(1) else since = row.longAt(1)
        }
        return PartyState(votes, since)
    }

}
