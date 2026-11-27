package co.xenastudios.xmarket.api

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.event.EventBus
import kotlin.concurrent.Volatile
import kotlin.jvm.JvmStatic

/**
 * xMarket's developer API, the same on Paper, Folia, Spigot and Fabric: prices from memory, the
 * shop's entries, purchases and sales with every rule xMarket applies (permissions, limits,
 * discounts and boosts, the events), price modifiers and providers, and the events.
 *
 * Get it with [XMarket.api]. On Bukkit it's also in the services manager while xMarket is enabled.
 * Players are by [PlayerRef]: from Java, `PlayerRefs.of(player.getUniqueId())`.
 *
 * Prices are answered from the price list in memory, on any thread, and are `null` until it's
 * loaded (just after the server starts) or for an item it doesn't price. Trades are [Pending]
 * (a `CompletableFuture` on the JVM): they run on the player's own thread, one at a time per
 * player, and settle once the currency has answered.
 *
 * @since 1.0.0
 */
public interface XMarketApi {
    /** The running xMarket's version, such as `1.0.0`. */
    public val version: String

    /**
     * The shop's events: [co.xenastudios.xmarket.api.event.ShopBuyEvent] and
     * [co.xenastudios.xmarket.api.event.ShopSellEvent] (cancellable, price changeable) before a
     * trade, [co.xenastudios.xmarket.api.event.ShopBoughtEvent] and
     * [co.xenastudios.xmarket.api.event.ShopSoldEvent] after. On Bukkit each is also a Bukkit
     * event in `co.xenastudios.xmarket.api.bukkit`.
     */
    public val events: EventBus

    /** Whether the price list is loaded, so prices answer. */
    public val isLoaded: Boolean

    /** The price of one [item] to buy, before anyone's discount; `null` if it can't be bought. */
    public fun buyPrice(item: ItemKey): Money?

    /** What one [item] sells for, before anyone's boost; `null` if it can't be sold. */
    public fun sellPrice(item: ItemKey): Money?

    /** What one [item] sells for to [player], with their sell boost and every [PriceModifier]. */
    public fun sellPrice(player: PlayerRef, item: ItemKey): Money?

    /** Whether [item] can be sold at all. */
    public fun isSellable(item: ItemKey): Boolean

    /** The entry [item] buys or sells as, or `null`. */
    public fun entry(item: ItemKey): ShopEntry?

    /** The entry [id] of [category], or `null`. */
    public fun entry(category: String, id: String): ShopEntry?

    /** Every entry, category by category. */
    public fun entries(): List<ShopEntry>

    /**
     * Sells [items] for [player], who must be online: the caller has already taken them from
     * wherever they were (a sell chest, a sell wand's container), and gives back whatever the
     * result says wasn't sold. Every rule applies; the player is paid, and the sale recorded.
     */
    public fun sell(player: PlayerRef, items: List<ItemKey>): Pending<SaleResult>

    /** Buys [amount] of [entry] for [player], who must be online, exactly as a click in `/shop` does. */
    public fun buy(player: PlayerRef, entry: ShopEntry, amount: Int): Pending<PurchaseResult>

    /** Applies [modifier] to every price from now on, after xMarket's own. [owner] names it in the console. */
    public fun registerPriceModifier(owner: String, modifier: PriceModifier)

    /** Asks [provider] for items the price list doesn't price. */
    public fun registerPriceProvider(owner: String, provider: PriceProvider)

    /** Takes away every modifier and provider [owner] registered. */
    public fun unregisterAll(owner: String)
}

/**
 * Where xMarket's API is found, on every platform:
 *
 * ```java
 * XMarketApi api = XMarket.api();
 * ```
 *
 * @since 1.0.0
 */
public object XMarket {
    @Volatile
    private var running: XMarketApi? = null

    /**
     * xMarket's API.
     *
     * @throws IllegalStateException if xMarket isn't installed and enabled
     */
    @JvmStatic
    public fun api(): XMarketApi =
        running ?: throw IllegalStateException("xMarket isn't enabled. Add it to depend (or softdepend and check XMarket.isEnabled() first).")

    /** Whether xMarket is enabled, so [api] answers. */
    @JvmStatic
    public fun isEnabled(): Boolean = running != null

    /** Made available by xMarket as it's enabled, and taken away as it's disabled: not for other plugins. */
    @JvmStatic
    public fun register(api: XMarketApi?) {
        running = api
    }
}
