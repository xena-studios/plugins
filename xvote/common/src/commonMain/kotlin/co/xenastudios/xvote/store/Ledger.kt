package co.xenastudios.xvote.store

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.xvote.vote.Facts
import co.xenastudios.xvote.vote.IncomingVote
import co.xenastudios.xvote.vote.Names
import co.xenastudios.xvote.vote.Periods
import co.xenastudios.xvote.vote.Voter

/**
 * The SQL behind the ledger: 1.x's `Ledger`, statement for statement. Every function runs inside one
 * [Tx], on the database's writer (or a reader, for the reads that say so).
 */
internal object Ledger {
    /** Unmatched votes matched per player join; a name with more waits for the next join. */
    const val MATCH_LIMIT = 500

    /**
     * Whether a vote repeats one already stored: from the same site for the same player (or name, if
     * unmatched) within the cooldown, or the same list timestamp within a day (a replayed v1 vote).
     */
    fun isRepeat(tx: Tx, player: PlayerRef?, nameKey: String, vote: IncomingVote, cooldownMs: Long): Boolean {
        val since = vote.receivedAt - maxOf(cooldownMs, 0)
        val day = vote.receivedAt - 86_400_000L
        val who = if (player != null) "uuid = ?" else "name_key = ? AND uuid IS NULL"
        return tx.count(
            "SELECT COUNT(*) FROM {p}votes WHERE $who AND LOWER(site) = LOWER(?) AND (at > ? OR (at > ? AND list_time = ? AND list_time <> ''))",
            player?.toString() ?: nameKey, vote.site, if (cooldownMs > 0) since else Long.MAX_VALUE, day, vote.timestamp,
        ) > 0
    }

    /** Stores a vote, claimed by [claim] if this server pays it now. Returns its id. */
    fun insert(tx: Tx, vote: IncomingVote, player: PlayerRef?, name: String, claim: String?): Long = tx.insert(
        "INSERT INTO {p}votes (uuid, name, name_key, site, address, list_time, source, at, claim, paid_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        // Never the voter's address: xVote doesn't need it (Tables).
        player?.toString(), name, Names.key(name), vote.site, "", vote.timestamp, vote.source.name, vote.receivedAt, claim,
        if (claim == null) null else vote.receivedAt,
    )

    /** Locks the player's `voters` row (MySQL), so two servers counting their votes take turns. */
    fun lock(tx: Tx, player: PlayerRef) {
        if (tx.mysql) tx.rows("SELECT total FROM {p}voters WHERE uuid = ? FOR UPDATE", player.toString())
    }

    /**
     * Counts a vote that has its player: works out its place in their count, stores that on the
     * vote, and moves their totals and streak on.
     */
    fun count(tx: Tx, id: Long, player: PlayerRef, name: String, site: String, at: Long, periods: Periods): Facts {
        val row = tx.rows("SELECT total, streak, best_streak, last_day, last_vote FROM {p}voters WHERE uuid = ?${tx.forUpdate}", player.toString()).firstOrNull()
        val known = row != null
        val total = row?.int(0) ?: 0
        val streak = row?.int(1) ?: 0
        val best = row?.int(2) ?: 0
        val lastDay = row?.int(3) ?: Int.MIN_VALUE
        val lastVote = row?.long(4) ?: 0L
        val day = periods.day(at)
        // From the row just locked, not a count of votes: on MySQL a plain SELECT reads the
        // transaction's snapshot, taken before the lock, so it could miss a vote another server
        // counted a moment ago, and both would pay the first vote of the day.
        val firstToday = when {
            !known || day > lastDay -> true
            day == lastDay -> false
            // An older vote counted late (matched when its name first joined): its own day decides.
            else -> !countedOn(tx, player, id, periods.dayStart(at), periods.dayEnd(at))
        }
        val number = total + 1
        val nextStreak = if (known) Periods.nextStreak(lastDay, streak, day) else 1
        val nextBest = maxOf(best, nextStreak)
        if (known) {
            tx.exec(
                "UPDATE {p}voters SET name = ?, total = ?, streak = ?, best_streak = ?, last_day = ?, last_vote = ? WHERE uuid = ?",
                name, number, nextStreak, nextBest, maxOf(lastDay, day), maxOf(lastVote, at), player.toString(),
            )
        } else {
            tx.exec(
                "INSERT INTO {p}voters (uuid, name, total, streak, best_streak, last_day, last_vote) VALUES (?, ?, ?, ?, ?, ?, ?)",
                player.toString(), name, number, nextStreak, nextBest, day, at,
            )
        }
        tx.exec("UPDATE {p}votes SET uuid = ?, number = ?, streak = ?, first_today = ? WHERE id = ?", player.toString(), number, nextStreak, if (firstToday) 1 else 0, id)
        return Facts(id, player, name, site, number, nextStreak, firstToday, at)
    }

