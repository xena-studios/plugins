package co.xenastudios.xteleport.tpa

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.concurrentMap
import co.xenastudios.core.common.locked

/**
 * Pending teleport requests, by who they're for. A player can have several at once, one from each
 * sender. Nothing runs on a timer: a request older than the expiry is dropped whenever the list
 * it's in is read or added to, so a request that runs out costs nothing until someone looks. A
 * player's list goes when they leave. Each list is replaced whole on every change. Any thread.
 */
public class Requests(private val clock: () -> Long) {

    /** One request. [here] is `/tpahere`: the target goes to the sender. */
    public class Request(public val from: PlayerRef, public val fromName: String, public val here: Boolean, public val sentAt: Long)

    private val inbox = concurrentMap<PlayerRef, List<Request>>()

    public fun now(): Long = clock()

    private fun fresh(list: List<Request>, oldest: Long): MutableList<Request> {
        val kept = ArrayList<Request>(list.size)
        for (request in list) if (request.sentAt >= oldest) kept += request
        return kept
    }

    /** Adds a request, unless the sender already has one waiting for this target: `false` then, and the old one kept. */
    public fun add(to: PlayerRef, request: Request, expireMillis: Long): Boolean = locked(inbox) {
        val kept = fresh(inbox[to].orEmpty(), clock() - expireMillis)
        val added = kept.none { it.from == request.from }
        if (added) kept += request
        put(to, kept)
        added
    }

    /** Whether [from] has a request to [to] still waiting. */
    public fun asked(to: PlayerRef, from: PlayerRef, expireMillis: Long): Boolean = pending(to, expireMillis).any { it.from == from }

    /** A player's requests still waiting, oldest first. */
    public fun pending(to: PlayerRef, expireMillis: Long): List<Request> = locked(inbox) {
        val list = inbox[to] ?: return@locked emptyList()
        val kept = fresh(list, clock() - expireMillis)
        if (kept.size != list.size) put(to, kept)
        kept
    }

    /** Takes a request out: the one from [from], or the newest if it's `null`; `null` if there's none waiting. */
    public fun take(to: PlayerRef, from: PlayerRef?, expireMillis: Long): Request? = locked(inbox) {
        val kept = fresh(inbox[to].orEmpty(), clock() - expireMillis)
        // The last match is the newest.
        val chosen = kept.lastOrNull { from == null || it.from == from }
        if (chosen != null) kept.remove(chosen)
        put(to, kept)
        chosen
    }

    /** Takes back what [from] sent: to [to], or to everyone if it's `null`. Returns who the requests were for. */
    public fun cancel(from: PlayerRef, to: PlayerRef?, expireMillis: Long): List<PlayerRef> = locked(inbox) {
        val oldest = clock() - expireMillis
        val targets = ArrayList<PlayerRef>()
        for (target in inbox.keys.toList()) {
            if (to != null && to != target) continue
            val kept = fresh(inbox[target].orEmpty(), oldest)
            if (kept.removeAll { it.from == from }) targets += target
            put(target, kept)
        }
        targets
    }

    /** Forgets the requests for a player who left. Ones they sent are skipped when answered. */
    public fun forget(player: PlayerRef) {
        inbox.remove(player)
    }

    public fun clear() {
        inbox.clear()
    }

    private fun put(to: PlayerRef, list: List<Request>) {
        if (list.isEmpty()) inbox.remove(to) else inbox[to] = list.toList()
    }
}

/**
 * When each player may use each command again, in memory. Kept through a quit, so leaving and
 * rejoining doesn't skip a cooldown; ones that ran out are dropped as players leave.
 */
public class Cooldowns(private val clock: () -> Long) {
    private val until = concurrentMap<PlayerRef, MutableMap<String, Long>>()

    /** Milliseconds left on a cooldown, or 0. Any thread. */
    public fun remaining(player: PlayerRef, key: String): Long {
        val end = until[player]?.get(key) ?: return 0
        return (end - clock()).coerceAtLeast(0)
    }

    /** Starts a cooldown. Nothing for 0 seconds or a blank key. */
    public fun start(player: PlayerRef, key: String, seconds: Int) {
        if (seconds <= 0 || key.isEmpty()) return
        val keys = locked(until) { until[player] ?: concurrentMap<String, Long>().also { until[player] = it } }
        keys[key] = clock() + seconds * 1000L
    }

    /** Drops every cooldown that ran out, as players leave, so the map can't grow forever. */
    public fun prune() {
        val now = clock()
        locked(until) {
            for ((player, keys) in until.entries.toList()) {
                for ((key, end) in keys.entries.toList()) if (end <= now) keys.remove(key)
                if (keys.isEmpty()) until.remove(player)
            }
        }
    }

    internal val players: Int get() = until.size
}
