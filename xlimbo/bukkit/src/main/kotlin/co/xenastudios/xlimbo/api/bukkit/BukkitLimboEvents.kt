package co.xenastudios.xlimbo.api.bukkit

import co.xenastudios.xlimbo.api.EnterReason
import co.xenastudios.xlimbo.api.ReturnCause
import co.xenastudios.xlimbo.api.ReturnRoute
import co.xenastudios.xlimbo.api.event.EnteredLimboEvent
import co.xenastudios.xlimbo.api.event.MainServerStatusEvent
import co.xenastudios.xlimbo.api.event.PreEnterLimboEvent
import co.xenastudios.xlimbo.api.event.PreReturnEvent
import co.xenastudios.xlimbo.api.event.ReturnedEvent
import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import org.bukkit.event.player.PlayerEvent

/**
 * [PreEnterLimboEvent] as a Bukkit event, for plugins and Skript scripts listening the Bukkit way:
 * a player on this server is about to be sent into limbo. Cancelling it keeps them where they are.
 * On the server thread.
 *
 * @since 3.0.0
 */
public class BukkitPreEnterLimboEvent(player: Player, private val event: PreEnterLimboEvent) : PlayerEvent(player), Cancellable {
    /** xLimbo's own event, the same on every platform. */
    public fun getEvent(): PreEnterLimboEvent = event

    override fun isCancelled(): Boolean = event.isCancelled

    override fun setCancelled(cancel: Boolean) {
        event.isCancelled = cancel
    }

    override fun getHandlers(): HandlerList = HANDLERS

    public companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        public fun getHandlerList(): HandlerList = HANDLERS
    }
}

/** [EnteredLimboEvent] as a Bukkit event: a player arrived in limbo. On the server thread. @since 3.0.0 */
public class BukkitEnteredLimboEvent(player: Player, private val event: EnteredLimboEvent) : PlayerEvent(player) {
    public fun getReason(): EnterReason = event.reason

    public fun getEvent(): EnteredLimboEvent = event

    override fun getHandlers(): HandlerList = HANDLERS

    public companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        public fun getHandlerList(): HandlerList = HANDLERS
    }
}

/**
 * [PreReturnEvent] as a Bukkit event: a player is about to be sent back. Cancelling it keeps them
 * in limbo. On the server thread.
 *
 * @since 3.0.0
 */
public class BukkitPreReturnEvent(player: Player, private val event: PreReturnEvent) : PlayerEvent(player), Cancellable {
    public fun getCause(): ReturnCause = event.cause

    public fun getRoute(): ReturnRoute = event.route

    /** The proxy's name for the main server, or the teleport's destination. */
    public fun getDestination(): String = event.destination

    public fun getEvent(): PreReturnEvent = event

    override fun isCancelled(): Boolean = event.isCancelled

    override fun setCancelled(cancel: Boolean) {
        event.isCancelled = cancel
    }

    override fun getHandlers(): HandlerList = HANDLERS

    public companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        public fun getHandlerList(): HandlerList = HANDLERS
    }
}

/** [ReturnedEvent] as a Bukkit event: a player was sent back. On the server thread. @since 3.0.0 */
public class BukkitReturnedEvent(player: Player, private val event: ReturnedEvent) : PlayerEvent(player) {
    public fun getCause(): ReturnCause = event.cause

    public fun getRoute(): ReturnRoute = event.route

    public fun getDestination(): String = event.destination

    public fun getEvent(): ReturnedEvent = event

    override fun getHandlers(): HandlerList = HANDLERS

    public companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        public fun getHandlerList(): HandlerList = HANDLERS
    }
}

/** [MainServerStatusEvent] as a Bukkit event: server-check saw the main server go up or down. Asynchronous. @since 3.0.0 */
public class BukkitMainServerStatusEvent(private val event: MainServerStatusEvent) : Event(true) {
    public fun isUp(): Boolean = event.up

    public fun getEvent(): MainServerStatusEvent = event

    override fun getHandlers(): HandlerList = HANDLERS

    public companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        public fun getHandlerList(): HandlerList = HANDLERS
    }
}
