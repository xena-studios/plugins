package co.xenastudios.core.common.scheduler

import co.xenastudios.core.api.Location
import co.xenastudios.core.api.PlayerRef
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

/**
 * Runs every task of a plugin, on the thread that owns what it touches (ROADMAP.md, Fast).
 *
 * The model is Folia's regions, whatever the platform ([co.xenastudios.core.common.platform.Threading]):
 * work for a place runs on the thread that owns that place, work for an entity on the thread
 * that owns the entity, wherever it walks, global work on the global thread and async work
 * off them all. On Bukkit and Fabric the first three are the main thread; on Pumpkin all four
 * are its tick callbacks. Code written for Folia is right everywhere, so shared code always
 * says [Where] its work belongs.
 *
 * Delays and periods are [Duration]s. Work on a server thread runs on ticks, so its delays
 * are rounded up to whole ticks of 50 ms; async work keeps them exact. A delay of zero is the
 * next tick (or, async, as soon as a thread is free): [run] never runs anything right away.
 * [now] does, when it can.
 *
 * Global work runs even while a Paper server is paused because it's empty: it uses Bukkit's
 * scheduler there, not the global region scheduler, which pauses with the worlds (DECISIONS.md,
 * "Global work survives an empty server's pause").
 */
public interface Scheduler {

    /**
     * Runs [action] [where] it belongs, after [delay], then every [period] if there is one,
     * until the [Task] is cancelled. [action] is given its own task, so it can cancel itself.
     *
     * For [Where.On], if the entity is gone (a player who logged out), the task is cancelled
     * and [retired] runs instead, once, on whichever thread noticed: it must not touch the
     * entity. A period must be positive.
     */
    public fun run(
        where: Where,
        delay: Duration = Duration.ZERO,
        period: Duration? = null,
        retired: (() -> Unit)? = null,
        action: (Task) -> Unit,
    ): Task

    /**
     * Whether the current thread owns what [where] names, so it may touch it now: on Folia,
     * the region's or the entity's thread, or the global thread for [Where.Global]; on
     * Bukkit, the main thread for all three; on Pumpkin, always. [Where.Async] is owned by
     * async tasks.
     */
    public fun owns(where: Where): Boolean
}

/** Where a task runs: on the thread that owns a place, an entity, global work, or none of them. */
public sealed class Where {
    /** The global thread: work that belongs to no place or entity, such as a reload or a broadcast. */
    public data object Global : Where()

    /** The thread that owns [location]'s region: for changing blocks there. */
    public class At(public val location: Location) : Where() {
        override fun toString(): String = "At($location)"
    }

    /**
     * The thread that owns the entity with this [entity] UUID, wherever it goes: for anything
     * that touches a player or a mob.
     */
    public class On(public val entity: Uuid) : Where() {
        override fun toString(): String = "On($entity)"
    }

    /** Off the server's threads: for the disk and the network. Never touches the world. */
    public data object Async : Where()

    public companion object {
        /** The thread that owns this [player]. */
        public fun on(player: PlayerRef): Where = On(player.uuid)
    }
}

/** A scheduled task: one type for every kind, wherever and however often it runs. */
public interface Task {
    /** Whether it was cancelled, or its entity went away. A task that ran once and ended isn't cancelled. */
    public val isCancelled: Boolean

    /** Stops it running again; one that's running finishes. Calling it again does nothing. */
    public fun cancel()
}

/** Runs [action] on the global thread's next tick. */
public fun Scheduler.global(action: (Task) -> Unit): Task = run(Where.Global, action = action)

/** Runs [action] on the next tick of the thread that owns [location]. */
public fun Scheduler.at(location: Location, action: (Task) -> Unit): Task = run(Where.At(location), action = action)

/**
 * Runs [action] on the next tick of the thread that owns the [entity], or [retired] instead if
 * it's gone by then.
 */
public fun Scheduler.on(entity: Uuid, retired: (() -> Unit)? = null, action: (Task) -> Unit): Task =
    run(Where.On(entity), retired = retired, action = action)

/** Runs [action] off the server's threads, as soon as one is free. */
public fun Scheduler.async(action: (Task) -> Unit): Task = run(Where.Async, action = action)

/** Runs [action] [where] it belongs, once, after [delay]. */
public fun Scheduler.later(where: Where, delay: Duration, action: (Task) -> Unit): Task =
    run(where, delay = delay, action = action)

/** Runs [action] [where] it belongs, every [period], starting after [delay] (one period, unless said). */
public fun Scheduler.every(where: Where, period: Duration, delay: Duration = period, action: (Task) -> Unit): Task =
    run(where, delay = delay, period = period, action = action)

/**
 * Runs [action] right away if this thread owns [where], or on its next tick if not: for
 * finishing work that may already be in the right place. Returns `null` when it ran here.
 */
public fun Scheduler.now(where: Where, retired: (() -> Unit)? = null, action: () -> Unit): Task? {
    if (owns(where)) {
        action()
        return null
    }
    return run(where, retired = retired) { action() }
}

/** The length of a server tick: work on a server thread runs on ticks, so its delays are whole ticks. */
public val TICK: Duration = 50.milliseconds
