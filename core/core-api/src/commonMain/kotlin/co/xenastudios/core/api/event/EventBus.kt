package co.xenastudios.core.api.event

/**
 * Where a plugin's events are heard. Each plugin's API hands out its own, such as
 * `XMoney.api().events()`.
 *
 * A listener that throws is logged and skipped; the others still hear the event. Listeners
 * are heard on the thread the event is fired on (see [Event]).
 *
 * Only the plugin that owns a bus fires events on it. On Bukkit, each event is also fired as
 * a Bukkit event, at [EventPriority.NORMAL] among this bus's listeners, so Skript and other
 * plugins listening the Bukkit way see it, and can cancel it, too.
 */
public interface EventBus {

    /** [listener] hears every event of [type], at [EventPriority.NORMAL]. */
    public fun <E : Event> subscribe(type: EventType<E>, listener: EventListener<in E>): Subscription =
        subscribe(type, EventPriority.NORMAL, false, listener)

    /** [listener] hears every event of [type], at [priority]. */
    public fun <E : Event> subscribe(
        type: EventType<E>,
        priority: EventPriority,
        listener: EventListener<in E>,
    ): Subscription = subscribe(type, priority, false, listener)

    /**
     * [listener] hears every event of [type], at [priority]; if [ignoreCancelled], not those
     * a listener before it has cancelled. Until [Subscription.cancel] is called: a plugin
     * cancels its subscriptions when it's disabled.
     */
    public fun <E : Event> subscribe(
        type: EventType<E>,
        priority: EventPriority,
        ignoreCancelled: Boolean,
        listener: EventListener<in E>,
    ): Subscription
}

/** Hears events of one type. A lambda in Kotlin and Java alike. */
public fun interface EventListener<E : Event> {
    /** Called with each [event], on the thread it was fired on. */
    public fun on(event: E)
}

/** A listener's place on a bus, until it's [cancel]led. */
public interface Subscription {
    /** Whether the listener still hears events. */
    public val isActive: Boolean

    /** Stops the listener hearing events. Calling it again does nothing. */
    public fun cancel()
}
