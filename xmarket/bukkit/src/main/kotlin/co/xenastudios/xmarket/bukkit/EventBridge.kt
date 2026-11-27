package co.xenastudios.xmarket.bukkit

import co.xenastudios.core.api.event.Event
import co.xenastudios.core.bukkit.online
import co.xenastudios.core.common.event.SimpleEventBus
import co.xenastudios.xmarket.api.bukkit.BukkitShopBoughtEvent
import co.xenastudios.xmarket.api.bukkit.BukkitShopBuyEvent
import co.xenastudios.xmarket.api.bukkit.BukkitShopSellEvent
import co.xenastudios.xmarket.api.bukkit.BukkitShopSoldEvent
import co.xenastudios.xmarket.api.event.ShopBoughtEvent
import co.xenastudios.xmarket.api.event.ShopBuyEvent
import co.xenastudios.xmarket.api.event.ShopSellEvent
import co.xenastudios.xmarket.api.event.ShopSoldEvent
import org.bukkit.Server

/**
 * Fires the API's events as Bukkit events too, bridged just before the first event could be
 * heard, not as the server starts; the Bukkit events are named only in [Fire], so neither loads
 * until someone trades with a listener there.
 */
internal class EventBridge(private val server: Server, private val bus: SimpleEventBus) : () -> Unit {
    override fun invoke() {
        for (kind in 0..3) {
            val forward = Forward(server, kind)
            when (kind) {
                0 -> bus.bridge(ShopBuyEvent.TYPE, forward = forward)
                1 -> bus.bridge(ShopSellEvent.TYPE, forward = forward)
                2 -> bus.bridge(ShopBoughtEvent.TYPE, forward = forward)
                else -> bus.bridge(ShopSoldEvent.TYPE, forward = forward)
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
                0 -> {
                    if (BukkitShopBuyEvent.getHandlerList().registeredListeners.isEmpty()) return
                    val e = event as ShopBuyEvent
                    server.pluginManager.callEvent(BukkitShopBuyEvent(e.player.online(server) ?: return, e))
                }
                1 -> {
                    if (BukkitShopSellEvent.getHandlerList().registeredListeners.isEmpty()) return
                    val e = event as ShopSellEvent
                    server.pluginManager.callEvent(BukkitShopSellEvent(e.player.online(server) ?: return, e))
                }
                2 -> {
                    if (BukkitShopBoughtEvent.getHandlerList().registeredListeners.isEmpty()) return
                    val e = event as ShopBoughtEvent
                    server.pluginManager.callEvent(BukkitShopBoughtEvent(e.player.online(server) ?: return, e))
                }
                else -> {
                    if (BukkitShopSoldEvent.getHandlerList().registeredListeners.isEmpty()) return
                    val e = event as ShopSoldEvent
                    server.pluginManager.callEvent(BukkitShopSoldEvent(e.player.online(server) ?: return, e))
                }
            }
        }
    }
}
