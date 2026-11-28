package co.xenastudios.xrewind.bukkit

import co.xenastudios.core.api.event.Event
import co.xenastudios.core.common.event.SimpleEventBus
import co.xenastudios.xrewind.api.bukkit.BukkitLookupEvent
import co.xenastudios.xrewind.api.bukkit.BukkitRollbackEvent
import co.xenastudios.xrewind.api.bukkit.BukkitRollbackFinishedEvent
import co.xenastudios.xrewind.api.event.LookupEvent
import co.xenastudios.xrewind.api.event.RollbackEvent
import co.xenastudios.xrewind.api.event.RollbackFinishedEvent
import org.bukkit.Server

/**
 * Fires the API's events as Bukkit events too, bridged just before the first event could be
 * heard, not as the server starts; the Bukkit events are named only in [Fire], so none loads until
 * a lookup or rollback runs with a listener there.
 */
internal class EventBridge(private val server: Server, private val bus: SimpleEventBus) : () -> Unit {
    override fun invoke() {
        for (kind in 0..2) {
            val forward = Forward(server, kind)
            when (kind) {
                0 -> bus.bridge(LookupEvent.TYPE, forward = forward)
                1 -> bus.bridge(RollbackEvent.TYPE, forward = forward)
                else -> bus.bridge(RollbackFinishedEvent.TYPE, forward = forward)
            }
        }
    }

    private class Forward(private val server: Server, private val kind: Int) : (Event) -> Unit {
        override fun invoke(event: Event) {
            Fire.fire(server, kind, event)
        }
    }

    private object Fire {
        fun fire(server: Server, kind: Int, event: Event) {
            when (kind) {
                0 -> if (BukkitLookupEvent.getHandlerList().registeredListeners.isNotEmpty()) server.pluginManager.callEvent(BukkitLookupEvent(event as LookupEvent))
                1 -> if (BukkitRollbackEvent.getHandlerList().registeredListeners.isNotEmpty()) server.pluginManager.callEvent(BukkitRollbackEvent(event as RollbackEvent))
                else -> if (BukkitRollbackFinishedEvent.getHandlerList().registeredListeners.isNotEmpty()) server.pluginManager.callEvent(BukkitRollbackFinishedEvent(event as RollbackFinishedEvent))
            }
        }
    }
}
