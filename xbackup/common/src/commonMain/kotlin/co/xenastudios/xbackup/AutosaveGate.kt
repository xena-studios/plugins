package co.xenastudios.xbackup

import co.xenastudios.core.common.locked

/**
 * Which worlds a backup turned autosave off on, and whether it still wants them off: the heart of
 * every platform's world pause, kept apart from the server so the races can be tested. 1.x's.
 *
 * The pause is asked for on one thread and carried out on another (the main thread on Paper), and
 * the end can come from a third (the watchdog, or disable). So: the worlds are only turned off if
 * the pause is still wanted, checked under the same lock that ends it; the worlds actually turned
 * off are remembered, and the end always turns exactly those back on, however many times it's
 * called and whatever order things happen in. One lock for all gates, so one backup's end can't
 * land in the middle of the next one's start.
 *
 * Worlds whose owner had already turned autosave off (an arena that resets, say) are left alone:
 * not flushed, and not turned back on.
 */
public class AutosaveGate<W>(private val worlds: Switch<W>) {
    /** Reads and sets a world's autosave. Setting it is a plain field write, safe from any thread. */
    public interface Switch<W> {
        public fun isOn(world: W): Boolean

        public fun set(world: W, on: Boolean)
    }

    private var wanted = false
    private val off = LinkedHashSet<W>()

    /** A pause is wanted from now until [close]. */
    public fun open() {
        locked(LOCK) { wanted = true }
    }

    /**
     * Turns autosave off on each of [candidates] that has it on, if the pause is still wanted.
     *
     * @return the worlds this pause holds off, to flush; `null` if the pause already ended
     */
    public fun turnOff(candidates: Iterable<W>): List<W>? = locked(LOCK) {
        if (!wanted) return@locked null
        for (world in candidates) {
            if (worlds.isOn(world)) {
                worlds.set(world, false)
                off += world
            }
        }
        off.toList()
    }

    /** Whether the pause is still wanted. */
    public val isWanted: Boolean get() = locked(LOCK) { wanted }

    /** Ends the pause: autosave back on for every world it turned off. Any thread, any number of times. */
    public fun close() {
        locked(LOCK) {
            wanted = false
            for (world in off) {
                try {
                    worlds.set(world, true)
                } catch (e: RuntimeException) {
                    // An unloaded world: nothing left to save.
                }
            }
            off.clear()
        }
    }

    /** The worlds held off right now. */
    public fun held(): List<W> = locked(LOCK) { off.toList() }

    private companion object {
        val LOCK = Any()
    }
}
