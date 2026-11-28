package co.xenastudios.xlimbo

import co.xenastudios.core.api.PlayerRef

/**
 * When each player arrived and when they were last sent back. Kept by the plugin, not the return
 * queue, so it outlives the queue being restarted on reload: changing server-check doesn't give a
 * player the main server keeps refusing three more tries. Server thread only.
 */
public class ReturnHistory {
    private val arrived = HashMap<PlayerRef, Long>()

    // Kept when a player leaves: being sent back is leaving, and a refused player comes back.
    private val tries = HashMap<PlayerRef, ArrayDeque<Long>>()

    public fun arrived(player: PlayerRef, now: Long) {
        arrived[player] = now
    }

    /** Records an arrival for a player already here that nothing has recorded yet. */
    internal fun arrivedIfUnknown(player: PlayerRef, now: Long) {
        if (player !in arrived) arrived[player] = now
    }

    /** How long the player has been here, in milliseconds, or 0 if their arrival wasn't seen. */
    internal fun here(player: PlayerRef, now: Long): Long = now - (arrived[player] ?: now)

    public fun left(player: PlayerRef) {
        arrived.remove(player)
    }

    /**
     * Records a player sent back while the return queue is off (by /join or the API), so coming
     * back moments later still counts as refused. Prunes too: without the queue, nothing else does.
     */
    public fun sent(player: PlayerRef, now: Long) {
        prune(now)
        tried(player, now)
    }

    internal fun tried(player: PlayerRef, now: Long) {
        tries.getOrPut(player) { ArrayDeque() }.addLast(now)
    }

    /** Whether the player was sent back moments ago, so being here again means the main server refused them. */
    public fun bounced(player: PlayerRef, now: Long): Boolean = recentlySent(player, now)

    internal fun recentlySent(player: PlayerRef, now: Long): Boolean {
        val times = tries[player] ?: return false
        return times.isNotEmpty() && now - times.last() < BOUNCE_MILLIS
    }

    internal fun triedTooOften(player: PlayerRef, now: Long): Boolean {
        val times = tries[player] ?: return false
        return times.count { now - it <= TRIES_WINDOW_MILLIS } >= MAX_TRIES
    }

    /** Forgets tries older than the window, and players with none left. */
    internal fun prune(now: Long) {
        val each = tries.entries.iterator()
        while (each.hasNext()) {
            val times = each.next().value
            while (times.isNotEmpty() && now - times.first() > TRIES_WINDOW_MILLIS) times.removeFirst()
            if (times.isEmpty()) each.remove()
        }
    }

    /** `/xlimbo forget`: everything known about [player]; whether there was anything. */
    public fun forget(player: PlayerRef): Boolean {
        val had = arrived.remove(player) != null
        return (tries.remove(player) != null) || had
    }

    public companion object {
        /** Automatic tries per player in [TRIES_WINDOW_MILLIS], so a player the main server keeps refusing isn't bounced forever. */
        public const val MAX_TRIES: Int = 3
        public const val TRIES_WINDOW_MILLIS: Long = 300_000

        /** A player sent back who's still here this long after has been bounced back. */
        public const val BOUNCE_MILLIS: Long = 15_000
    }
}
