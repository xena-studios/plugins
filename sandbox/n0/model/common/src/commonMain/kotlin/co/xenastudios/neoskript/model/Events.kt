package co.xenastudios.neoskript.model

/**
 * A kind of event scripts can hear (`on join`, `on break`), the same on every platform. Which of
 * its [values] a platform can give, and whether it can be cancelled there, is that platform's
 * [EventBinding]: what's missing is a parse error on that platform, never a null at run time.
 */
public class EventKind private constructor(
    /** Skript's name for it: `join`, `block break`. */
    public val name: String,
    /** The values triggers of it may ask for (`event-player`, `the damage`). */
    public val values: List<EventValueKey>,
) {
    override fun toString(): String = name

    public companion object {
        public val JOIN: EventKind = EventKind("join", listOf(EventValueKey.PLAYER, EventValueKey.WORLD))
        public val QUIT: EventKind = EventKind("quit", listOf(EventValueKey.PLAYER, EventValueKey.WORLD))
        public val CHAT: EventKind = EventKind("chat", listOf(EventValueKey.PLAYER, EventValueKey.MESSAGE))
        public val BLOCK_BREAK: EventKind =
            EventKind("block break", listOf(EventValueKey.PLAYER, EventValueKey.BLOCK, EventValueKey.WORLD))
        public val BLOCK_PLACE: EventKind =
            EventKind("block place", listOf(EventValueKey.PLAYER, EventValueKey.BLOCK, EventValueKey.WORLD))
        public val DAMAGE: EventKind =
            EventKind("damage", listOf(EventValueKey.VICTIM, EventValueKey.ATTACKER, EventValueKey.DAMAGE))
        public val DEATH: EventKind = EventKind("death", listOf(EventValueKey.VICTIM, EventValueKey.ATTACKER))

        public val ALL: List<EventKind> = listOf(JOIN, QUIT, CHAT, BLOCK_BREAK, BLOCK_PLACE, DAMAGE, DEATH)
    }
}

/** One value an event has: `event-player`, `event-block`, `the damage`. */
public class EventValueKey private constructor(public val name: String) {
    override fun toString(): String = name

    public companion object {
        public val PLAYER: EventValueKey = EventValueKey("player")
        public val BLOCK: EventValueKey = EventValueKey("block")
        public val WORLD: EventValueKey = EventValueKey("world")
        public val MESSAGE: EventValueKey = EventValueKey("message")
        public val VICTIM: EventValueKey = EventValueKey("victim")
        public val ATTACKER: EventValueKey = EventValueKey("attacker")
        public val DAMAGE: EventValueKey = EventValueKey("damage")
    }
}

/**
 * Reads one value of the platform's own event object. Resolved once, as a script loads
 * ([EventBinding.value]), and called directly after that: no lookup per call.
 */
public fun interface EventValue {
    public fun get(event: Any): Any?
}

/** An [EventValue] scripts can also change: `set message to ...`, `set damage to ...`. */
public interface ChangeableEventValue : EventValue {
    public fun set(event: Any, value: Any?)
}

/**
 * How one [kind] of event is heard on this platform: its own event object (Bukkit's
 * `BlockBreakEvent`; on Fabric the adapter's, made from a Fabric API callback or a core world
 * hook), the values it has and whether it can be cancelled.
 */
public interface EventBinding {
    public val kind: EventKind

    /** The getter for [key], or `null` if this platform's event has no such value (a parse error). */
    public fun value(key: EventValueKey): EventValue?

    /** Whether `cancel event` stops it here. */
    public val cancellable: Boolean

    public fun isCancelled(event: Any): Boolean

    /** Cancels it, or lets it go on; does nothing if it isn't [cancellable]. */
    public fun setCancelled(event: Any, cancelled: Boolean)

    /** What it happened to, whose thread the trigger runs on and carries on on after a wait. */
    public fun owner(event: Any): Any?

    /** Whether triggers of it run off the server's threads (Paper's chat), where nothing in the world may be touched. */
    public val async: Boolean
}

/** Where scripts' event listeners sit among other plugins'. */
public enum class Priority { LOWEST, LOW, NORMAL, HIGH, HIGHEST, MONITOR }

/** The events of this platform. */
public interface Events {
    /** How [kind] is heard here, or `null` if it can't be. */
    public fun binding(kind: EventKind): EventBinding?

    /**
     * Calls [trigger] with each [kind] event's own object, at [priority] (Skript's is `HIGH`),
     * skipping cancelled ones if [ignoreCancelled], until the returned handle is closed. A
     * script load listens to each kind once, for all its triggers.
     */
    public fun listen(kind: EventKind, priority: Priority, ignoreCancelled: Boolean, trigger: (Any) -> Unit): AutoCloseable
}
