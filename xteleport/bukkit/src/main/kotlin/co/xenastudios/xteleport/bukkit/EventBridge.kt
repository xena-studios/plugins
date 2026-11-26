package co.xenastudios.xteleport.bukkit

import co.xenastudios.core.api.event.Event
import co.xenastudios.core.bukkit.online
import co.xenastudios.core.bukkit.toBukkit
import co.xenastudios.core.common.event.SimpleEventBus
import co.xenastudios.xteleport.api.bukkit.BukkitHomeSetEvent
import co.xenastudios.xteleport.api.bukkit.BukkitPreTeleportEvent
import co.xenastudios.xteleport.api.bukkit.BukkitTeleportEvent
import co.xenastudios.xteleport.api.bukkit.BukkitTpaRequestEvent
import co.xenastudios.xteleport.api.event.HomeSetEvent
import co.xenastudios.xteleport.api.event.PreTeleportEvent
import co.xenastudios.xteleport.api.event.TeleportEvent
import co.xenastudios.xteleport.api.event.TpaRequestEvent
import org.bukkit.Server

/**
 * Fires the API's events as Bukkit events too, made only when a Bukkit plugin listens. Bridged just
 * before the first event could be heard ([co.xenastudios.xteleport.XTeleportPlugin.beforeFirstEvent]),
 * not as the server starts, and the Bukkit events are named only in [Fire]: neither the API's events
 * nor Bukkit's load until someone teleports (decisions/2026-11-25-a-lighter-core.md).
 */
internal class EventBridge(private val server: Server, private val bus: SimpleEventBus) : () -> Unit {
    override fun invoke() {
        for (kind in 0..3) {
            val forward = Forward(server, kind)
            when (kind) {
                0 -> bus.bridge(PreTeleportEvent.TYPE, forward = forward)
                1 -> bus.bridge(TeleportEvent.TYPE, forward = forward)
                2 -> bus.bridge(HomeSetEvent.TYPE, forward = forward)
                else -> bus.bridge(TpaRequestEvent.TYPE, forward = forward)
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
            when (kind) {
                0 -> {
                    if (BukkitPreTeleportEvent.getHandlerList().registeredListeners.isEmpty()) return
                    val e = event as PreTeleportEvent
                    val player = e.player.online(server) ?: return
                    server.pluginManager.callEvent(BukkitPreTeleportEvent(player, e.destination.toBukkit(server) ?: return, e))
                }
                1 -> {
                    if (BukkitTeleportEvent.getHandlerList().registeredListeners.isEmpty()) return
                    val e = event as TeleportEvent
                    val player = e.player.online(server) ?: return
                    server.pluginManager.callEvent(BukkitTeleportEvent(player, e.from.toBukkit(server) ?: return, e.to.toBukkit(server) ?: return, e))
                }
                2 -> {
                    if (BukkitHomeSetEvent.getHandlerList().registeredListeners.isEmpty()) return
                    val e = event as HomeSetEvent
                    val player = e.player.online(server) ?: return
                    server.pluginManager.callEvent(BukkitHomeSetEvent(player, e.location.toBukkit(server) ?: return, e))
                }
                else -> {
                    if (BukkitTpaRequestEvent.getHandlerList().registeredListeners.isEmpty()) return
                    val e = event as TpaRequestEvent
                    server.pluginManager.callEvent(BukkitTpaRequestEvent(e.sender.online(server) ?: return, e.target.online(server) ?: return, e))
                }
            }
        }
    }
}
