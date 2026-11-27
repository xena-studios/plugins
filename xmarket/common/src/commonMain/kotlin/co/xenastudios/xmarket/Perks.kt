package co.xenastudios.xmarket

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.concurrentMap
import co.xenastudios.xmarket.config.XMarketConfig

/**
 * Each player's discount and sell boost: the highest `xmarket.discount.<n>` and
 * `xmarket.sellboost.<n>` they have (1 to 100). Working it out asks their permissions a hundred
 * times, so it's kept for 30 seconds: there's no event for a permission changing, so that short
 * time lets a rank change show. Forgotten as they leave, and all of it on reload.
 */
public class Perks internal constructor(private val plugin: XMarketPlugin) {
    private class Known(val discount: Int, val boost: Int, val at: Long)

    private val known = concurrentMap<PlayerRef, Known>()

    public fun discount(player: PlayerRef): Int = of(player)?.discount ?: 0

    public fun boost(player: PlayerRef): Int = of(player)?.boost ?: 0

    private fun of(player: PlayerRef): Known? {
        if (!plugin.config[XMarketConfig.rules.discounts]) return null
        val last = known[player]
        val now = plugin.now()
        if (last != null && now - last.at < FRESH_MILLIS) return last
        val server = plugin.server
        var discount = 0
        var boost = 0
        for (n in 100 downTo 1) {
            if (discount == 0 && server.hasExplicit(player, DISCOUNT + n)) discount = n
            if (boost == 0 && server.hasExplicit(player, BOOST + n)) boost = n
            if (discount != 0 && boost != 0) break
        }
        return Known(discount, boost, now).also { known[player] = it }
    }

    public fun forget(player: PlayerRef) {
        known.remove(player)
    }

    public fun clear() {
        known.clear()
    }

    public companion object {
        public const val DISCOUNT: String = "xmarket.discount."
        public const val BOOST: String = "xmarket.sellboost."
        private const val FRESH_MILLIS = 30_000L
    }
}
