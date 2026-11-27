package co.xenastudios.xvote.vote

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.concurrentMap
import co.xenastudios.core.common.locked
import co.xenastudios.xvote.api.Period
import co.xenastudios.xvote.api.VoteSource
import kotlin.concurrent.Volatile

/**
 * One player's votes, as last read from the ledger. Never changed: a new vote makes a new one,
 * swapped in whole, so any thread can read it. The day, week and month totals are kept with the key
 * of the period they were counted in; read through [today], [week] and [month], a total from a
 * period that's over is 0. [lastBySite]: when they last voted on each site, by service name in lower
 * case, for the last couple of days.
 */
public class Voter(
    public val id: PlayerRef,
    public val name: String,
    public val total: Int,
    public val streak: Int,
    public val bestStreak: Int,
    public val lastDay: Int,
    public val lastVote: Long,
    public val dayKey: Int,
    public val day: Int,
    public val weekKey: Int,
    public val week: Int,
    public val monthKey: Int,
    public val month: Int,
    public val lastBySite: Map<String, Long>,
) {
    public fun today(periods: Periods, now: Long): Int = if (periods.dayKey(now) == dayKey) day else 0

    public fun week(periods: Periods, now: Long): Int = if (periods.weekKey(now) == weekKey) week else 0

    public fun month(periods: Periods, now: Long): Int = if (periods.monthKey(now) == monthKey) month else 0

    /** The streak as it stands: 0 once a whole day has passed without a vote. */
    public fun streakNow(periods: Periods, now: Long): Int = if (periods.day(now) - lastDay <= 1) streak else 0

    /** When they last voted on a site, or 0. */
    public fun lastOn(service: String): Long = lastBySite[service.lowercase()] ?: 0L

    override fun equals(other: Any?): Boolean = other is Voter && other.id == id && other.name == name && other.total == total &&
        other.streak == streak && other.bestStreak == bestStreak && other.lastDay == lastDay && other.lastVote == lastVote &&
        other.dayKey == dayKey && other.day == day && other.weekKey == weekKey && other.week == week && other.monthKey == monthKey &&
        other.month == month && other.lastBySite == lastBySite

    override fun hashCode(): Int = (id.hashCode() * 31 + total) * 31 + lastVote.hashCode()

    override fun toString(): String = "Voter($name, $total)"
}

/**
 * A counted vote: what decides its rewards. Worked out once, when the vote is matched to a player,
 * and stored with it, so whichever server pays it pays the same. [number] is the player's total
 * with this vote, [streak] their streak with it, [firstToday] whether it was their first that day.
 */
public class Facts(
    public val id: Long,
    public val player: PlayerRef,
    public val name: String,
    public val site: String,
    public val number: Int,
    public val streak: Int,
    public val firstToday: Boolean,
    public val at: Long,
) {
    override fun toString(): String = "Facts(#$id $name $site $number)"
}

/**
 * A vote as it arrived, before it's checked or stored. Every field is cleaned as it's made:
 * control characters (line breaks, escapes) are taken out, since they come from the network and
 * end up in the console and in commands, and each is cut to [MAX_FIELD] characters. [receivedAt]
 * is when this server got it: what xVote counts by.
 */
public class IncomingVote(
    site: String?,
    username: String?,
    address: String?,
    timestamp: String?,
    public val source: VoteSource,
    public val receivedAt: Long,
) {
    /** The site's service name, as the list sent it. */
    public val site: String = clean(site)

    /** The name the list sent. */
    public val username: String = clean(username).trim()

    /** The voter's address as the list sent it, or empty. Never stored. */
    public val address: String = clean(address)

    /** The list's own timestamp, unchanged, or empty. */
    public val timestamp: String = clean(timestamp)

    /** The same vote from another [source]. */
    public fun from(source: VoteSource): IncomingVote = IncomingVote(site, username, address, timestamp, source, receivedAt)

    override fun toString(): String = "IncomingVote($site, $username, $source)"

    public companion object {
        /** Longest site name, user name, address or timestamp kept; lists don't send more. */
        public const val MAX_FIELD: Int = 64

        /** Text from the network, safe to log: control and format characters, and Unicode's line and paragraph breaks, taken out; cut to [MAX_FIELD]. */
        public fun clean(value: String?): String {
            if (value == null) return ""
            val out = StringBuilder(minOf(value.length, MAX_FIELD))
            for (c in value) {
                if (out.length >= MAX_FIELD) break
                if (unprintable(c)) continue
                out.append(c)
            }
            return out.toString()
        }

        private fun unprintable(c: Char): Boolean {
            val code = c.code
            // Cc
            if (code < 0x20 || code in 0x7F..0x9F) return true
            // Zl, Zp
            if (code == 0x2028 || code == 0x2029) return true
            // Cf: the format characters servers and terminals act on.
            return code == 0xAD || code in 0x600..0x605 || code == 0x61C || code == 0x6DD || code == 0x70F || code == 0x180E ||
                code in 0x200B..0x200F || code in 0x202A..0x202E || code in 0x2060..0x2064 || code in 0x2066..0x206F ||
                code == 0xFEFF || code in 0xFFF9..0xFFFB || c.isSurrogate()
        }
    }
}

