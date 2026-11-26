package co.xenastudios.xteleport.api.bukkit

import co.xenastudios.xteleport.api.TeleportType
import co.xenastudios.xteleport.api.event.HomeSetEvent
import co.xenastudios.xteleport.api.event.PreTeleportEvent
import co.xenastudios.xteleport.api.event.TeleportEvent
import co.xenastudios.xteleport.api.event.TpaRequestEvent
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.HandlerList
import org.bukkit.event.player.PlayerEvent

/**
 * [PreTeleportEvent] as a Bukkit event, for plugins and Skript scripts listening the Bukkit way: a
 * player is about to be teleported, after the warmup and the safety check. Cancelling it stops the
 * teleport, as cancelling xTeleport's own event does. On the player's thread. 1.x's
 * `co.xenastudios.xteleport.api.event.PreTeleportEvent`, which this replaces.
 *
 * @since 2.0.0
 */
public class BukkitPreTeleportEvent(player: Player, private val to: Location, private val event: PreTeleportEvent) : PlayerEvent(player), Cancellable {
    /** Where they're going. */
    public fun getTo(): Location = to.clone()

    public fun getType(): TeleportType = event.type

    /** xTeleport's own event, the same on every platform. */
    public fun getEvent(): PreTeleportEvent = event

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

/** [TeleportEvent] as a Bukkit event: a player arrived. On the player's thread. @since 2.0.0 */
public class BukkitTeleportEvent(player: Player, private val from: Location, private val to: Location, private val event: TeleportEvent) : PlayerEvent(player) {
    public fun getFrom(): Location = from.clone()

    public fun getTo(): Location = to.clone()

    public fun getType(): TeleportType = event.type

    public fun getEvent(): TeleportEvent = event

    override fun getHandlers(): HandlerList = HANDLERS

    public companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        public fun getHandlerList(): HandlerList = HANDLERS
    }
}

/** [HomeSetEvent] as a Bukkit event: `/sethome`, before it's saved. Cancelling it refuses. @since 2.0.0 */
public class BukkitHomeSetEvent(player: Player, private val location: Location, private val event: HomeSetEvent) : PlayerEvent(player), Cancellable {
    public fun getName(): String = event.name

    public fun getLocation(): Location = location.clone()

    public fun isReplacing(): Boolean = event.replacing

    public fun getEvent(): HomeSetEvent = event

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

/** [TpaRequestEvent] as a Bukkit event: `/tpa` or `/tpahere`, before it's sent. Cancelling it refuses. @since 2.0.0 */
public class BukkitTpaRequestEvent(sender: Player, private val target: Player, private val event: TpaRequestEvent) : PlayerEvent(sender), Cancellable {
    public fun getTarget(): Player = target

    public fun isHere(): Boolean = event.here

    public fun getEvent(): TpaRequestEvent = event

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
