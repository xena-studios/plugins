package co.xenastudios.core.api.event

/**
 * Something a plugin tells the others happened, or is about to happen, on its own [EventBus].
 * Each kind of event has one [EventType], which listeners subscribe to.
 *
 * An event is delivered on the thread it's fired on, to every listener in turn: on Folia, the
 * thread that owns what it's about. A listener that needs another thread hops there itself.
 */
public interface Event

/**
 * An event that listeners can stop: a pre-event, fired before the change it describes. If
 * [isCancelled] is `true` once every listener has seen it, the change doesn't happen. A
 * listener can also set it back to `false`.
 */
public interface Cancellable : Event {
    /** Whether the change is off. From Java, `isCancelled()` and `setCancelled(boolean)`. */
    public var isCancelled: Boolean
}

/** A [Cancellable] that starts out not cancelled: the usual base for a pre-event. */
public abstract class CancellableEvent : Cancellable {
    override var isCancelled: Boolean = false
}
