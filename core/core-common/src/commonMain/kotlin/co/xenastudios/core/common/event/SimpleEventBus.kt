package co.xenastudios.core.common.event

import co.xenastudios.core.api.event.Cancellable
import co.xenastudios.core.api.event.Event
import co.xenastudios.core.api.event.EventBus
import co.xenastudios.core.api.event.EventListener
import co.xenastudios.core.api.event.EventPriority
import co.xenastudios.core.api.event.EventType
import co.xenastudios.core.api.event.Subscription
import co.xenastudios.core.common.isFatal
import co.xenastudios.core.common.locked
import co.xenastudios.core.common.platform.Log
import kotlin.concurrent.Volatile

/**
 * A plugin's [EventBus]: the one its API hands out, and the only place its events are fired.
 *
 * Firing reads a snapshot of the listeners without locking or allocating, so an event no one
 * listens to costs a map lookup; subscribing copies the snapshot, which only happens at setup.
 * A listener that throws is logged to [log] and the rest still hear the event.
 *
 * A platform whose own events other plugins listen to ([co.xenastudios.core.common.platform.Capability.NATIVE_EVENTS])
 * [bridge]s each event type to them.
 */
public class SimpleEventBus(private val log: Log) : EventBus {

    private inner class Entry(
        val type: EventType<*>,
        val priority: EventPriority,
        val ignoreCancelled: Boolean,
        val listener: EventListener<Event>,
        val order: Long,
    ) : Subscription {
        @Volatile
        var active: Boolean = true
        override val isActive: Boolean get() = active

        override fun cancel() {
            locked(lock) {
                if (active) {
                    active = false
                    replace(type) { entries -> entries.filter { it !== this } }
                }
            }
        }
    }

    private val lock = Any()

    // Replaced whole, never changed in place, so fire() can read it without the lock.
    @Volatile
    private var listeners: Map<EventType<*>, Array<Entry>> = emptyMap()

    private var nextOrder = 0L

    override fun <E : Event> subscribe(
        type: EventType<E>,
        priority: EventPriority,
        ignoreCancelled: Boolean,
        listener: EventListener<in E>,
    ): Subscription = locked(lock) {
        @Suppress("UNCHECKED_CAST")
        val entry = Entry(type, priority, ignoreCancelled, listener as EventListener<Event>, nextOrder++)
        replace(type) { entries -> (entries + entry).sortedWith(compareBy({ it.priority.ordinal }, { it.order })) }
        entry
    }

    // Holding the lock.
    private fun replace(type: EventType<*>, change: (List<Entry>) -> List<Entry>) {
        val changed = change(listeners[type]?.asList() ?: emptyList())
        listeners = if (changed.isEmpty()) listeners - type else listeners + (type to changed.toTypedArray())
    }

    /**
     * Also fires each event of [type] the platform's own way, by calling [forward], at
     * [priority] among this bus's listeners: Bukkit's adapter wraps the event in a Bukkit
     * event and calls it, so Skript and Bukkit listeners see it. [forward] hears cancelled
     * events too, and whatever the platform's listeners do to [Cancellable.isCancelled] is
     * what the listeners after it see, so the bridge passes cancellation both ways.
     */
    public fun <E : Event> bridge(
        type: EventType<E>,
        priority: EventPriority = EventPriority.NORMAL,
        forward: (E) -> Unit,
    ): Subscription = subscribe(type, priority, false, EventListener { forward(it) })

    /**
     * Whether anything listens to [type], so a plugin can skip building an event no one
     * hears. A bridged type always has a listener.
     */
    public fun hasListeners(type: EventType<*>): Boolean = type in listeners

    /**
     * Tells every listener of [type] about [event], in order of priority, on this thread, and
     * returns it: for a [Cancellable] pre-event, check [Cancellable.isCancelled] after.
     */
    public fun <E : Event> fire(type: EventType<E>, event: E): E {
        val entries = listeners[type] ?: return event
        for (entry in entries) {
            if (!entry.active) continue
            if (entry.ignoreCancelled && event is Cancellable && event.isCancelled) continue
            try {
                entry.listener.on(event)
            } catch (e: Throwable) {
                if (isFatal(e)) throw e
                log.error("A listener of ${type.name} failed; the others still heard it", e)
            }
        }
        return event
    }
}
