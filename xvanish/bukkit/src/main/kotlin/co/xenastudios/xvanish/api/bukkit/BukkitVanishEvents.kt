package co.xenastudios.xvanish.api.bukkit

import co.xenastudios.xvanish.api.VanishCause
import co.xenastudios.xvanish.api.event.UnvanishedEvent
import co.xenastudios.xvanish.api.event.VanishEvent
import co.xenastudios.xvanish.api.event.VanishedEvent
import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.HandlerList
import org.bukkit.event.player.PlayerEvent

/**
 * [VanishEvent] as a Bukkit event, for plugins and Skript scripts listening the Bukkit way: a
 * player is about to vanish or show again. Cancelling it refuses, as cancelling xVanish's own
 * event does. On the player's thread.
 *
 * @since 1.0.0
 */
public class BukkitVanishEvent(player: Player, private val event: VanishEvent) : PlayerEvent(player), Cancellable {
    /** `true` to vanish, `false` to show. */
    public fun isVanishing(): Boolean = event.vanishing

    public fun isSilent(): Boolean = event.silent

    public fun getCause(): VanishCause = event.cause

    /** xVanish's own event, the same on every platform. */
    public fun getEvent(): VanishEvent = event

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

/** [VanishedEvent] as a Bukkit event: a player vanished. On the player's thread. @since 1.0.0 */
public class BukkitVanishedEvent(player: Player, private val event: VanishedEvent) : PlayerEvent(player) {
    public fun isSilent(): Boolean = event.silent

    public fun getCause(): VanishCause = event.cause

    public fun getEvent(): VanishedEvent = event

    override fun getHandlers(): HandlerList = HANDLERS

    public companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        public fun getHandlerList(): HandlerList = HANDLERS
    }
}

/** [UnvanishedEvent] as a Bukkit event: a player showed again. On the player's thread. @since 1.0.0 */
public class BukkitUnvanishedEvent(player: Player, private val event: UnvanishedEvent) : PlayerEvent(player) {
    public fun isSilent(): Boolean = event.silent

    public fun getCause(): VanishCause = event.cause

    public fun getEvent(): UnvanishedEvent = event

    override fun getHandlers(): HandlerList = HANDLERS

    public companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        public fun getHandlerList(): HandlerList = HANDLERS
    }
}
