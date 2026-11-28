package co.xenastudios.xlimbo.bukkit

import co.xenastudios.core.api.event.Event
import co.xenastudios.core.bukkit.online
import co.xenastudios.core.common.event.SimpleEventBus
import co.xenastudios.xlimbo.api.bukkit.BukkitEnteredLimboEvent
import co.xenastudios.xlimbo.api.bukkit.BukkitMainServerStatusEvent
import co.xenastudios.xlimbo.api.bukkit.BukkitPreEnterLimboEvent
import co.xenastudios.xlimbo.api.bukkit.BukkitPreReturnEvent
import co.xenastudios.xlimbo.api.bukkit.BukkitReturnedEvent
import co.xenastudios.xlimbo.api.event.EnteredLimboEvent
import co.xenastudios.xlimbo.api.event.MainServerStatusEvent
import co.xenastudios.xlimbo.api.event.PreEnterLimboEvent
import co.xenastudios.xlimbo.api.event.PreReturnEvent
import co.xenastudios.xlimbo.api.event.ReturnedEvent
import org.bukkit.Server

/**
 * Fires the API's events as Bukkit events too, made only when a Bukkit plugin listens. Bridged just
 * before the first event could be heard, not as the server starts (decisions/2026-11-25-a-lighter-core.md).
 */
internal class EventBridge(private val server: Server, private val bus: SimpleEventBus) : () -> Unit {
    override fun invoke() {
        bus.bridge(PreEnterLimboEvent.TYPE, forward = Forward(server, 0))
        bus.bridge(EnteredLimboEvent.TYPE, forward = Forward(server, 1))
        bus.bridge(PreReturnEvent.TYPE, forward = Forward(server, 2))
        bus.bridge(ReturnedEvent.TYPE, forward = Forward(server, 3))
        bus.bridge(MainServerStatusEvent.TYPE, forward = Forward(server, 4))
    }

    private class Forward(private val server: Server, private val kind: Int) : (Event) -> Unit {
        override fun invoke(event: Event) {
            Fire.fire(server, kind, event)
        }
    }

    private object Fire {
        fun fire(server: Server, kind: Int, event: Event) {
            val pm = server.pluginManager
            when (kind) {
                0 -> {
                    if (BukkitPreEnterLimboEvent.getHandlerList().registeredListeners.isEmpty()) return
                    val e = event as PreEnterLimboEvent
                    pm.callEvent(BukkitPreEnterLimboEvent(e.player.online(server) ?: return, e))
                }
                1 -> {
                    if (BukkitEnteredLimboEvent.getHandlerList().registeredListeners.isEmpty()) return
                    val e = event as EnteredLimboEvent
                    pm.callEvent(BukkitEnteredLimboEvent(e.player.online(server) ?: return, e))
                }
                2 -> {
                    if (BukkitPreReturnEvent.getHandlerList().registeredListeners.isEmpty()) return
                    val e = event as PreReturnEvent
                    pm.callEvent(BukkitPreReturnEvent(e.player.online(server) ?: return, e))
                }
                3 -> {
                    if (BukkitReturnedEvent.getHandlerList().registeredListeners.isEmpty()) return
                    val e = event as ReturnedEvent
                    pm.callEvent(BukkitReturnedEvent(e.player.online(server) ?: return, e))
                }
                else -> {
                    if (BukkitMainServerStatusEvent.getHandlerList().registeredListeners.isEmpty()) return
                    pm.callEvent(BukkitMainServerStatusEvent(event as MainServerStatusEvent))
                }
            }
        }
    }
}
