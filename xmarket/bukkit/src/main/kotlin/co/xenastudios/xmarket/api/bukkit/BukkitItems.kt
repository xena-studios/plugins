package co.xenastudios.xmarket.api.bukkit

import co.xenastudios.xmarket.api.ItemKey
import co.xenastudios.xmarket.api.XMarket
import org.bukkit.inventory.ItemStack

/**
 * Bukkit's items as xMarket's API names them, for a plugin pricing or selling Bukkit items:
 *
 * ```java
 * Money each = XMarket.api().sellPrice(BukkitItemKeys.key(stack));
 * ```
 *
 * @since 1.0.0
 */
public object BukkitItemKeys {
    @Volatile
    private var reader: ((ItemStack) -> ItemKey)? = null

    /** [stack] as an [ItemKey]: its id, amount, potion, stored enchantments and whether it's plain. Needs xMarket enabled. */
    @JvmStatic
    public fun key(stack: ItemStack): ItemKey {
        val r = reader ?: throw IllegalStateException("xMarket isn't enabled")
        check(XMarket.isEnabled()) { "xMarket isn't enabled" }
        return r(stack)
    }

    /** Set by xMarket as it's enabled: not for other plugins. */
    @JvmStatic
    public fun register(read: ((ItemStack) -> ItemKey)?) {
        reader = read
    }
}
