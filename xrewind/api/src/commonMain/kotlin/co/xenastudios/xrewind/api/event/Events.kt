package co.xenastudios.xrewind.api.event

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.event.CancellableEvent
import co.xenastudios.core.api.event.Event
import co.xenastudios.core.api.event.EventType
import co.xenastudios.xrewind.api.Plan
import co.xenastudios.xrewind.api.Query
import co.xenastudios.xrewind.api.RollbackResult
import kotlin.jvm.JvmField

/**
 * Someone looked the history up: a staff member's `/xrewind lookup`, `page`, `near` or inspector
 * click, or another plugin's [co.xenastudios.xrewind.api.XRewindApi.lookup]. For an audit log of
 * who looked at what; it can't be stopped. Fired off the server's threads, before the database is
 * read, and only when something listens. On Bukkit it's also
 * `co.xenastudios.xrewind.api.bukkit.BukkitLookupEvent`.
 *
 * @since 2.0.0
 */
public class LookupEvent(
    /** The player who looked, or `null` for the console or a plugin. */
    public val player: PlayerRef?,
    /** Their name, `CONSOLE`, or `null` for a plugin. */
    public val name: String?,
    /** What they looked for, as it runs. */
    public val query: Query,
    /** Which page is shown, from 1. */
    public val page: Int,
) : Event {
    public companion object {
        @JvmField
        public val TYPE: EventType<LookupEvent> = EventType.of("xrewind:lookup")
    }
}

/**
 * A rollback, restore or undo is about to start: cancel it to stop it (the staff member who ran
 * it is told another plugin stopped it). On the global thread, only when something listens. On
 * Bukkit also `BukkitRollbackEvent`.
 *
 * @since 2.0.0
 */
public class RollbackEvent(
    /** What will change. It can't be applied from here. */
    public val plan: Plan,
    /** The player who ran the command, or `null` for the console or a plugin. */
    public val player: PlayerRef?,
    /** Their name, `CONSOLE`, or `null` for a plugin. */
    public val name: String?,
) : CancellableEvent() {
    public companion object {
        @JvmField
        public val TYPE: EventType<RollbackEvent> = EventType.of("xrewind:rollback")
    }
}

/**
 * A rollback, restore or undo finished. On the global thread, only when something listens. On
 * Bukkit also `BukkitRollbackFinishedEvent`.
 *
 * @since 2.0.0
 */
public class RollbackFinishedEvent(
    /** What was applied. */
    public val plan: Plan,
    /** What it did. */
    public val result: RollbackResult,
    /** The player who ran the command, or `null` for the console or a plugin. */
    public val player: PlayerRef?,
    /** Their name, `CONSOLE`, or `null` for a plugin. */
    public val name: String?,
) : Event {
    public companion object {
        @JvmField
        public val TYPE: EventType<RollbackFinishedEvent> = EventType.of("xrewind:rollback_finished")
    }
}
