package co.xenastudios.xbans.bukkit

import co.xenastudios.core.api.event.Event
import co.xenastudios.core.common.event.SimpleEventBus
import co.xenastudios.xbans.api.bukkit.BukkitLoginDeniedEvent
import co.xenastudios.xbans.api.bukkit.BukkitPardonEvent
import co.xenastudios.xbans.api.bukkit.BukkitPardonedEvent
import co.xenastudios.xbans.api.bukkit.BukkitPunishEvent
import co.xenastudios.xbans.api.bukkit.BukkitPunishedEvent
import co.xenastudios.xbans.api.event.LoginDeniedEvent
import co.xenastudios.xbans.api.event.PardonEvent
import co.xenastudios.xbans.api.event.PardonedEvent
import co.xenastudios.xbans.api.event.PunishEvent
import co.xenastudios.xbans.api.event.PunishedEvent
import org.bukkit.Server

/**
 * Fires the API's events as Bukkit events too, made only when a Bukkit plugin listens. Bridged
 * just before the first event could be heard ([co.xenastudios.xbans.XBansPlugin.beforeFirstEvent]),
 * not as the server starts, and the Bukkit events are named only in [Fire]: neither loads until
 * someone is punished (decisions/2026-11-25-a-lighter-core.md).
 */
internal class EventBridge(private val server: Server, private val bus: SimpleEventBus) : () -> Unit {
    override fun invoke() {
        for (kind in 0..4) {
            val forward = Forward(server, kind)
            when (kind) {
                0 -> bus.bridge(PunishEvent.TYPE, forward = forward)
                1 -> bus.bridge(PunishedEvent.TYPE, forward = forward)
                2 -> bus.bridge(PardonEvent.TYPE, forward = forward)
                3 -> bus.bridge(PardonedEvent.TYPE, forward = forward)
                else -> bus.bridge(LoginDeniedEvent.TYPE, forward = forward)
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
                0 -> if (BukkitPunishEvent.getHandlerList().registeredListeners.isEmpty()) return else BukkitPunishEvent(event as PunishEvent, async)
                1 -> if (BukkitPunishedEvent.getHandlerList().registeredListeners.isEmpty()) return else BukkitPunishedEvent(event as PunishedEvent, async)
                2 -> if (BukkitPardonEvent.getHandlerList().registeredListeners.isEmpty()) return else BukkitPardonEvent(event as PardonEvent, async)
                3 -> if (BukkitPardonedEvent.getHandlerList().registeredListeners.isEmpty()) return else BukkitPardonedEvent(event as PardonedEvent, async)
                else -> if (BukkitLoginDeniedEvent.getHandlerList().registeredListeners.isEmpty()) return else BukkitLoginDeniedEvent(event as LoginDeniedEvent, async)
            }
            server.pluginManager.callEvent(bukkit)
        }
    }
}
