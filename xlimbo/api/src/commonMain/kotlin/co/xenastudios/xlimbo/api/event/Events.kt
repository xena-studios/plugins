package co.xenastudios.xlimbo.api.event

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.event.CancellableEvent
import co.xenastudios.core.api.event.Event
import co.xenastudios.core.api.event.EventType
import co.xenastudios.xlimbo.api.EnterReason
import co.xenastudios.xlimbo.api.ReturnCause
import co.xenastudios.xlimbo.api.ReturnRoute
import kotlin.jvm.JvmField

/**
 * A player on this server is about to be sent into limbo: `/xlimbo send`, the API's `sendToLimbo`
 * or xTeleport's `xlimbo` destination. Cancel it to keep them where they are. Not fired for a
 * player joining (the proxy sent them; there's nothing to stop): that's [EnteredLimboEvent].
 *
 * On the server thread, only when something listens. On Paper it's also
 * `co.xenastudios.xlimbo.api.bukkit.BukkitPreEnterLimboEvent`.
 *
 * @since 3.0.0
 */
public class PreEnterLimboEvent(public val player: PlayerRef) : CancellableEvent() {
    public companion object {
        /** Subscribe to it on [co.xenastudios.xlimbo.api.XLimboApi.events]. */
        @JvmField
        public val TYPE: EventType<PreEnterLimboEvent> = EventType.of("xlimbo:pre_enter")
    }
}

/**
 * A player arrived in limbo: joined this server, or was sent into its world. On the server
 * thread, only when something listens. On Paper it's also
 * `co.xenastudios.xlimbo.api.bukkit.BukkitEnteredLimboEvent`.
 *
 * @since 3.0.0
 */
public class EnteredLimboEvent(public val player: PlayerRef, public val reason: EnterReason) : Event {
    public companion object {
        @JvmField
        public val TYPE: EventType<EnteredLimboEvent> = EventType.of("xlimbo:entered")
    }
}

/**
 * A player is about to be sent back. Cancel it to keep them in limbo; they're told nothing, so
 * tell them why. A cancelled automatic return is tried again on a later pass of the queue, so it
 * can fire again for the same player.
 *
 * On the server thread, only when something listens. On Paper it's also
 * `co.xenastudios.xlimbo.api.bukkit.BukkitPreReturnEvent`.
 *
 * @since 3.0.0
 */
public class PreReturnEvent(
    public val player: PlayerRef,
    public val cause: ReturnCause,
    public val route: ReturnRoute,
    /** Where they're going: the proxy's name for the main server, or the teleport's destination (`previous`, `spawn`, `warp:<name>`, or an xTeleport destination's name). */
    public val destination: String,
) : CancellableEvent() {
    public companion object {
        @JvmField
        public val TYPE: EventType<PreReturnEvent> = EventType.of("xlimbo:pre_return")
    }
}

/**
 * A player was sent back: asked of the proxy, or teleported out of limbo. Through the proxy, the
 * proxy may still refuse them; they're back in limbo then, with [EnterReason.REFUSED]. On the
 * server thread, only when something listens. On Paper it's also
 * `co.xenastudios.xlimbo.api.bukkit.BukkitReturnedEvent`.
 *
 * @since 3.0.0
 */
public class ReturnedEvent(
    public val player: PlayerRef,
    public val cause: ReturnCause,
    public val route: ReturnRoute,
    public val destination: String,
) : Event {
    public companion object {
        @JvmField
        public val TYPE: EventType<ReturnedEvent> = EventType.of("xlimbo:returned")
    }
}

/**
 * The main server went up or down, as server-check saw it. Only while server-check is on, and only
 * for a change: the first answer after it starts isn't one. Fired off the server thread, on the
 * thread that asked, only when something listens; by then
 * [co.xenastudios.xlimbo.api.XLimboApi.isMainServerUp] already answers the new status. On Paper
 * it's also `co.xenastudios.xlimbo.api.bukkit.BukkitMainServerStatusEvent`.
 *
 * @since 3.0.0
 */
public class MainServerStatusEvent(
    /** `true` if it came up; `false` if it went down. */
    public val up: Boolean,
) : Event {
    public companion object {
        @JvmField
        public val TYPE: EventType<MainServerStatusEvent> = EventType.of("xlimbo:main_server_status")
    }
}
