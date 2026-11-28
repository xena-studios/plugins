package co.xenastudios.xplots.bukkit

import co.xenastudios.core.api.event.Event
import co.xenastudios.core.common.event.SimpleEventBus
import co.xenastudios.xplots.api.bukkit.BukkitMemberAddEvent
import co.xenastudios.xplots.api.bukkit.BukkitMemberAddedEvent
import co.xenastudios.xplots.api.bukkit.BukkitMemberRemoveEvent
import co.xenastudios.xplots.api.bukkit.BukkitMemberRemovedEvent
import co.xenastudios.xplots.api.bukkit.BukkitPlotClaimEvent
import co.xenastudios.xplots.api.bukkit.BukkitPlotClaimedEvent
import co.xenastudios.xplots.api.bukkit.BukkitPlotUnclaimEvent
import co.xenastudios.xplots.api.bukkit.BukkitPlotUnclaimedEvent
import co.xenastudios.xplots.api.bukkit.BukkitRentPaidEvent
import co.xenastudios.xplots.api.bukkit.BukkitRentPayEvent
import co.xenastudios.xplots.api.event.MemberAddEvent
import co.xenastudios.xplots.api.event.MemberAddedEvent
import co.xenastudios.xplots.api.event.MemberRemoveEvent
import co.xenastudios.xplots.api.event.MemberRemovedEvent
import co.xenastudios.xplots.api.event.PlotClaimEvent
import co.xenastudios.xplots.api.event.PlotClaimedEvent
import co.xenastudios.xplots.api.event.PlotUnclaimEvent
import co.xenastudios.xplots.api.event.PlotUnclaimedEvent
import co.xenastudios.xplots.api.event.RentPaidEvent
import co.xenastudios.xplots.api.event.RentPayEvent
import org.bukkit.Server

/**
 * Fires the API's events as Bukkit events too, bridged just before the first event could be heard,
 * not as the server starts; the Bukkit events are named only in [Fire], so none loads until a
 * change is made with a listener there.
 */
internal class EventBridge(private val server: Server, private val bus: SimpleEventBus) : () -> Unit {
    override fun invoke() {
        bus.bridge(PlotClaimEvent.TYPE, forward = Forward(server, 0))
        bus.bridge(PlotClaimedEvent.TYPE, forward = Forward(server, 1))
        bus.bridge(PlotUnclaimEvent.TYPE, forward = Forward(server, 2))
        bus.bridge(PlotUnclaimedEvent.TYPE, forward = Forward(server, 3))
        bus.bridge(MemberAddEvent.TYPE, forward = Forward(server, 4))
        bus.bridge(MemberAddedEvent.TYPE, forward = Forward(server, 5))
        bus.bridge(MemberRemoveEvent.TYPE, forward = Forward(server, 6))
        bus.bridge(MemberRemovedEvent.TYPE, forward = Forward(server, 7))
        bus.bridge(RentPayEvent.TYPE, forward = Forward(server, 8))
        bus.bridge(RentPaidEvent.TYPE, forward = Forward(server, 9))
    }

    private class Forward(private val server: Server, private val kind: Int) : (Event) -> Unit {
        override fun invoke(event: Event) = Fire.fire(server, kind, event)
    }

    private object Fire {
        fun fire(server: Server, kind: Int, event: Event) {
            val bukkit: org.bukkit.event.Event = when (kind) {
                0 -> if (BukkitPlotClaimEvent.getHandlerList().registeredListeners.isEmpty()) return else BukkitPlotClaimEvent(event as PlotClaimEvent)
                1 -> if (BukkitPlotClaimedEvent.getHandlerList().registeredListeners.isEmpty()) return else BukkitPlotClaimedEvent(event as PlotClaimedEvent)
                2 -> if (BukkitPlotUnclaimEvent.getHandlerList().registeredListeners.isEmpty()) return else BukkitPlotUnclaimEvent(event as PlotUnclaimEvent)
                3 -> if (BukkitPlotUnclaimedEvent.getHandlerList().registeredListeners.isEmpty()) return else BukkitPlotUnclaimedEvent(event as PlotUnclaimedEvent)
                4 -> if (BukkitMemberAddEvent.getHandlerList().registeredListeners.isEmpty()) return else BukkitMemberAddEvent(event as MemberAddEvent)
                5 -> if (BukkitMemberAddedEvent.getHandlerList().registeredListeners.isEmpty()) return else BukkitMemberAddedEvent(event as MemberAddedEvent)
                6 -> if (BukkitMemberRemoveEvent.getHandlerList().registeredListeners.isEmpty()) return else BukkitMemberRemoveEvent(event as MemberRemoveEvent)
                7 -> if (BukkitMemberRemovedEvent.getHandlerList().registeredListeners.isEmpty()) return else BukkitMemberRemovedEvent(event as MemberRemovedEvent)
                8 -> if (BukkitRentPayEvent.getHandlerList().registeredListeners.isEmpty()) return else BukkitRentPayEvent(event as RentPayEvent)
                9 -> if (BukkitRentPaidEvent.getHandlerList().registeredListeners.isEmpty()) return else BukkitRentPaidEvent(event as RentPaidEvent)
                else -> return
            }
            server.pluginManager.callEvent(bukkit)
        }
    }
}