/**
 * Names as lists send them. Lists send whatever the player typed: Bedrock players often leave off
 * Floodgate's prefix, and gamertags can have spaces, which Floodgate turns into underscores.
 */
public object Names {
    /** `\w{1,16}`, as 1.x's pattern: letters, digits and `_`. */
    private fun plain(name: String): Boolean = name.length in 1..16 && name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' }

    /** The name tidied up as Floodgate would have it, or `null` if it can't be a player's name. */
    public fun clean(sent: String, bedrockPrefix: String): String? {
        val name = sent.trim().replace(' ', '_')
        if (plain(name)) return name
        if (bedrockPrefix.isNotEmpty() && name.startsWith(bedrockPrefix) && plain(name.substring(bedrockPrefix.length))) return name
        return null
    }

    /** The names to look a voter up by, in order: as sent, then with Bedrock's prefix. */
    public fun lookups(name: String, bedrockPrefix: String): List<String> =
        if (bedrockPrefix.isNotEmpty() && !name.startsWith(bedrockPrefix)) listOf(name, bedrockPrefix + name) else listOf(name)

    /**
     * The name keys of unmatched votes a joining player gets: their own name, and for a Bedrock
     * player, their name without the prefix, which is what they likely typed.
     */
    public fun matchKeys(playerName: String, bedrockPrefix: String): List<String> {
        val keys = ArrayList<String>(2)
        keys += playerName.lowercase()
        if (bedrockPrefix.isNotEmpty() && playerName.startsWith(bedrockPrefix) && playerName.length > bedrockPrefix.length) {
            val bare = playerName.substring(bedrockPrefix.length).lowercase()
            if (bare !in keys) keys += bare
        }
        return keys
    }

    /** A name as votes are matched by it. */
    public fun key(name: String): String = name.lowercase()
}

/**
 * Who is online here, by uuid and by name, for the receiver and the database's threads to ask
 * without touching the server's own player list. Kept on join and quit. On a proxy, everyone on the
 * network, with the server each is on.
 */
public class Online {
    private val names = concurrentMap<PlayerRef, String>()
    private val ids = concurrentMap<String, PlayerRef>()
    private val seats = concurrentMap<PlayerRef, String>()

    public fun add(id: PlayerRef, name: String) {
        names[id] = name
        ids[name.lowercase()] = id
    }

    public fun remove(id: PlayerRef, name: String) {
        names.remove(id)
        seats.remove(id)
        if (ids[name.lowercase()] == id) ids.remove(name.lowercase())
    }

    /** The proxy only: [id] is now on [server]. */
    public fun seat(id: PlayerRef, server: String) {
        if (id in names) seats[id] = server
    }

    /** The proxy only: the server [id] is on, or `null`. */
    public fun server(id: PlayerRef): String? = seats[id]

    /** Everyone online, uuid to name, as it is now. */
    public fun all(): Map<PlayerRef, String> = HashMap(names)

    public fun clear() {
        names.clear()
        ids.clear()
        seats.clear()
    }

    public fun has(id: PlayerRef): Boolean = id in names

    /** The player with this name, ignoring case, if they're online. */
    public fun byName(name: String): PlayerRef? = ids[name.lowercase()]

    /** Their name as they write it, if they're online. */
    public fun name(id: PlayerRef): String? = names[id]

    public val size: Int get() = names.size
}

/**
 * Every voter's totals, in memory, and the leaderboards made from them. Safe from any thread:
 * voters are swapped whole, and a leaderboard is a list that's never changed. A leaderboard is
 * sorted the first time it's asked for after a vote, or after its period rolls over, and kept
 * until the next: votes are rare next to the scoreboards and menus asking, so a vote costs one
 * sort, not one per question.
 */
