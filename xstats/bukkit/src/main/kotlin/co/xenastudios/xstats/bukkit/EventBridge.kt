package co.xenastudios.xstats.bukkit

import co.xenastudios.core.api.event.Event
import co.xenastudios.core.common.event.SimpleEventBus
import co.xenastudios.xstats.api.bukkit.BukkitLeaderboardChangeEvent
import co.xenastudios.xstats.api.bukkit.BukkitPrivacyChangeEvent
import co.xenastudios.xstats.api.bukkit.BukkitStatChangeEvent
import co.xenastudios.xstats.api.bukkit.BukkitStatsSavedEvent
import co.xenastudios.xstats.api.event.LeaderboardChangeEvent
import co.xenastudios.xstats.api.event.PrivacyChangeEvent
import co.xenastudios.xstats.api.event.StatChangeEvent
import co.xenastudios.xstats.api.event.StatsSavedEvent
import org.bukkit.Server

/**
 * Fires the API's events as Bukkit events too, made only when a Bukkit plugin listens. Bridged just
 * before the first event could be heard ([co.xenastudios.xstats.XStatsPlugin.beforeFirstEvent]), not as
 * the server starts, and the Bukkit events are named only in [Fire] (decisions/2026-11-25-a-lighter-core.md).
 */
internal class EventBridge(private val server: Server, private val bus: SimpleEventBus) : () -> Unit {
    override fun invoke() {
        for (kind in 0..3) {
            val forward = Forward(server, kind)
            when (kind) {
                0 -> bus.bridge(StatChangeEvent.TYPE, forward = forward)
                1 -> bus.bridge(PrivacyChangeEvent.TYPE, forward = forward)
                2 -> bus.bridge(StatsSavedEvent.TYPE, forward = forward)
                else -> bus.bridge(LeaderboardChangeEvent.TYPE, forward = forward)
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
                0 -> if (BukkitStatChangeEvent.getHandlerList().registeredListeners.isEmpty()) return else BukkitStatChangeEvent(event as StatChangeEvent, async)
                1 -> if (BukkitPrivacyChangeEvent.getHandlerList().registeredListeners.isEmpty()) return else BukkitPrivacyChangeEvent(event as PrivacyChangeEvent, async)
                2 -> if (BukkitStatsSavedEvent.getHandlerList().registeredListeners.isEmpty()) return else BukkitStatsSavedEvent(event as StatsSavedEvent, async)
                else -> if (BukkitLeaderboardChangeEvent.getHandlerList().registeredListeners.isEmpty()) return else BukkitLeaderboardChangeEvent(event as LeaderboardChangeEvent, async)
            }
            server.pluginManager.callEvent(bukkit)
        }
    }
}
