package co.xenastudios.xutilities.api.bukkit

import co.xenastudios.xutilities.api.event.BackRecordEvent
import co.xenastudios.xutilities.api.event.BackTeleportEvent
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.HandlerList
import org.bukkit.event.player.PlayerEvent

/**
 * [BackRecordEvent] as a Bukkit event, for plugins and Skript scripts listening the Bukkit way:
 * a spot is about to be saved as where `/back` takes a player. Cancelling it cancels xUtilities'
 * own event, so `/back` keeps the spot from before. Fired on the player's thread, only when a
 * Bukkit listener is registered.
 *
 * @since 3.0.0
 */
public class BukkitBackRecordEvent(player: Player, private val location: Location, private val event: BackRecordEvent) : PlayerEvent(player), Cancellable {
    /** The spot about to be saved: a copy. */
    public fun getLocation(): Location = location.clone()

    /** Why it's being saved. */
    public fun getReason(): BackRecordEvent.Reason = event.reason

    /** xUtilities' own event, the same on every platform. */
    public fun getEvent(): BackRecordEvent = event

    override fun isCancelled(): Boolean = event.isCancelled

    override fun setCancelled(cancel: Boolean) {
        event.isCancelled = cancel
    }

    override fun getHandlers(): HandlerList = HANDLERS

    public companion object {
        private val HANDLERS = HandlerList()

        /** Every listener for this event, as Bukkit needs. */
        @JvmStatic
        public fun getHandlerList(): HandlerList = HANDLERS
    }
}

/**
 * [BackTeleportEvent] as a Bukkit event: a player used `/back` and is about to be teleported.
 * Cancel it to keep them where they are; they're told nothing, so tell them why.
 *
 * @since 3.0.0
 */
public class BukkitBackTeleportEvent(player: Player, private val destination: Location, private val event: BackTeleportEvent) : PlayerEvent(player), Cancellable {
    /** Where `/back` would take the player: a copy. */
    public fun getDestination(): Location = destination.clone()

    /** xUtilities' own event, the same on every platform. */
    public fun getEvent(): BackTeleportEvent = event

    override fun isCancelled(): Boolean = event.isCancelled

    override fun setCancelled(cancel: Boolean) {
        event.isCancelled = cancel
    }

    override fun getHandlers(): HandlerList = HANDLERS

    public companion object {
        private val HANDLERS = HandlerList()

        /** Every listener for this event, as Bukkit needs. */
        @JvmStatic
        public fun getHandlerList(): HandlerList = HANDLERS
    }
}