    // Whether the player has another counted vote in [from, to).
    private fun countedOn(tx: Tx, player: PlayerRef, id: Long, from: Long, to: Long): Boolean =
        tx.count("SELECT COUNT(*) FROM {p}votes WHERE uuid = ? AND at >= ? AND at < ? AND number IS NOT NULL AND id <> ?", player.toString(), from, to, id) > 0

    /**
     * Gives unmatched votes for these names to the player and counts them, oldest first. Each is
     * taken with `WHERE uuid IS NULL`, so a vote is matched once even if two servers try at once.
     */
    fun match(tx: Tx, player: PlayerRef, name: String, nameKeys: List<String>, periods: Periods): List<Facts> {
        if (nameKeys.isEmpty()) return emptyList()
        val marks = nameKeys.joinToString(", ") { "?" }
        val found = tx.rows("SELECT id, at, site FROM {p}votes WHERE uuid IS NULL AND name_key IN ($marks) ORDER BY id LIMIT $MATCH_LIMIT", *nameKeys.toTypedArray())
        val matched = ArrayList<Facts>()
        for (row in found) {
            val id = row.longAt(0)
            if (tx.exec("UPDATE {p}votes SET uuid = ? WHERE id = ? AND uuid IS NULL", player.toString(), id) == 0) continue
            matched += count(tx, id, player, name, row.text(2) ?: "", row.longAt(1), periods)
        }
        return matched
    }

    /** Takes every unpaid vote of the player's for this server to pay. */
    fun claimUnpaid(tx: Tx, player: PlayerRef, claim: String, now: Long): List<Facts> {
        if (tx.exec("UPDATE {p}votes SET claim = ?, paid_at = ? WHERE uuid = ? AND claim IS NULL AND number IS NOT NULL", claim, now, player.toString()) == 0) {
            return emptyList()
        }
        return tx.rows("SELECT id, name, site, number, streak, first_today, at FROM {p}votes WHERE claim = ? ORDER BY id", claim).map {
            Facts(it.longAt(0), player, it.text(1) ?: "", it.text(2) ?: "", it.int(3), it.int(4), it.int(5) == 1, it.longAt(6))
        }
    }

    /** Gives a claimed vote back, unpaid, for a player who left before it could be paid. */
    fun unclaim(tx: Tx, id: Long, claim: String) {
        tx.exec("UPDATE {p}votes SET claim = NULL, paid_at = NULL WHERE id = ? AND claim = ?", id, claim)
    }

    /** A vote as stored, for another server's news of it. */
    class Stored(val id: Long, val player: PlayerRef?, val name: String, val site: String, val number: Int?, val claim: String?)

    fun read(tx: Tx, id: Long): Stored? = tx.rows("SELECT uuid, name, site, number, claim FROM {p}votes WHERE id = ?", id).firstOrNull()?.let {
        Stored(id, it.text(0)?.let(PlayerRef::parseOrNull), it.text(1) ?: "", it.text(2) ?: "", it.number(3)?.toInt(), it.text(4))
    }

    /** Keeps a voter's name current when they join under a new one, so votes for it find them. */
    fun rename(tx: Tx, player: PlayerRef, name: String) {
        tx.exec("UPDATE {p}voters SET name = ? WHERE uuid = ? AND name <> ?", name, player.toString(), name)
    }

    fun party(tx: Tx, state: Reads.PartyState) {
        tx.exec("UPDATE {p}state SET value = ? WHERE name = ?", state.votes, "party_votes")
        tx.exec("UPDATE {p}state SET value = ? WHERE name = ?", state.since, "party_since")
    }

    // ---- items that didn't fit ----

    fun hold(tx: Tx, player: PlayerRef, items: List<ByteArray>, now: Long) {
        for (item in items) tx.exec("INSERT INTO {p}held (uuid, item, created) VALUES (?, ?, ?)", player.toString(), item, now)
    }

