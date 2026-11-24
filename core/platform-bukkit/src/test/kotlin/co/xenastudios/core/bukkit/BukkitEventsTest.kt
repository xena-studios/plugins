package co.xenastudios.core.bukkit

import co.xenastudios.core.api.event.CancellableEvent
import co.xenastudios.core.api.event.Event
import co.xenastudios.core.api.event.EventPriority
import co.xenastudios.core.api.event.EventType
import co.xenastudios.core.common.event.SimpleEventBus
import co.xenastudios.core.testing.RecordingLog
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.plugin.EventExecutor
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.bukkit.event.Event as BukkitEvent
import org.bukkit.event.EventPriority as BukkitPriority

class BukkitEventsTest {

    private class Paid(val amount: Int) : CancellableEvent() {
        companion object {
            val TYPE: EventType<Paid> = EventType.of("xsample:paid")
        }
    }

    private class Joined : Event {
        companion object {
            val TYPE: EventType<Joined> = EventType.of("xsample:joined")
        }
    }

    private val log = RecordingLog()
    private val bus = SimpleEventBus(log)
    private lateinit var events: BukkitEvents
    private val heard = mutableListOf<BukkitEvent>()
    private val listener = object : Listener {}

    @BeforeTest
    fun setUp() {
        FakeServer.reset()
        events = BukkitEvents(FakeServer.server)
    }

    @AfterTest
    fun tearDown() {
        HandlerList.unregisterAll(listener)
    }

    private fun listen(cancel: Boolean = false) {
        val executor = EventExecutor { _, event ->
            heard += event
            if (cancel && event is XenaCancellableEvent) event.isCancelled = true
        }
        FakeServer.plugins.registerEvent(XenaEvent::class.java, listener, BukkitPriority.NORMAL, executor, FakeServer.plugin())
    }

    @Test
    fun aBukkitListenersCancelIsWhatLaterListenersOnTheBusSee() {
        events.bridge(bus, Paid.TYPE)
        listen(cancel = true)
        var seen: Boolean? = null
        bus.subscribe(Paid.TYPE, EventPriority.HIGH) { seen = it.isCancelled }
        val event = bus.fire(Paid.TYPE, Paid(5))
        assertTrue(event.isCancelled)
        assertEquals(true, seen)
        val bukkit = assertIs<XenaCancellableEvent>(heard.single())
        assertEquals("xsample:paid", bukkit.type)
        assertEquals(5, (bukkit.event as Paid).amount)
        assertFalse(bukkit.isAsynchronous)
    }

    @Test
    fun aCancelBeforeTheBridgeIsWhatBukkitSees() {
        events.bridge(bus, Paid.TYPE)
        listen()
        bus.subscribe(Paid.TYPE, EventPriority.LOW) { it.isCancelled = true }
        bus.fire(Paid.TYPE, Paid(1))
        assertTrue((heard.single() as XenaCancellableEvent).isCancelled)
    }

    @Test
    fun anEventThatCantBeCancelledIsAPlainXenaEvent() {
        events.bridge(bus, Joined.TYPE)
        listen()
        bus.fire(Joined.TYPE, Joined())
        assertEquals<Class<*>>(XenaEvent::class.java, heard.single().javaClass)
    }

    @Test
    fun offTheServersThreadsItsAnAsyncBukkitEvent() {
        events.bridge(bus, Paid.TYPE)
        listen()
        thread { bus.fire(Paid.TYPE, Paid(2)) }.join()
        assertTrue(heard.single().isAsynchronous)
    }

    @Test
    fun withNoBukkitListenerNothingIsMadeAndClosingStopsTheBridge() {
        val bridge = events.bridge(bus, Paid.TYPE)
        bus.fire(Paid.TYPE, Paid(3))
        assertEquals(emptyList<BukkitEvent>(), heard)
        listen()
        events.close()
        assertFalse(bridge.isActive)
        bus.fire(Paid.TYPE, Paid(4))
        assertEquals(emptyList<BukkitEvent>(), heard)
    }

    @Test
    fun anApiCanMakeItsOwnBukkitEvents() {
        class Typed(val paid: Paid, async: Boolean) : XenaEvent(Paid.TYPE.name, paid, async)
        events.bridge(bus, Paid.TYPE) { event, async -> Typed(event, async) }
        listen()
        bus.fire(Paid.TYPE, Paid(6))
        assertEquals(6, assertIs<Typed>(heard.single()).paid.amount)
    }
}
