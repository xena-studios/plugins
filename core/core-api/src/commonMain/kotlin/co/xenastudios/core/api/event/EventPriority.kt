package co.xenastudios.core.api.event

/**
 * When a listener hears an event, among the others: [LOWEST] first, [MONITOR] last. The same
 * order as Bukkit's, so a listener moves between the two without surprises. Listeners of one
 * priority hear it in the order they subscribed.
 */
public enum class EventPriority {
    /** First: for listeners that set things up for the others. */
    LOWEST,

    /** Before most. */
    LOW,

    /** Where most listeners belong, and the default. */
    NORMAL,

    /** After most. */
    HIGH,

    /** Last of the listeners that may change the event: they have the final say. */
    HIGHEST,

    /** After the outcome is decided, to watch it. A monitor listener changes nothing. */
    MONITOR,
}
