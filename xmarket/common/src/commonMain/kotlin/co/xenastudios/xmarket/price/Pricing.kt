package co.xenastudios.xmarket.price

import co.xenastudios.xmarket.api.Money

/**
 * What each item costs at the cheapest, from what can be bought outright ([given]) and every
 * [RecipeBook] recipe: the least of its own price and, for each recipe that makes it, what its
 * ingredients cost (each the cheapest of its options), less what a recipe hands back (a milk
 * bucket's empty bucket), over how many it makes. Worked out to a fixed point, so a chain of
 * recipes (logs to planks to sticks to a ladder) costs what its cheapest way does.
 *
 * Two things use it: the price list's generator, where [given] is the hand-set base prices of
 * what isn't made from anything, so every crafted item's price comes from its recipes; and
 * `/shop audit`, where [given] is every buy price in the shop, so the result is what an item
 * costs a player who buys and crafts, and selling anything for more is a loop to fix ([Audit]).
 *
 * Exact: costs are [Money] units, a share rounded up, so rounding never makes crafting look
 * cheaper than it is.
 */
public object Pricing {
    /** What a recipe that uses an item hands back, by key: buckets and bottles. */
    public val REMAINDERS: Map<String, String> = mapOf(
        "milk_bucket" to "bucket", "water_bucket" to "bucket", "lava_bucket" to "bucket", "powder_snow_bucket" to "bucket",
        "honey_bottle" to "glass_bottle", "dragon_breath" to "glass_bottle",
    )

    /** The cheapest cost of every item [given] or a recipe reaches, in units; items neither prices are missing. */
    public fun costs(given: Map<String, Money>, book: RecipeBook, maxRounds: Int = 64): Map<String, Money> {
        val cost = HashMap<String, Long>(given.size * 2)
        for ((k, v) in given) cost[k] = v.units
        // Bellman-Ford over the recipes: a round lowers what it can; done when a round changes nothing.
        var round = 0
        while (round++ < maxRounds) {
            var changed = false
            for (recipe in book.recipes) {
                val made = made(recipe, cost) ?: continue
                val now = cost[recipe.result]
                if (now == null || made < now) {
                    cost[recipe.result] = made
                    changed = true
                }
            }
            if (!changed) break
        }
        val out = HashMap<String, Money>(cost.size * 2)
        for ((k, v) in cost) out[k] = Money.ofUnits(v)
        return out
    }

    /** What one of [recipe]'s result costs through it, or `null` if an ingredient can't be had. */
    public fun made(recipe: Recipe, cost: Map<String, Long>): Long? {
        var total = 0L
        var self = 0
        for (ingredient in recipe.ingredients) {
            // A recipe that takes its own result (a smithing template copied) makes count - self net.
            if (recipe.result in ingredient.options && ingredient.options.size == 1) {
                self += ingredient.count
                continue
            }
            var cheapest = Long.MAX_VALUE
            for (option in ingredient.options) {
                val c = cost[option] ?: continue
                val back = REMAINDERS[option]?.let { cost[it] } ?: 0
                val net = (c - back).coerceAtLeast(0)
                if (net < cheapest) cheapest = net
            }
            if (cheapest == Long.MAX_VALUE) return null
            total += cheapest * ingredient.count
            if (total < 0) return null
        }
        val net = recipe.count - self
        if (net <= 0) return null
        return (total + net - 1) / net
    }
}

/**
 * The loops a price list allows: an item that sells for more than it costs to get from the
 * shop, by buying it or buying what it's made of ([Pricing.costs] of the buy prices). Any
 * buy-craft-sell profit ends in such a sale, however many steps it takes, so none of these
 * means none at all.
 */
public object Audit {
    /** One item that sells for more than it can be had for, and the cheapest way it's had. */
    public class Loop(public val key: String, public val sell: Money, public val cost: Money, public val via: Recipe?) {
        override fun toString(): String = "$key sells for $sell but costs $cost" + (via?.let { " (as $it)" } ?: " (bought)")
    }

    /**
     * Every loop: [sell] and [buy] are the shop's prices of one of each key, [book] how items are
     * made. Sorted by the profit, the worst first.
     */
    public fun loops(buy: Map<String, Money>, sell: Map<String, Money>, book: RecipeBook): List<Loop> {
        val costs = Pricing.costs(buy, book)
        val units = HashMap<String, Long>(costs.size * 2)
        for ((k, v) in costs) units[k] = v.units
        val out = ArrayList<Loop>()
        for ((key, price) in sell) {
            val cost = costs[key] ?: continue
            if (price <= cost) continue
            // The way it's that cheap: bought, or the recipe that makes it for that.
            val via = book.byResult[key]?.firstOrNull { Pricing.made(it, units) == cost.units }?.takeIf { buy[key]?.let { b -> b > cost } != false }
            out += Loop(key, price, cost, via)
        }
        return out.sortedByDescending { it.sell.units - it.cost.units }
    }
}
