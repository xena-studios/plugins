package co.xenastudios.xmoney.bukkit

import co.xenastudios.core.api.event.Event
import co.xenastudios.core.common.event.SimpleEventBus
import co.xenastudios.xmoney.api.bukkit.BukkitBalanceChangedEvent
import co.xenastudios.xmoney.api.bukkit.BukkitPreTransactionEvent
import co.xenastudios.xmoney.api.event.BalanceChangedEvent
import co.xenastudios.xmoney.api.event.PreTransactionEvent
import org.bukkit.Bukkit
import org.bukkit.Server

/**
 * Fires the API's events as Bukkit events too, made only when a Bukkit plugin listens. Bridged
 * just before the first change ([co.xenastudios.xmoney.XMoneyPlugin.beforeFirstChange]), not as
 * the server starts, and the Bukkit events are named only in [Fire]: neither the API's events
 * nor Bukkit's load until there's money moving (decisions/2026-11-25-a-lighter-core.md).
 */
internal class EventBridge(private val server: Server, private val bus: SimpleEventBus) : () -> Unit {
    override fun invoke() {
        bus.bridge(PreTransactionEvent.TYPE, forward = Forward(server, pre = true))
        bus.bridge(BalanceChangedEvent.TYPE, forward = Forward(server, pre = false))
    }

    // One class for both events, not a lambda each.
    private class Forward(private val server: Server, private val pre: Boolean) : (Event) -> Unit {
        override fun invoke(event: Event) {
            if (pre) Fire.pre(server, event as PreTransactionEvent) else Fire.changed(server, event as BalanceChangedEvent)
        }
    }

    private object Fire {
        fun pre(server: Server, event: PreTransactionEvent) {
            if (BukkitPreTransactionEvent.getHandlerList().registeredListeners.isEmpty()) return
            server.pluginManager.callEvent(BukkitPreTransactionEvent(!Bukkit.isPrimaryThread(), event))
        }

        fun changed(server: Server, event: BalanceChangedEvent) {
            if (BukkitBalanceChangedEvent.getHandlerList().registeredListeners.isEmpty()) return
            server.pluginManager.callEvent(BukkitBalanceChangedEvent(event))
        }
    }
}
