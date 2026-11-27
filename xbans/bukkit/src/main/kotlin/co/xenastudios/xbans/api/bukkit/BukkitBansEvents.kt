package co.xenastudios.xbans.api.bukkit

import co.xenastudios.xbans.api.Punishment
import co.xenastudios.xbans.api.event.LoginDeniedEvent
import co.xenastudios.xbans.api.event.PardonEvent
import co.xenastudios.xbans.api.event.PardonedEvent
import co.xenastudios.xbans.api.event.PunishEvent
import co.xenastudios.xbans.api.event.PunishedEvent
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

// xBans' events as Bukkit events, for plugins and Skript scripts listening the Bukkit way. Each
// wraps xBans' own event, the same on every platform, and is fired off the server's threads
// (asynchronous Bukkit events), only while a Bukkit listener is there.

/**
 * [PunishEvent] as a Bukkit event: a punishment is about to be made. Cancelling it stops it, as
 * cancelling xBans' own event does. 1.x's `co.xenastudios.xbans.api.event.PunishEvent`, which this replaces.
 *
 * @since 2.0.0
 */
public class BukkitPunishEvent(private val event: PunishEvent, async: Boolean) : Event(async), Cancellable {
    /** xBans' own event, with what's about to be made. */
    public fun getEvent(): PunishEvent = event

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

/** [PunishedEvent] as a Bukkit event: a punishment was made, here or on another server. 1.x's `PunishedEvent`. @since 2.0.0 */
public class BukkitPunishedEvent(private val event: PunishedEvent, async: Boolean) : Event(async) {
    public fun getPunishment(): Punishment = event.punishment

    public fun isFromOtherServer(): Boolean = event.fromOtherServer

    public fun getEvent(): PunishedEvent = event

    override fun getHandlers(): HandlerList = HANDLERS

    public companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        public fun getHandlerList(): HandlerList = HANDLERS
    }
}

/** [PardonEvent] as a Bukkit event: a punishment is about to be lifted. Cancelling it keeps it in force. @since 2.0.0 */
public class BukkitPardonEvent(private val event: PardonEvent, async: Boolean) : Event(async), Cancellable {
    public fun getPunishment(): Punishment = event.punishment

    public fun getEvent(): PardonEvent = event

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

/** [PardonedEvent] as a Bukkit event: a punishment was lifted, here or on another server. 1.x's `RevokedEvent`. @since 2.0.0 */
public class BukkitPardonedEvent(private val event: PardonedEvent, async: Boolean) : Event(async) {
    public fun getPunishment(): Punishment = event.punishment

    public fun isFromOtherServer(): Boolean = event.fromOtherServer

    public fun getEvent(): PardonedEvent = event

    override fun getHandlers(): HandlerList = HANDLERS

    public companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        public fun getHandlerList(): HandlerList = HANDLERS
    }
}

/** [LoginDeniedEvent] as a Bukkit event: a player was turned away as they joined. Not cancellable (see xBans' event). @since 2.0.0 */
public class BukkitLoginDeniedEvent(private val event: LoginDeniedEvent, async: Boolean) : Event(async) {
    public fun getEvent(): LoginDeniedEvent = event

    override fun getHandlers(): HandlerList = HANDLERS

    public companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        public fun getHandlerList(): HandlerList = HANDLERS
    }
}
