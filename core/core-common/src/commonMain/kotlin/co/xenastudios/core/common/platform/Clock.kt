package co.xenastudios.core.common.platform

import kotlin.time.Duration
import kotlin.time.Instant

/**
 * The time, as the platform gives it. Shared code reads the time only from here, never from
 * the stdlib: Pumpkin's plugins can't reach the system clock (M0), and the fake platform's
 * clock moves only when a test says, so tests about time are exact.
 */
public interface Clock {
    /** The current time, for what's saved and shown: when a ban ends, when a player was last seen. */
    public fun now(): Instant

    /**
     * Time since some fixed point, which never jumps when the system clock is set: for
     * measuring how long something took, and cooldowns. Only differences mean anything.
     */
    public fun elapsed(): Duration
}

/**
 * The stdlib's clocks, for platforms that can use them: every JVM platform. Pumpkin can't
 * (M0), so its adapter brings its own.
 */
public object SystemClock : Clock {
    private val start = kotlin.time.TimeSource.Monotonic.markNow()

    override fun now(): Instant = kotlin.time.Clock.System.now()

    override fun elapsed(): Duration = start.elapsedNow()
}
