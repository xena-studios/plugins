package co.xenastudios.core.testing

import co.xenastudios.core.common.platform.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/** A [Clock] that reads the [scheduler]'s virtual time: it starts at [start] and moves only when the scheduler does. */
public class FakeClock(
    private val scheduler: VirtualScheduler,
    public val start: Instant = Instant.parse("2026-01-01T00:00:00Z"),
) : Clock {
    override fun now(): Instant = start + scheduler.time

    override fun elapsed(): Duration = scheduler.time
}
