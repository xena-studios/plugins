package co.xenastudios.core.benchmarks

import co.xenastudios.core.api.event.CancellableEvent
import co.xenastudios.core.api.event.Event
import co.xenastudios.core.api.event.EventPriority
import co.xenastudios.core.api.event.EventType
import co.xenastudios.core.common.event.SimpleEventBus
import co.xenastudios.core.testing.RecordingLog
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.infra.Blackhole

internal class Moved(val distance: Int) : Event

internal class Paying(val amount: Long) : CancellableEvent()

/**
 * The core's event bus, which a plugin fires its own events on, some on every block change or
 * move: firing to no one (which a platform checks first, to skip making the event), to three
 * listeners, and a cancellable pre-event that one listener cancels.
 */
@State(Scope.Benchmark)
public open class EventBusBenchmark {
    private val bus = SimpleEventBus(RecordingLog())
    private val unheard: EventType<Moved> = EventType.of("bench:unheard")
    private val moved: EventType<Moved> = EventType.of("bench:moved")
    private val paying: EventType<Paying> = EventType.of("bench:paying")
    private val event = Moved(3)
    private var total = 0L

    init {
        repeat(3) { bus.subscribe(moved) { total += it.distance } }
        bus.subscribe(paying, EventPriority.LOW, false) { if (it.amount > 100) it.isCancelled = true }
        bus.subscribe(paying, EventPriority.MONITOR, true) { total += it.amount }
    }

    @Benchmark
    public fun noListeners(): Boolean = bus.hasListeners(unheard)

    @Benchmark
    public fun threeListeners(blackhole: Blackhole) {
        blackhole.consume(bus.fire(moved, event))
    }

    @Benchmark
    public fun cancellable(blackhole: Blackhole) {
        blackhole.consume(bus.fire(paying, Paying(250)).isCancelled)
    }
}
