package co.xenastudios.core.common.event

import co.xenastudios.core.api.event.CancellableEvent
import co.xenastudios.core.api.event.Event
import co.xenastudios.core.api.event.EventPriority
import co.xenastudios.core.api.event.EventType
import co.xenastudios.core.testing.RecordingLog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SimpleEventBusTest {

    private class Withdraw(val amount: Long) : CancellableEvent()

    private class Joined(val name: String) : Event

    private val withdraw = EventType.of<Withdraw>("test:withdraw")
    private val joined = EventType.of<Joined>("test:joined")
    private val log = RecordingLog()
    private val bus = SimpleEventBus(log)

    @Test
    fun listenersHearInOrderOfPriorityThenOfSubscribing() {
        val heard = mutableListOf<String>()
        bus.subscribe(joined, EventPriority.MONITOR) { heard += "monitor" }
        bus.subscribe(joined) { heard += "normal 1" }
        bus.subscribe(joined, EventPriority.LOWEST) { heard += "lowest" }
        bus.subscribe(joined) { heard += "normal 2" }
        bus.subscribe(joined, EventPriority.HIGH) { heard += "high ${it.name}" }
        bus.fire(joined, Joined("Steve"))
        assertEquals(listOf("lowest", "normal 1", "normal 2", "high Steve", "monitor"), heard)
    }

    @Test
    fun aPreEventCanBeCancelledAndUncancelled() {
        val heard = mutableListOf<String>()
        bus.subscribe(withdraw, EventPriority.LOW) { if (it.amount > 100) it.isCancelled = true }
        bus.subscribe(withdraw, EventPriority.NORMAL, true) { heard += "normal saw ${it.amount}" }
        bus.subscribe(withdraw, EventPriority.HIGH) { heard += "high saw cancelled=${it.isCancelled}" }
        assertFalse(bus.fire(withdraw, Withdraw(50)).isCancelled)
        assertTrue(bus.fire(withdraw, Withdraw(500)).isCancelled)
        assertEquals(listOf("normal saw 50", "high saw cancelled=false", "high saw cancelled=true"), heard)

        bus.subscribe(withdraw, EventPriority.HIGHEST) { it.isCancelled = false }
        assertFalse(bus.fire(withdraw, Withdraw(500)).isCancelled)
    }

    @Test
    fun aListenerThatThrowsIsLoggedAndTheOthersStillHear() {
        val heard = mutableListOf<String>()
        bus.subscribe(joined, EventPriority.LOW) { throw IllegalStateException("broken") }
        bus.subscribe(joined) { heard += it.name }
        bus.fire(joined, Joined("Alex"))
        assertEquals(listOf("Alex"), heard)
        assertEquals(listOf("A listener of test:joined failed; the others still heard it"), log.errors)
        assertEquals("broken", log.lines.single().cause?.message)
    }

    @Test
    fun aCancelledSubscriptionHearsNothing() {
        var heard = 0
        val subscription = bus.subscribe(joined) { heard++ }
        assertTrue(bus.hasListeners(joined))
        bus.fire(joined, Joined("Steve"))
        subscription.cancel()
        subscription.cancel()
        assertFalse(subscription.isActive)
        assertFalse(bus.hasListeners(joined))
        bus.fire(joined, Joined("Steve"))
        assertEquals(1, heard)
    }

    @Test
    fun subscribingWhileFiringTakesEffectNextTime() {
        var late = 0
        bus.subscribe(joined) { bus.subscribe(joined) { late++ } }
        bus.fire(joined, Joined("Steve"))
        assertEquals(0, late)
        bus.fire(joined, Joined("Steve"))
        assertEquals(1, late)
    }

    @Test
    fun typesAreKeptApart() {
        var heard = 0
        bus.subscribe(joined) { heard++ }
        val event = Withdraw(1)
        assertSame(event, bus.fire(withdraw, event))
        assertFalse(bus.hasListeners(withdraw))
        assertEquals(0, heard)
    }

    @Test
    fun aBridgeSeesCancelledEventsAndCanCancelForTheListenersAfterIt() {
        val native = mutableListOf<String>()
        bus.subscribe(withdraw, EventPriority.LOW) { it.isCancelled = it.amount > 100 }
        // What Bukkit's adapter does: fire a Bukkit event wrapping ours, whose listeners
        // (Skript, say) cancel it through the wrapper.
        bus.bridge(withdraw) { event ->
            native += "native saw ${event.amount} cancelled=${event.isCancelled}"
            if (event.amount == 13L) event.isCancelled = true
        }
        val after = mutableListOf<Long>()
        bus.subscribe(withdraw, EventPriority.HIGH, true) { after += it.amount }
        assertTrue(bus.fire(withdraw, Withdraw(500)).isCancelled)
        assertTrue(bus.fire(withdraw, Withdraw(13)).isCancelled)
        assertFalse(bus.fire(withdraw, Withdraw(5)).isCancelled)
        assertEquals(
            listOf("native saw 500 cancelled=true", "native saw 13 cancelled=false", "native saw 5 cancelled=false"),
            native,
        )
        assertEquals(listOf(5L), after)
    }
}
