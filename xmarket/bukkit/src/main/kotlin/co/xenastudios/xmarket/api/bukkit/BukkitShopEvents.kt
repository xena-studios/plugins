package co.xenastudios.xmarket.api.bukkit

import co.xenastudios.xmarket.api.Money
import co.xenastudios.xmarket.api.ShopEntry
import co.xenastudios.xmarket.api.event.ShopBoughtEvent
import co.xenastudios.xmarket.api.event.ShopBuyEvent
import co.xenastudios.xmarket.api.event.ShopSellEvent
import co.xenastudios.xmarket.api.event.ShopSoldEvent
import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.HandlerList
import org.bukkit.event.player.PlayerEvent

/**
 * [ShopBuyEvent] as a Bukkit event, for plugins and Skript scripts listening the Bukkit way: a
 * player is about to buy. Cancelling it or changing the price changes xMarket's own event. On
 * the player's thread.
 *
 * @since 1.0.0
 */
public class BukkitShopBuyEvent(player: Player, private val event: ShopBuyEvent) : PlayerEvent(player), Cancellable {
    public fun getEntry(): ShopEntry = event.entry
    public fun getAmount(): Int = event.amount
    public fun getPrice(): Money = event.price
    public fun setPrice(price: Money) {
        event.price = price
    }
    public fun getEvent(): ShopBuyEvent = event
    override fun isCancelled(): Boolean = event.isCancelled
    override fun setCancelled(cancel: Boolean) {
        event.isCancelled = cancel
    }
    override fun getHandlers(): HandlerList = HANDLERS

    public companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        public fun getHandlerList(): HandlerList = HANDLERS
    }
}

/** [ShopSellEvent] as a Bukkit event: a player is about to sell. Cancellable, the price changeable. @since 1.0.0 */
public class BukkitShopSellEvent(player: Player, private val event: ShopSellEvent) : PlayerEvent(player), Cancellable {
    public fun getEntry(): ShopEntry = event.entry
    public fun getAmount(): Int = event.amount
    public fun getPrice(): Money = event.price
    public fun setPrice(price: Money) {
        event.price = price
    }
    public fun getEvent(): ShopSellEvent = event
    override fun isCancelled(): Boolean = event.isCancelled
    override fun setCancelled(cancel: Boolean) {
        event.isCancelled = cancel
    }
    override fun getHandlers(): HandlerList = HANDLERS

    public companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        public fun getHandlerList(): HandlerList = HANDLERS
    }
}

/** [ShopBoughtEvent] as a Bukkit event: a player bought and paid. @since 1.0.0 */
public class BukkitShopBoughtEvent(player: Player, private val event: ShopBoughtEvent) : PlayerEvent(player) {
    public fun getEntry(): ShopEntry = event.entry
    public fun getAmount(): Int = event.amount
    public fun getPaid(): Money = event.paid
    public fun getEvent(): ShopBoughtEvent = event
    override fun getHandlers(): HandlerList = HANDLERS

    public companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        public fun getHandlerList(): HandlerList = HANDLERS
    }
}

/** [ShopSoldEvent] as a Bukkit event: a player sold and was paid. @since 1.0.0 */
public class BukkitShopSoldEvent(player: Player, private val event: ShopSoldEvent) : PlayerEvent(player) {
    public fun getEntry(): ShopEntry = event.entry
    public fun getAmount(): Int = event.amount
    public fun getPaid(): Money = event.paid
    public fun getEvent(): ShopSoldEvent = event
    override fun getHandlers(): HandlerList = HANDLERS

    public companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        public fun getHandlerList(): HandlerList = HANDLERS
    }
}
