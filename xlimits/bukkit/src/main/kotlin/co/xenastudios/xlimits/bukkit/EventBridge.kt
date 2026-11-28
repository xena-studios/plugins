package co.xenastudios.xlimits.bukkit

import co.xenastudios.core.api.event.Event
import co.xenastudios.core.common.event.SimpleEventBus
import co.xenastudios.xlimits.api.bukkit.BukkitFarmRestoredEvent
import co.xenastudios.xlimits.api.bukkit.BukkitFarmSlowedEvent
import co.xenastudios.xlimits.api.bukkit.BukkitLimitReachedEvent
import co.xenastudios.xlimits.api.bukkit.BukkitProfileChangedEvent
import co.xenastudios.xlimits.api.event.FarmRestoredEvent
import co.xenastudios.xlimits.api.event.FarmSlowedEvent
import co.xenastudios.xlimits.api.event.LimitReachedEvent
import co.xenastudios.xlimits.api.event.ProfileChangedEvent
import org.bukkit.Server

/**
 * Fires the API's events as Bukkit events too, made only when a Bukkit plugin listens. Bridged just
 * before the first event could be heard, not as the server starts (decisions/2026-11-25-a-lighter-core.md).
 */
internal class EventBridge(private val server: Server, private val bus: SimpleEventBus) : () -> Unit {
    override fun invoke() {
        bus.bridge(LimitReachedEvent.TYPE, forward = Forward(server, 0))
        bus.bridge(FarmSlowedEvent.TYPE, forward = Forward(server, 1))
        bus.bridge(FarmRestoredEvent.TYPE, forward = Forward(server, 2))
        bus.bridge(ProfileChangedEvent.TYPE, forward = Forward(server, 3))
    }

    private class Forward(private val server: Server, private val kind: Int) : (Event) -> Unit {
        override fun invoke(event: Event) {
            Fire.fire(server, kind, event)
        }
    }

    private object Fire {
        fun fire(server: Server, kind: Int, event: Event) {
            when (kind) {
                0 -> if (BukkitLimitReachedEvent.getHandlerList().registeredListeners.isNotEmpty()) {
                    server.pluginManager.callEvent(BukkitLimitReachedEvent(event as LimitReachedEvent))
                }
                1 -> if (BukkitFarmSlowedEvent.getHandlerList().registeredListeners.isNotEmpty()) {
                    server.pluginManager.callEvent(BukkitFarmSlowedEvent(event as FarmSlowedEvent))
                }
                2 -> if (BukkitFarmRestoredEvent.getHandlerList().registeredListeners.isNotEmpty()) {
                    server.pluginManager.callEvent(BukkitFarmRestoredEvent(event as FarmRestoredEvent))
                }
                else -> if (BukkitProfileChangedEvent.getHandlerList().registeredListeners.isNotEmpty()) {
                    server.pluginManager.callEvent(BukkitProfileChangedEvent(event as ProfileChangedEvent))
                }
            }
        }
    }
}
