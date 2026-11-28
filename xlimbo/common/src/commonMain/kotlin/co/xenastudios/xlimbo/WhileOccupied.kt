package co.xenastudios.xlimbo

import co.xenastudios.core.common.scheduler.Scheduler
import co.xenastudios.core.common.scheduler.Task
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.scheduler.every
import kotlin.time.Duration

/**
 * Repeating work on the server thread that runs only while someone is in limbo: the action bar,
 * the return queue, the entity sweep. [wake] starts it (an arrival, or the module turning on with
 * players already here; [started] runs then), and it stops itself on the first run that finds
 * limbo empty, so an empty limbo schedules nothing at all (the family's rule: no work at idle).
 * Server thread only; xLimbo is Paper only, so that's one thread.
 */
internal class WhileOccupied(
    private val scheduler: Scheduler,
    private val period: Duration,
    private val occupied: () -> Boolean,
    private val started: () -> Unit,
    private val action: () -> Unit,
) {
    private var task: Task? = null

    /** Whether it's scheduled now. */
    val running: Boolean get() = task != null

    /** Starts it if anyone is in limbo and it isn't running already. */
    fun wake() {
        if (task != null || !occupied()) return
        started()
        task = scheduler.every(Where.Global, period) { self ->
            if (occupied()) {
                action()
            } else {
                self.cancel()
                task = null
            }
        }
    }

    /** Stops it; the module turning off. */
    fun stop() {
        task?.cancel()
        task = null
    }
}
