package co.xenastudios.xvote.api.bukkit

import co.xenastudios.xvote.api.event.VoteCountedEvent
import co.xenastudios.xvote.api.event.VotePartyEvent
import co.xenastudios.xvote.api.event.VoteReceivedEvent
import co.xenastudios.xvote.api.event.VoteRewardEvent
import co.xenastudios.xvote.api.event.VoteRewardedEvent
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

// xVote's events as Bukkit events, for plugins and Skript scripts listening the Bukkit way. Each
// wraps xVote's own event, the same on every platform, and is fired only while a Bukkit listener is
// there: asynchronously off the server's threads, synchronously on them.

/**
 * [VoteReceivedEvent] as a Bukkit event: a vote arrived and is about to be stored. Cancelling it
 * drops the vote. Replaces 1.x's `co.xenastudios.xvote.api.event.VoteReceivedEvent`.
 *
 * @since 2.0.0
 */
public class BukkitVoteReceivedEvent(private val event: VoteReceivedEvent, async: Boolean) : Event(async), Cancellable {
    public fun getEvent(): VoteReceivedEvent = event
    public fun getSite(): String = event.site
    public fun getUsername(): String = event.username

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

/** [VoteCountedEvent] as a Bukkit event: a vote was counted for a player, once on the whole network. @since 2.0.0 */
public class BukkitVoteCountedEvent(private val event: VoteCountedEvent, async: Boolean) : Event(async) {
    public fun getEvent(): VoteCountedEvent = event

    override fun getHandlers(): HandlerList = HANDLERS

    public companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        public fun getHandlerList(): HandlerList = HANDLERS
    }
}

/**
 * [VoteRewardEvent] as a Bukkit event: a player is about to be rewarded, on their thread. Change
 * [getRewards] or cancel it. Replaces 1.x's `VoteRewardEvent`.
 *
 * @since 2.0.0
 */
public class BukkitVoteRewardEvent(private val event: VoteRewardEvent, async: Boolean) : Event(async), Cancellable {
    public fun getEvent(): VoteRewardEvent = event
    public fun getRewards(): MutableList<String> = event.rewards

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

/** [VoteRewardedEvent] as a Bukkit event: a player was given a vote's rewards. @since 2.0.0 */
public class BukkitVoteRewardedEvent(private val event: VoteRewardedEvent, async: Boolean) : Event(async) {
    public fun getEvent(): VoteRewardedEvent = event

    override fun getHandlers(): HandlerList = HANDLERS

    public companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        public fun getHandlerList(): HandlerList = HANDLERS
    }
}

/** [VotePartyEvent] as a Bukkit event: the party is about to reward this server's players. Replaces 1.x's `VotePartyEvent`. @since 2.0.0 */
public class BukkitVotePartyEvent(private val event: VotePartyEvent, async: Boolean) : Event(async), Cancellable {
    public fun getEvent(): VotePartyEvent = event

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
