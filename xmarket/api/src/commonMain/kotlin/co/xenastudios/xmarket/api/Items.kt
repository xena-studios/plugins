package co.xenastudios.xmarket.api

import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic

/**
 * An item as xMarket prices it, the same on every platform: its id as Minecraft names it
 * (`minecraft:diamond`), how many, and what about it can change its price: a potion's type, an
 * enchanted book's stored enchantments, an item's own enchantments, and whether it's a plain
 * item or something more (a custom name or model, lore, another plugin's item), which prices
 * only as an entry made from that very item.
 *
 * On Bukkit, `co.xenastudios.xmarket.api.bukkit.BukkitItemKeys.key(itemStack)` makes one.
 *
 * @since 1.0.0
 */
public class ItemKey @JvmOverloads constructor(
    /** The namespaced id: `minecraft:diamond`, or another mod's. */
    public val id: String,
    public val amount: Int = 1,
    /** A potion's (or tipped arrow's) type as Minecraft names it, `long_swiftness`; `null` for none. */
    public val potion: String? = null,
    /** An enchanted book's stored enchantments, or an item's own, by id (`minecraft:sharpness`) and level. */
    public val enchantments: Map<String, Int> = emptyMap(),
    /** Whether it has nothing a player or plugin added: no custom name, lore or model. */
    public val plain: Boolean = true,
    /** The platform's full text of the item, for entries made from an item; `null` if unknown. */
    public val data: String? = null,
) {
    /** The same item, [amount] of it. */
    public fun withAmount(amount: Int): ItemKey = ItemKey(id, amount, potion, enchantments, plain, data)

    override fun toString(): String = buildString {
        append(id)
        if (potion != null) append("[potion=").append(potion).append(']')
        if (enchantments.isNotEmpty()) append(enchantments.entries.joinToString(",", "[", "]") { "${it.key}=${it.value}" })
        if (!plain) append("[custom]")
        append(" x").append(amount)
    }

    public companion object {
        /** [id] with `minecraft:` in front of it if it has no namespace. */
        @JvmStatic
        public fun namespaced(id: String): String = if (':' in id) id.lowercase() else "minecraft:" + id.lowercase()

        /** A plain item of [id], one of it. */
        @JvmStatic
        public fun of(id: String): ItemKey = ItemKey(namespaced(id))
    }
}

/** Buying from the shop, or selling to it. @since 1.0.0 */
public enum class TradeKind { BUY, SELL }

/**
 * One of the shop's entries: what's sold at a slot of a category, its prices before anyone's
 * discount, and what it gives.
 *
 * @since 1.0.0
 */
public class ShopEntry(
    /** The category's id: its file name, `ores`. */
    public val category: String,
    /** The entry's id within the category: the item's id, or what an owner named it. */
    public val id: String,
    /** The item it gives or takes (for a command entry, its icon). */
    public val item: ItemKey,
    /** The price of one to buy, or `null` if it can't be bought. */
    public val buyPrice: Money?,
    /** What one sells for, or `null` if it can't be sold. */
    public val sellPrice: Money?,
    /** The currency's id: `money`, `xp`, or `item:minecraft:diamond`. */
    public val currency: String,
    /** Whether buying it runs commands rather than giving the item. */
    public val commands: Boolean,
) {
    override fun toString(): String = "$category/$id"
}

/** What a purchase came to. @since 1.0.0 */
public class PurchaseResult(
    public val status: Status,
    /** How many were bought: fewer than asked when the inventory had less room. */
    public val amount: Int,
    /** What they paid, after any refund. */
    public val paid: Money,
) {
    public val isSuccess: Boolean get() = status == Status.SUCCESS

    public enum class Status {
        SUCCESS,
        /** No such entry, or it can't be bought. */
        NOT_FOR_SALE,
        /** The player lacks its permission or requirements. */
        NOT_ALLOWED,
        /** A buy limit is used up for now. */
        LIMIT_REACHED,
        CANT_AFFORD,
        /** No room for even one. */
        NO_ROOM,
        /** A listener cancelled it. */
        CANCELLED,
        /** The player isn't online, another of their trades is under way, the shop isn't loaded, or the currency failed. */
        UNAVAILABLE,
    }

    override fun toString(): String = "PurchaseResult($status, $amount, $paid)"
}

/** What a sale came to: which of the items offered were sold, and what for. @since 1.0.0 */
public class SaleResult(
    public val status: Status,
    /** For each item offered, in order, how many of it were sold (0 for one that wasn't). */
    public val sold: List<Int>,
    /** What the player was paid, by currency id. */
    public val paid: Map<String, Money>,
) {
    public val isSuccess: Boolean get() = status == Status.SUCCESS

    public enum class Status {
        /** At least one item was sold, and paid for. */
        SUCCESS,
        /** Nothing offered sells here (no price, a sell limit used up, no permission). */
        NOTHING_SELLABLE,
        CANCELLED,
        /** The player isn't online, another trade is under way, the shop isn't loaded, or the payment failed: nothing sold. */
        UNAVAILABLE,
    }

    override fun toString(): String = "SaleResult($status, $sold, $paid)"
}

/**
 * Changes every price a player pays or is paid, after xMarket's own discounts and boosts: a
 * booster, an event weekend. Asked from memory on the trade's thread; keep it quick.
 *
 * @since 1.0.0
 */
public fun interface PriceModifier {
    /** The price of one of [entry] for [player] (`null` for a price asked about no one in particular), from [price]. */
    public fun modify(player: co.xenastudios.core.api.PlayerRef?, entry: ShopEntry, kind: TradeKind, price: Money): Money
}

/**
 * Prices items the price list doesn't: another plugin's items, by its own rules. Asked only for
 * an item no entry matches, from memory, on any thread. A provided price can be sold (in the
 * default currency) but not bought.
 *
 * @since 1.0.0
 */
public fun interface PriceProvider {
    /** What one [item] sells for, or `null` for an item it doesn't price. */
    public fun sellPrice(item: ItemKey): Money?
}