    /** Takes the player's held items out, so they're handed out once. */
    fun takeHeld(tx: Tx, player: PlayerRef): List<ByteArray> {
        val rows = tx.rows("SELECT id, item FROM {p}held WHERE uuid = ? ORDER BY id${tx.forUpdate}", player.toString())
        val items = ArrayList<ByteArray>(rows.size)
        for (row in rows) {
            tx.exec("DELETE FROM {p}held WHERE id = ?", row.longAt(0))
            row.bytes(1)?.let { items += it }
        }
        return items
    }

    // ---- imports ----

    /** When totals were imported from [source] before, or 0. */
    fun imported(tx: Tx, source: String): Long =
        tx.rows("SELECT value FROM {p}state WHERE name = ?", "import_" + source.lowercase()).firstOrNull()?.longAt(0) ?: 0L

    /** Adds other plugins' totals to players' all-time totals, and records that it was done. Their streaks are left as they are. */
    fun addImported(tx: Tx, source: String, players: List<co.xenastudios.xvote.Imported>, periods: Periods, now: Long): Int {
        var added = 0
        for (player in players) {
            val changed = tx.exec(
                "UPDATE {p}voters SET total = total + ?, last_vote = CASE WHEN last_vote < ? THEN ? ELSE last_vote END WHERE uuid = ?",
                player.total, player.lastVote, player.lastVote, player.player.toString(),
            )
            if (changed == 0) {
                tx.exec(
                    "INSERT INTO {p}voters (uuid, name, total, streak, best_streak, last_day, last_vote) VALUES (?, ?, ?, 0, 0, ?, ?)",
                    player.player.toString(), player.name.ifBlank { player.player.toString().substring(0, 8) }, player.total,
                    if (player.lastVote > 0) periods.day(player.lastVote) else 0, player.lastVote,
                )
            }
            added++
        }
        val key = "import_" + source.lowercase()
        if (tx.exec("UPDATE {p}state SET value = ? WHERE name = ?", now, key) == 0) {
            tx.exec("INSERT INTO {p}state (name, value) VALUES (?, ?)", key, now)
        }
        return added
    }

    // ---- upkeep, status and forget ----

    /** Deletes votes for names that never joined, older than [before]. */
    fun forgetUnknown(tx: Tx, before: Long): Int = tx.exec("DELETE FROM {p}votes WHERE uuid IS NULL AND at < ?", before)

    /** Votes waiting to be paid, and votes waiting for their name to join. */
    fun waiting(tx: Tx): LongArray = longArrayOf(
        tx.count("SELECT COUNT(*) FROM {p}votes WHERE claim IS NULL AND uuid IS NOT NULL"),
        tx.count("SELECT COUNT(*) FROM {p}votes WHERE uuid IS NULL"),
    )

    /** One of the last votes, for `/xvote status`. */
    class Recent(val name: String, val site: String, val source: String, val at: Long, val paid: Boolean, val matched: Boolean)

    fun recent(tx: Tx, limit: Int): List<Recent> = tx.rows("SELECT name, site, source, at, claim, uuid FROM {p}votes ORDER BY id DESC LIMIT $limit").map {
        Recent(it.text(0) ?: "", it.text(1) ?: "", it.text(2) ?: "", it.longAt(3), it.text(4) != null, it.text(5) != null)
    }

    /** How many rows each table has about [player], for `/xvote forget`'s count. */
    fun about(tx: Tx, player: PlayerRef): Long {
        val id = player.toString()
        return tx.count("SELECT COUNT(*) FROM {p}votes WHERE uuid = ?", id) + tx.count("SELECT COUNT(*) FROM {p}voters WHERE uuid = ?", id) +
            tx.count("SELECT COUNT(*) FROM {p}held WHERE uuid = ?", id)
    }

    /** Deletes everything about [player]: their votes, their totals and their held items. */
    fun forget(tx: Tx, player: PlayerRef): Int {
        val id = player.toString()
        return tx.exec("DELETE FROM {p}votes WHERE uuid = ?", id) + tx.exec("DELETE FROM {p}voters WHERE uuid = ?", id) +
            tx.exec("DELETE FROM {p}held WHERE uuid = ?", id)
    }
}
