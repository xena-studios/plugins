package co.xenastudios.xvanish.bukkit

import co.xenastudios.core.api.event.Event
import co.xenastudios.core.bukkit.online
import co.xenastudios.core.common.event.SimpleEventBus
import co.xenastudios.xvanish.api.bukkit.BukkitUnvanishedEvent
import co.xenastudios.xvanish.api.bukkit.BukkitVanishEvent
import co.xenastudios.xvanish.api.bukkit.BukkitVanishedEvent
import co.xenastudios.xvanish.api.event.UnvanishedEvent
import co.xenastudios.xvanish.api.event.VanishEvent
import co.xenastudios.xvanish.api.event.VanishedEvent
import org.bukkit.Server

/**
 * Fires the API's events as Bukkit events too, made only when a Bukkit plugin listens. Bridged just
 * before the first event could be heard, not as the server starts (decisions/2026-11-25-a-lighter-core.md).
 */
internal class EventBridge(private val server: Server, private val bus: SimpleEventBus) : () -> Unit {
    override fun invoke() {
        bus.bridge(VanishEvent.TYPE, forward = Forward(server, 0))
        bus.bridge(VanishedEvent.TYPE, forward = Forward(server, 1))
        bus.bridge(UnvanishedEvent.TYPE, forward = Forward(server, 2))
    }

    private class Forward(private val server: Server, private val kind: Int) : (Event) -> Unit {
        override fun invoke(event: Event) {
            Fire.fire(server, kind, event)
        }
    }

    private object Fire {
        fun fire(server: Server, kind: Int, event: Event) {
            when (kind) {
                0 -> {
                    if (BukkitVanishEvent.getHandlerList().registeredListeners.isEmpty()) return
                    val e = event as VanishEvent
                    server.pluginManager.callEvent(BukkitVanishEvent(e.player.online(server) ?: return, e))
                }
                1 -> {
                    if (BukkitVanishedEvent.getHandlerList().registeredListeners.isEmpty()) return
                    val e = event as VanishedEvent
                    server.pluginManager.callEvent(BukkitVanishedEvent(e.player.online(server) ?: return, e))
                }
                else -> {
                    if (BukkitUnvanishedEvent.getHandlerList().registeredListeners.isEmpty()) return
                    val e = event as UnvanishedEvent
                    server.pluginManager.callEvent(BukkitUnvanishedEvent(e.player.online(server) ?: return, e))
                }
            }
        }
    }
}
