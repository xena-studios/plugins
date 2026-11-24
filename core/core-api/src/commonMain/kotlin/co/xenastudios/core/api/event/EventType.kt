package co.xenastudios.core.api.event

import kotlin.jvm.JvmStatic

/**
 * The key listeners subscribe to for one kind of event, declared once beside it:
 *
 * ```kotlin
 * class BalanceChanged(...) : Event {
 *     companion object {
 *         @JvmField val TYPE: EventType<BalanceChanged> = EventType.of("xmoney:balance_changed")
 *     }
 * }
 * ```
 *
 * A key rather than a class, so it works the same on every platform (Wasm has no reflection,
 * and Java has no `KClass`), delivery never depends on class hierarchies, and an event has a
 * stable [name] for logs, the docs and, on Pumpkin, the bytes plugins send each other.
 *
 * Two types are the same only if they're the same object; the [name] is for people.
 */
public class EventType<E : Event> private constructor(
    /** A namespaced name, `<plugin>:<event>` in lower case, such as `xmoney:balance_changed`. */
    public val name: String,
) {
    override fun toString(): String = name

    public companion object {
        private val NAME = Regex("[a-z0-9_]+:[a-z0-9_/.]+")

        /** A new event type called [name], which must look like `<plugin>:<event>`. */
        @JvmStatic
        public fun <E : Event> of(name: String): EventType<E> {
            require(NAME.matches(name)) { "An event type's name looks like plugin:event_name, not \"$name\"" }
            return EventType(name)
        }
    }
}