public class Voters(periods: Periods) {
    /** A place on a leaderboard. */
    public class Ranked(public val rank: Int, public val voter: Voter, public val votes: Int)

    private class Board(val periodKey: Int, val version: Long, val ranked: List<Ranked>)

    private val byId = concurrentMap<PlayerRef, Voter>()
    private val byName = concurrentMap<String, PlayerRef>()
    private val boards = concurrentMap<Period, Board>()
    private val lock = Any()
    private var changes = 0L

    @Volatile
    public var periods: Periods = periods
        private set

    /** New period settings (a reload). Totals stay as read; leaderboards are sorted again. */
    public fun periods(periods: Periods) {
        this.periods = periods
        bump()
    }

    private fun bump() = locked(lock) { changes++ }

    /** Bumped whenever a leaderboard needs sorting again, for tests. */
    internal val version: Long get() = locked(lock) { changes }

    public fun putAll(voters: Collection<Voter>) {
        for (voter in voters) put(voter)
    }

    /** Every voter, forgotten (a fresh read follows). */
    public fun clear() {
        byId.clear()
        byName.clear()
        bump()
    }

    /** A voter as just read. One that's unchanged (a join with no new votes) leaves the leaderboards alone. */
    public fun put(voter: Voter) {
        val before = byId.put(voter.id, voter)
        if (voter == before) return
        if (before != null && !before.name.equals(voter.name, ignoreCase = true) && byName[before.name.lowercase()] == voter.id) {
            byName.remove(before.name.lowercase())
        }
        byName[voter.name.lowercase()] = voter.id
        bump()
    }

    /** A player's data deleted (`/xvote forget`). */
    public fun remove(id: PlayerRef) {
        val before = byId.remove(id) ?: return
        if (byName[before.name.lowercase()] == id) byName.remove(before.name.lowercase())
        bump()
    }

    /** A player joined under a name: if someone else voted under it before, that was their old name. */
    public fun joinedAs(id: PlayerRef, name: String) {
        val key = name.lowercase()
        val holder = byName[key]
        if (holder != null && holder != id) byName.remove(key)
    }

    public operator fun get(id: PlayerRef): Voter? = byId[id]

    /** A player who has voted, by the name they voted with, ignoring case. */
    public fun byName(name: String): PlayerRef? = byName[name.lowercase()]

    public val size: Int get() = byId.size

    /** A player's votes in [period], as of [now]. */
    public fun votes(voter: Voter, period: Period, now: Long): Int {
        val current = periods
        return when (period) {
            Period.DAY -> voter.today(current, now)
            Period.WEEK -> voter.week(current, now)
            Period.MONTH -> voter.month(current, now)
            Period.ALL -> voter.total
        }
    }

    /** Everyone with a vote in [period], most first; ties go to whoever got there first. */
    public fun top(period: Period, now: Long): List<Ranked> {
        val current = periods
        val key = when (period) {
            Period.DAY -> current.dayKey(now)
            Period.WEEK -> current.weekKey(now)
            Period.MONTH -> current.monthKey(now)
            Period.ALL -> 0
        }
        val seen = version
        val board = boards[period]
        if (board != null && board.periodKey == key && board.version == seen) return board.ranked
        val unsorted = ArrayList<Ranked>()
        for (voter in byId.values) {
            val votes = votes(voter, period, now)
            if (votes > 0) unsorted += Ranked(0, voter, votes)
        }
        unsorted.sortWith(RANKING)
        val ranked = ArrayList<Ranked>(unsorted.size)
        for ((i, entry) in unsorted.withIndex()) ranked += Ranked(i + 1, entry.voter, entry.votes)
        boards[period] = Board(key, seen, ranked)
        return ranked
    }

    private companion object {
        // Most votes, then whoever got there first, then by name: a class, not a chain of lambdas.
        val RANKING = object : Comparator<Ranked> {
            override fun compare(a: Ranked, b: Ranked): Int {
                if (a.votes != b.votes) return b.votes.compareTo(a.votes)
                if (a.voter.lastVote != b.voter.lastVote) return a.voter.lastVote.compareTo(b.voter.lastVote)
                return a.voter.name.compareTo(b.voter.name, ignoreCase = true)
            }
        }
    }
}
