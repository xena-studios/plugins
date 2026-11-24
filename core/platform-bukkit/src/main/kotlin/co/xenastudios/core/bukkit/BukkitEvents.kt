package co.xenastudios.core.bukkit

import co.xenastudios.core.api.event.Event
import co.xenastudios.core.api.event.EventPriority
import co.xenastudios.core.api.event.EventType
import co.xenastudios.core.api.event.Subscription
import co.xenastudios.core.common.event.SimpleEventBus
import org.bukkit.Server
import org.bukkit.event.HandlerList
import co.xenastudios.core.api.event.Cancellable as CoreCancellable
import org.bukkit.event.Cancellable as BukkitCancellable
import org.bukkit.event.Event as BukkitEvent

/**
 * Fires a plugin's events as Bukkit events too, so Skript and other plugins listening the
 * Bukkit way see them (ROADMAP.md, Developer APIs): [bridge] subscribes to one event type on the
 * plugin's bus, at [EventPriority.NORMAL] among its listeners, and calls a Bukkit event made
 * from each. A cancellable event's Bukkit side reads and sets the same flag, so a Bukkit
 * listener's cancel is what the bus's later listeners see (DECISIONS.md, "The platform layer").
 *
 * The event is fired on the thread it was fired on, as an async Bukkit event off the server's
 * threads (Bukkit refuses a sync one there). An event no Bukkit plugin listens to isn't made.
 */
public class BukkitEvents internal constructor(private val server: Server) {

    private val bridges = ArrayList<Subscription>()

    /** Fires every [type] event on [bus] as a [XenaEvent] (a [XenaCancellableEvent] if it's cancellable). */
    public fun <E : Event> bridge(bus: SimpleEventBus, type: EventType<E>): Subscription =
        track(
            bus.bridge(type, EventPriority.NORMAL) { event ->
                // Nothing to build or call when no Bukkit plugin listens.
                if (XenaEvent.getHandlerList().registeredListeners.isNotEmpty()) server.pluginManager.callEvent(wrap(type, event))
            },
        )

    /**
     * Fires every [type] event on [bus] as the Bukkit event [factory] makes: for an API with
     * its own Bukkit event classes, which Skript can name.
     */
    public fun <E : Event> bridge(bus: SimpleEventBus, type: EventType<E>, factory: Factory<E>): Subscription =
        track(bus.bridge(type, EventPriority.NORMAL) { event -> server.pluginManager.callEvent(factory.wrap(event, !server.isPrimaryThread)) })

    /** Makes the Bukkit event for one of ours. */
    public fun interface Factory<E : Event> {
        /** The Bukkit event for [event]; [async] is whether it's being fired off the server's threads, for Bukkit's constructor. */
        public fun wrap(event: E, async: Boolean): BukkitEvent
    }

    /** Stops every bridge, as the plugin is disabled. */
    internal fun close() {
        synchronized(bridges) {
            bridges.forEach { it.cancel() }
            bridges.clear()
        }
    }

    private fun track(subscription: Subscription): Subscription = subscription.also { synchronized(bridges) { bridges += it } }

    private fun wrap(type: EventType<*>, event: Event): XenaEvent {
        val async = !server.isPrimaryThread
        return if (event is CoreCancellable) XenaCancellableEvent(type.name, event, async) else XenaEvent(type.name, event, async)
    }
}

/**
 * One of a Xena plugin's events, as Bukkit sees it: [type] names it (`xmoney:balance_changed`)
 * and [event] is the event itself, with its API's getters. Every Xena plugin's events share
 * this class, so a Bukkit listener checks [type].
 */
public open class XenaEvent(
    /** The event's type, `<plugin>:<event>`. */
    public val type: String,
    /** The plugin's own event. */
    public val event: Event,
    async: Boolean,
) : BukkitEvent(async) {

    override fun getHandlers(): HandlerList = HANDLERS

    override fun toString(): String = "XenaEvent($type)"

    public companion object {
        // One list for this class and XenaCancellableEvent, so a listener of this one hears both.
        private val HANDLERS = HandlerList()

        /** Bukkit's listeners of these events. */
        @JvmStatic
        public fun getHandlerList(): HandlerList = HANDLERS
    }
}

/** A [XenaEvent] that can be cancelled: cancelling it here cancels the plugin's event. */
public class XenaCancellableEvent(type: String, event: CoreCancellable, async: Boolean) : XenaEvent(type, event, async), BukkitCancellable {
    private val cancellable = event

    override fun isCancelled(): Boolean = cancellable.isCancelled

    override fun setCancelled(cancel: Boolean) {
        cancellable.isCancelled = cancel
    }
}
