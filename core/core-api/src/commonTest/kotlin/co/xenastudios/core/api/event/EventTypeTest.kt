package co.xenastudios.core.api.event

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class EventTypeTest {

    private class Thing : CancellableEvent()

    @Test
    fun namesArePluginColonEvent() {
        assertEquals("xmoney:balance_changed", EventType.of<Thing>("xmoney:balance_changed").name)
        assertEquals("xplots:plot/claimed", EventType.of<Thing>("xplots:plot/claimed").toString())
        for (bad in listOf("", "balance", "XMoney:changed", "xmoney:", ":changed", "x money:changed")) {
            assertFailsWith<IllegalArgumentException>(bad) { EventType.of<Thing>(bad) }
        }
    }

    @Test
    fun typesAreTheSameOnlyIfTheSameObject() {
        assertNotEquals(EventType.of<Thing>("test:thing"), EventType.of<Thing>("test:thing"))
    }

    @Test
    fun aCancellableEventStartsNotCancelled() {
        val thing = Thing()
        assertFalse(thing.isCancelled)
        thing.isCancelled = true
        assertTrue(thing.isCancelled)
    }

    @Test
    fun prioritiesRunLowestToMonitor() {
        assertEquals(
            listOf("LOWEST", "LOW", "NORMAL", "HIGH", "HIGHEST", "MONITOR"),
            EventPriority.entries.map { it.name },
        )
    }
}
