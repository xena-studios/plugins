package co.xenastudios.core.bukkit.modern

import co.xenastudios.core.common.scheduler.TICK
import kotlin.time.Duration

/**
 * Server ticks for a [Duration]: rounded up, and at least one, since work on a server thread
 * runs on the next tick at the soonest. Every server's scheduler uses it, Bukkit's and Folia's:
 * it's here because main can see this source set, and not the other way round.
 */
internal object Ticks {
    private val NANOS = TICK.inWholeNanoseconds

    fun of(duration: Duration): Long {
        val nanos = duration.inWholeNanoseconds
        // Duration.INFINITE and the like: as long as Bukkit can wait, not an overflow.
        if (nanos > Long.MAX_VALUE - NANOS) return Long.MAX_VALUE / NANOS
        return maxOf(1L, (nanos + NANOS - 1) / NANOS)
    }
}
