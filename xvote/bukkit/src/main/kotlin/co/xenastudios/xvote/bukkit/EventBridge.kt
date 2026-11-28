package co.xenastudios.xvote.bukkit

import co.xenastudios.core.api.event.Event
import co.xenastudios.core.common.event.SimpleEventBus
import co.xenastudios.xvote.api.bukkit.BukkitVoteCountedEvent
import co.xenastudios.xvote.api.bukkit.BukkitVotePartyEvent
import co.xenastudios.xvote.api.bukkit.BukkitVoteReceivedEvent
import co.xenastudios.xvote.api.bukkit.BukkitVoteRewardEvent
import co.xenastudios.xvote.api.bukkit.BukkitVoteRewardedEvent
import co.xenastudios.xvote.api.event.VoteCountedEvent
import co.xenastudios.xvote.api.event.VotePartyEvent
import co.xenastudios.xvote.api.event.VoteReceivedEvent
import co.xenastudios.xvote.api.event.VoteRewardEvent
import co.xenastudios.xvote.api.event.VoteRewardedEvent
import org.bukkit.Server

/**
 * Fires the API's events as Bukkit events too, made only when a Bukkit plugin listens. Bridged just
 * before the first event could be heard ([co.xenastudios.xvote.XVotePlugin.beforeFirstEvent]), not
 * as the server starts, and the Bukkit events are named only in [Fire]: neither loads until the
 * first vote (decisions/2026-11-25-a-lighter-core.md).
 */
internal class EventBridge(private val server: Server, private val bus: SimpleEventBus) : () -> Unit {
    override fun invoke() {
        for (kind in 0..4) {
            val forward = Forward(server, kind)
            when (kind) {
                0 -> bus.bridge(VoteReceivedEvent.TYPE, forward = forward)
                1 -> bus.bridge(VoteCountedEvent.TYPE, forward = forward)
                2 -> bus.bridge(VoteRewardEvent.TYPE, forward = forward)
                3 -> bus.bridge(VoteRewardedEvent.TYPE, forward = forward)
                else -> bus.bridge(VotePartyEvent.TYPE, forward = forward)
            }
        }
    }

    // One class for every event, not a lambda each.
    private class Forward(private val server: Server, private val kind: Int) : (Event) -> Unit {
        override fun invoke(event: Event) {
            Fire.fire(server, kind, event)
        }
    }

    private object Fire {
        fun fire(server: Server, kind: Int, event: Event) {
            // Asynchronous Bukkit events off the main thread, as Bukkit asks.
            val async = !server.isPrimaryThread
            val bukkit = when (kind) {
                0 -> if (BukkitVoteReceivedEvent.getHandlerList().registeredListeners.isEmpty()) return else BukkitVoteReceivedEvent(event as VoteReceivedEvent, async)
                1 -> if (BukkitVoteCountedEvent.getHandlerList().registeredListeners.isEmpty()) return else BukkitVoteCountedEvent(event as VoteCountedEvent, async)
                2 -> if (BukkitVoteRewardEvent.getHandlerList().registeredListeners.isEmpty()) return else BukkitVoteRewardEvent(event as VoteRewardEvent, async)
                3 -> if (BukkitVoteRewardedEvent.getHandlerList().registeredListeners.isEmpty()) return else BukkitVoteRewardedEvent(event as VoteRewardedEvent, async)
                else -> if (BukkitVotePartyEvent.getHandlerList().registeredListeners.isEmpty()) return else BukkitVotePartyEvent(event as VotePartyEvent, async)
            }
            server.pluginManager.callEvent(bukkit)
        }
    }
}
