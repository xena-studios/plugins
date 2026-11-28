package co.xenastudios.xlimits.api.bukkit

import co.xenastudios.xlimits.api.event.FarmRestoredEvent
import co.xenastudios.xlimits.api.event.FarmSlowedEvent
import co.xenastudios.xlimits.api.event.LimitReachedEvent
import co.xenastudios.xlimits.api.event.ProfileChangedEvent
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

/**
 * [LimitReachedEvent] as a Bukkit event, for plugins and Skript scripts listening the Bukkit way:
 * something is about to be refused at a limit. **Cancelling it allows it after all**, as
 * cancelling xLimits' own event does. On the thread that owns the place.
 *
 * @since 1.0.0
 */
public class BukkitLimitReachedEvent(private val event: LimitReachedEvent) : Event(), Cancellable {
    /** xLimits' own event, the same on every platform: the limit, the place, the player, the counts. */
    public fun getEvent(): LimitReachedEvent = event

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

/** [FarmSlowedEvent] as a Bukkit event: a crammed farm is about to be slowed. Cancel it to leave it be. @since 1.0.0 */
public class BukkitFarmSlowedEvent(private val event: FarmSlowedEvent) : Event(), Cancellable {
    public fun getEvent(): FarmSlowedEvent = event

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

/** [FarmRestoredEvent] as a Bukkit event: a farm's mobs went back to normal. @since 1.0.0 */
public class BukkitFarmRestoredEvent(private val event: FarmRestoredEvent) : Event() {
    public fun getEvent(): FarmRestoredEvent = event

    override fun getHandlers(): HandlerList = HANDLERS

    public companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        public fun getHandlerList(): HandlerList = HANDLERS
    }
}

/** [ProfileChangedEvent] as a Bukkit event: a load profile turned on or off. @since 1.0.0 */
public class BukkitProfileChangedEvent(private val event: ProfileChangedEvent) : Event() {
    public fun getEvent(): ProfileChangedEvent = event

    override fun getHandlers(): HandlerList = HANDLERS

    public companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        public fun getHandlerList(): HandlerList = HANDLERS
    }
}
