package co.xenastudios.xstats.api.bukkit

import co.xenastudios.xstats.api.event.LeaderboardChangeEvent
import co.xenastudios.xstats.api.event.PrivacyChangeEvent
import co.xenastudios.xstats.api.event.StatChangeEvent
import co.xenastudios.xstats.api.event.StatsSavedEvent
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

// xStats' events as Bukkit events, for plugins and Skript scripts listening the Bukkit way. Each
// wraps xStats' own event, the same on every platform, and is fired only while a Bukkit listener is
// there: asynchronously off the server's threads, synchronously on them.

/** [StatChangeEvent] as a Bukkit event: a plugin is about to change one of its stats. Cancel it to drop the change. @since 2.0.0 */
public class BukkitStatChangeEvent(private val event: StatChangeEvent, async: Boolean) : Event(async), Cancellable {
    public fun getEvent(): StatChangeEvent = event

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

/** [PrivacyChangeEvent] as a Bukkit event: `/stats private`, about to apply. Cancel it to leave them as they are. @since 2.0.0 */
public class BukkitPrivacyChangeEvent(private val event: PrivacyChangeEvent, async: Boolean) : Event(async), Cancellable {
    public fun getEvent(): PrivacyChangeEvent = event

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

/** [StatsSavedEvent] as a Bukkit event: a save finished. Replaces 1.x's `StatsSavedEvent`. @since 2.0.0 */
public class BukkitStatsSavedEvent(private val event: StatsSavedEvent, async: Boolean) : Event(async) {
    public fun getEvent(): StatsSavedEvent = event
    public fun getPlayers(): Int = event.players
    public fun getMillis(): Long = event.millis

    override fun getHandlers(): HandlerList = HANDLERS

    public companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        public fun getHandlerList(): HandlerList = HANDLERS
    }
}

/** [LeaderboardChangeEvent] as a Bukkit event: a board has a new leader. Replaces 1.x's `LeaderboardChangeEvent`. @since 2.0.0 */
public class BukkitLeaderboardChangeEvent(private val event: LeaderboardChangeEvent, async: Boolean) : Event(async) {
    public fun getEvent(): LeaderboardChangeEvent = event
    public fun getStat(): String = event.stat
    public fun getLeaderName(): String = event.leaderName
    public fun getValue(): Long = event.value

    override fun getHandlers(): HandlerList = HANDLERS

    public companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        public fun getHandlerList(): HandlerList = HANDLERS
    }
}
