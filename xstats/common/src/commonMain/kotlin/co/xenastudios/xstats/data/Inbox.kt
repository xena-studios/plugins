package co.xenastudios.xstats.data

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.locked
import co.xenastudios.core.common.platform.Log
import kotlin.concurrent.Volatile

/**
 * Events waiting for the next save: what makes counting cheap. Adding is a map update under one
 * short lock, from any thread; nothing touches the database until the save.
 *
 * - Other plugins' changes are added up as they come: a thousand increments of one stat for one
 *   player between two saves wait as one number, and several sets in a row as their first and last
 *   value and the rises between. A set and an increment of the same stat keep their order, and so
 *   do changes and a player's join or reading: once one of those is waiting, later changes wait
 *   behind it, never added into one ahead. (Another plugin's join listener can add to a stat before
 *   xStats hears of the join.)
 * - Each player's events are applied in the order they happened. If one can't be (their rows
 *   couldn't be read), it and every later one of theirs wait for the next save, in order;
 *   everyone else's carry on.
 * - Past [warnAt] waiting, the console is told; past [limit], new events are dropped and counted
 *   rather than run the server out of memory.
 */
internal class Inbox(private val log: Log, private val limit: Int = LIMIT, private val warnAt: Int = WARN_AT) {
    /** Applies one event; throws [Unread] when the player's rows can't be read. On xStats' database thread. */
    fun interface Apply {
        fun apply(event: Event)
    }

    /** A player's rows couldn't be read: their events wait, in order, for the next save. */
    class Unread(val reason: String) : RuntimeException(reason, null)

    /** Increments or sets of one stat for one player, still being added to until a save takes them. Changed only under the lock. */
    private class Pending(val player: PlayerRef, val stat: String, val set: Boolean, value: Long) {
        val first: Long = value
        var value: Long = value
        var rise: Long = 0
        var closed: Boolean = false
    }

    private val lock = Any()
    private var queue = ArrayList<Any>()

    /** The last waiting increment or set of each player's stats, while more can be added to it, by player then stat. */
    private val tails = HashMap<PlayerRef, HashMap<String, Pending>>()

    @Volatile
    private var waiting = 0

    @Volatile
    private var droppedCount = 0L

    // xStats' database thread only: players whose events couldn't be applied, and theirs since, in order.
    private val held = LinkedHashMap<PlayerRef, ArrayDeque<Event>>()

    /** Events waiting, including any held back. */
    val size: Int get() = waiting

    /** Events dropped because too many were waiting. */
    val dropped: Long get() = droppedCount

    /** Players whose events are held back after an error. */
    val heldPlayers: Int get() = held.size

    fun add(event: Event) {
        when {
            event is Event.Increment -> merge(event.player, event.stat, false, event.amount)
            event is Event.Set && event.first == event.value && event.rise == 0L -> merge(event.player, event.stat, true, event.value)
            else -> locked(lock) {
                // Queued while holding the lock: changes after this wait behind it, in new slots.
                tails.remove(event.player)
                enqueue(event)
            }
        }
    }

    private fun merge(player: PlayerRef, stat: String, set: Boolean, value: Long) {
        locked(lock) {
            val stats = tails[player]
            val tail = stats?.get(stat)
            if (tail != null && tail.set == set && !tail.closed) {
                if (set) {
                    tail.rise = saturatedAdd(tail.rise, maxOf(0, value - tail.value))
                    tail.value = value
                } else {
                    tail.value = saturatedAdd(tail.value, value)
                }
                return
            }
            val next = Pending(player, stat, set, value)
            if (enqueue(next)) (stats ?: HashMap<String, Pending>().also { tails[player] = it })[stat] = next
        }
    }

    private fun saturatedAdd(a: Long, b: Long): Long {
        val sum = a + b
        return if (((a xor sum) and (b xor sum)) < 0) Long.MAX_VALUE else sum
    }

    // Under the lock.
    private fun enqueue(slot: Any): Boolean {
        if (waiting >= limit) {
            if (droppedCount++ == 0L) {
                log.error("$limit stat changes are waiting to be saved, so new ones are being dropped. Check the database; /xstats status shows how many were missed.")
            }
            return false
        }
        queue.add(slot)
        waiting++
        if (waiting == warnAt) log.warn("$warnAt stat changes are waiting to be saved: the database is slow or can't be reached.")
        return true
    }

    /** Takes everything waiting, closing each pending sum to further adds. */
    private fun takeAll(): List<Event> = locked(lock) {
        val taken = queue
        queue = ArrayList()
        tails.clear()
        val events = ArrayList<Event>(taken.size)
        for (slot in taken) {
            events += when (slot) {
                is Pending -> {
                    slot.closed = true
                    if (slot.set) Event.Set(slot.player, slot.stat, slot.first, slot.value, slot.rise) else Event.Increment(slot.player, slot.stat, slot.value)
                }
                else -> slot as Event
            }
        }
        events
    }

    private fun done(count: Int) {
        if (count == 0) return
        locked(lock) { waiting -= count }
    }

    /**
     * Applies every waiting event, those held back from the last save first. On xStats' database
     * thread. Returns the first error, or `null` if every event was applied.
     */
    fun drain(apply: Apply): String? {
        var failed: String? = null
        var applied = 0
        val players = held.entries.iterator()
        while (players.hasNext()) {
            val waitingHere = players.next().value
            try {
                while (waitingHere.isNotEmpty()) {
                    apply.apply(waitingHere.first())
                    waitingHere.removeFirst()
                    applied++
                }
                players.remove()
            } catch (e: Unread) {
                if (failed == null) failed = e.reason
            }
        }
        for (event in takeAll()) {
            val behind = held[event.player]
            if (behind != null) {
                // Behind one of theirs that couldn't be applied: after it, never before.
                behind.addLast(event)
                continue
            }
            try {
                apply.apply(event)
                applied++
            } catch (e: Unread) {
                held.getOrPut(event.player) { ArrayDeque() }.addLast(event)
                if (failed == null) failed = e.reason
            }
        }
        done(applied)
        return failed
    }

    companion object {
        const val WARN_AT = 100_000

        /** A few hundred MB at most: readings carry a number per stat. */
        const val LIMIT = 1_000_000
    }
}
