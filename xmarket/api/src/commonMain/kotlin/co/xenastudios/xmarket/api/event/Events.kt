package co.xenastudios.xmarket.api.event

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.event.CancellableEvent
import co.xenastudios.core.api.event.Event
import co.xenastudios.core.api.event.EventType
import co.xenastudios.xmarket.api.Money
import co.xenastudios.xmarket.api.ShopEntry
import kotlin.jvm.JvmField

/**
 * A player is about to buy: cancel it to refuse, or change [price] (the total for [amount]).
 * Fired on the player's own thread, after xMarket's checks and before they pay, and only when
 * something listens. On Bukkit it's also `co.xenastudios.xmarket.api.bukkit.BukkitShopBuyEvent`.
 *
 * @since 1.0.0
 */
public class ShopBuyEvent(
    public val player: PlayerRef,
    public val entry: ShopEntry,
    public val amount: Int,
    /** What they'll pay for all of it, in [ShopEntry.currency]. Set it to change the price. */
    public var price: Money,
) : CancellableEvent() {
    public companion object {
        @JvmField
        public val TYPE: EventType<ShopBuyEvent> = EventType.of("xmarket:buy")
    }
}

/**
 * A player is about to sell [amount] of [entry] (one event per entry in a sale of many): cancel
 * it to keep those items out of the sale, or change [price] (the total). On the player's own
 * thread, before they're paid. On Bukkit it's also `BukkitShopSellEvent`.
 *
 * @since 1.0.0
 */
public class ShopSellEvent(
    public val player: PlayerRef,
    public val entry: ShopEntry,
    public val amount: Int,
    /** What they'll be paid for all of it. Set it to change the price. */
    public var price: Money,
) : CancellableEvent() {
    public companion object {
        @JvmField
        public val TYPE: EventType<ShopSellEvent> = EventType.of("xmarket:sell")
    }
}

/** A player bought [amount] of [entry] and paid [paid]. On the player's thread. On Bukkit also `BukkitShopBoughtEvent`. @since 1.0.0 */
public class ShopBoughtEvent(
    public val player: PlayerRef,
    public val entry: ShopEntry,
    public val amount: Int,
    public val paid: Money,
) : Event {
    public companion object {
        @JvmField
        public val TYPE: EventType<ShopBoughtEvent> = EventType.of("xmarket:bought")
    }
}

/** A player sold [amount] of [entry] and was paid [paid]. On the player's thread. On Bukkit also `BukkitShopSoldEvent`. @since 1.0.0 */
public class ShopSoldEvent(
    public val player: PlayerRef,
    public val entry: ShopEntry,
    public val amount: Int,
    public val paid: Money,
) : Event {
    public companion object {
        @JvmField
        public val TYPE: EventType<ShopSoldEvent> = EventType.of("xmarket:sold")
    }
}
