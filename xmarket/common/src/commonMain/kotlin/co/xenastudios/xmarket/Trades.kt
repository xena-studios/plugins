package co.xenastudios.xmarket

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.newPending
import co.xenastudios.core.api.onSettled
import co.xenastudios.core.api.pendingOf
import co.xenastudios.core.api.resolve
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.concurrentMap
import co.xenastudios.core.common.i18n.MessageKey
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.scheduler.now
import co.xenastudios.xmarket.api.ItemKey
import co.xenastudios.xmarket.api.Money
import co.xenastudios.xmarket.api.PurchaseResult
import co.xenastudios.xmarket.api.SaleResult
import co.xenastudios.xmarket.api.TradeKind
import co.xenastudios.xmarket.api.event.ShopBoughtEvent
import co.xenastudios.xmarket.api.event.ShopBuyEvent
import co.xenastudios.xmarket.api.event.ShopSellEvent
import co.xenastudios.xmarket.api.event.ShopSoldEvent
import co.xenastudios.xmarket.config.XMarketConfig
import co.xenastudios.xmarket.price.Currency
import co.xenastudios.xmarket.price.PriceList
import co.xenastudios.xmarket.price.Period
import co.xenastudios.xmarket.store.Store

/**
 * Every purchase and sale, the menus', the commands' and the API's, the one way:
 *
 * - **On the player's own thread, one at a time per player.** A second trade while one is under
 *   way is refused, so two clicks in one tick can't both spend the same money or items.
 * - **Buying: the money first, then the items,** made only once the currency has taken the
 *   price; what doesn't fit (the inventory filled up meanwhile) is refunded. A player who leaves
 *   while the currency answers gets their money back.
 * - **Selling: the items first, then the money.** The items are taken (or were already out of
 *   the inventory, in `/sellgui`); if a currency refuses to pay, its items go back.
 * - Prices are exact, a discount rounded up and a boost down; an item or level currency pays
 *   whole numbers, rounded the same way (up for buying, down for selling).
 */
public class Trades internal constructor(private val plugin: XMarketPlugin) {
    private val busy = concurrentMap<PlayerRef, Boolean>()

    private val server: MarketServer get() = plugin.server
    private val texts: Texts get() = plugin.texts

    // ---- prices ----

    /** The price of one of [placed] to buy for [player] (or no one in particular): after their discount and every modifier. */
    public fun unitBuy(player: PlayerRef?, placed: PriceList.Placed): Money? {
        val base = placed.entry.buy ?: return null
        val discount = if (player == null) 0 else plugin.perks.discount(player)
        val price = if (discount > 0) base.percentUp(100L - discount) else base
        return plugin.modify(player, placed, TradeKind.BUY, price)
    }

    /** What one of [placed] sells for to [player]: with their sell boost and every modifier. */
    public fun unitSell(player: PlayerRef?, placed: PriceList.Placed): Money? {
        val base = placed.entry.sell ?: return null
        val boost = if (player == null) 0 else plugin.perks.boost(player)
        val price = if (boost > 0) base.percent(100L + boost) else base
        return plugin.modify(player, placed, TradeKind.SELL, price)
    }

    /** [count] at [unit], as [currency] pays it: whole items or levels rounded up when buying, down when selling. */
    public fun total(currency: Currency, unit: Money, count: Int, buy: Boolean): Money {
        val exact = unit.times(count.toLong())
        if (!currency.whole) return exact
        return Money.of(if (buy) exact.wholeUp else exact.whole)
    }

    /** What stops [player] trading [placed] for [kind] (no permission, a requirement), as a message; `null` if nothing. */
    internal fun refusal(player: PlayerRef, placed: PriceList.Placed): Pair<MessageKey, Array<Pair<String, Any>>>? {
        val c = placed.category
        val e = placed.entry
        if (c.permission != null && !server.hasPermission(player, c.permission)) return XMarketMessages.notAllowed to emptyArray()
        if (e.permission != null && !server.hasPermission(player, e.permission)) return XMarketMessages.notAllowed to emptyArray()
        if (plugin.config[XMarketConfig.rules.requirements]) {
            for (r in c.requires + e.requires) {
                if (!r.test(server.placeholders(player, r.placeholder))) return XMarketMessages.requirement to arrayOf("requirement" to r.toString())
            }
        }
        return null
    }

    /** Whether [player] may see [placed] in a menu: they have its permissions. Requirements are checked as they trade. */
    internal fun visible(player: PlayerRef, placed: PriceList.Placed): Boolean {
        val c = placed.category.permission
        val e = placed.entry.permission
        return (c == null || server.hasPermission(player, c)) && (e == null || server.hasPermission(player, e))
    }

    internal fun key(placed: PriceList.Placed): String = "${placed.category.id}/${placed.entry.id}"

    internal fun periodWord(period: Period): MessageKey = when (period) {
        Period.DAY -> XMarketMessages.periodDay
        Period.WEEK -> XMarketMessages.periodWeek
        Period.EVER -> XMarketMessages.periodEver
    }

    // ---- buying ----

    /**
     * Buys [wanted] of [placed] for online [player], on their thread. [tell] says whether they're
     * told how it went (the API's callers tell their own).
     */
    public fun buy(player: PlayerRef, placed: PriceList.Placed, wanted: Int, tell: Boolean = true): Pending<PurchaseResult> {
        if (!server.isOnline(player) || !plugin.ready) return pendingOf(PurchaseResult(PurchaseResult.Status.UNAVAILABLE, 0, Money.ZERO)).also { if (tell) texts.send(player, XMarketMessages.loading) }
        if (busy.put(player, true) != null) {
            if (tell) texts.send(player, XMarketMessages.busy)
            return pendingOf(PurchaseResult(PurchaseResult.Status.UNAVAILABLE, 0, Money.ZERO))
        }
        val result = newPending<PurchaseResult>()
        val done = { status: PurchaseResult.Status, amount: Int, paid: Money ->
            busy.remove(player)
            result.resolve(PurchaseResult(status, amount, paid))
            Unit
        }
        try {
            startBuy(player, placed, wanted, tell, done)
        } catch (e: Exception) {
            plugin.log.error("A purchase of ${key(placed)} for $player failed: $e")
            done(PurchaseResult.Status.UNAVAILABLE, 0, Money.ZERO)
        }
        return result
    }

    private fun startBuy(player: PlayerRef, placed: PriceList.Placed, wanted: Int, tell: Boolean, done: (PurchaseResult.Status, Int, Money) -> Unit) {
        val entry = placed.entry
        val name = displayName(placed)
        fun no(status: PurchaseResult.Status, message: MessageKey, vararg p: Pair<String, Any>) {
            if (tell) texts.send(player, message, *p)
            done(status, 0, Money.ZERO)
        }
        val unit = unitBuy(player, placed) ?: return no(PurchaseResult.Status.NOT_FOR_SALE, XMarketMessages.notBuyable, "item" to name)
        refusal(player, placed)?.let { (message, p) -> return no(PurchaseResult.Status.NOT_ALLOWED, message, *p) }
        var n = wanted.coerceIn(1, MAX_AMOUNT)
        val limitKey = key(placed)
        for (l in entry.limits) {
            if (l.kind != TradeKind.BUY) continue
            val left = plugin.limits.left(player, limitKey, l)
            if (left <= 0) {
                return if (l.server) no(PurchaseResult.Status.LIMIT_REACHED, XMarketMessages.serverLimit, "item" to name, "period" to periodWord(l.period))
                else no(PurchaseResult.Status.LIMIT_REACHED, XMarketMessages.limitReached, "limit" to l.amount, "item" to name, "period" to periodWord(l.period))
            }
            n = minOf(n, left)
        }
        val command = entry.commands.isNotEmpty()
        if (!command) {
            val room = server.room(player, entry.item, n * entry.amount) / entry.amount
            if (room <= 0) return no(PurchaseResult.Status.NO_ROOM, XMarketMessages.noRoom)
            n = minOf(n, room)
        }
        val currency = placed.currency
        var total = total(currency, unit, n, buy = true)
        if (plugin.bus.hasListeners(ShopBuyEvent.TYPE)) {
            plugin.eventsReady()
            val event = ShopBuyEvent(player, placed.api, n, total)
            plugin.bus.fire(ShopBuyEvent.TYPE, event)
            if (event.isCancelled) return done(PurchaseResult.Status.CANCELLED, 0, Money.ZERO)
            total = if (event.price.isNegative) Money.ZERO else event.price
        }
        val wallet = plugin.wallets
        when (wallet.has(player, currency, total)) {
            false -> return no(PurchaseResult.Status.CANT_AFFORD, XMarketMessages.cantAfford, "price" to texts.price(player, total, currency))
            null -> return no(PurchaseResult.Status.UNAVAILABLE, XMarketMessages.noEconomy)
            true -> {}
        }
        val count = n
        val paid = total
        wallet.take(player, currency, paid, reason(TradeKind.BUY, count * entry.amount, placed)).onSettled { ok, _ ->
            plugin.platform.scheduler.now(Where.on(player), retired = {
                // They left as it was paid: the money back, nothing given.
                if (ok == true) wallet.give(player, currency, paid, "refund")
                done(PurchaseResult.Status.UNAVAILABLE, 0, Money.ZERO)
            }) {
                if (ok != true) {
                    no(PurchaseResult.Status.CANT_AFFORD, if (ok == null) XMarketMessages.failed else XMarketMessages.cantAfford, "price" to texts.price(player, paid, currency))
                } else {
                    delivered(player, placed, count, unit, paid, tell, done)
                }
            }
        }
    }

    private fun delivered(player: PlayerRef, placed: PriceList.Placed, count: Int, unit: Money, paid: Money, tell: Boolean, done: (PurchaseResult.Status, Int, Money) -> Unit) {
        val entry = placed.entry
        val currency = placed.currency
        var given = count
        var cost = paid
        if (entry.commands.isNotEmpty()) {
            val name = server.name(player) ?: player.toString()
            repeat(count) {
                for (command in entry.commands) server.console(command.replace("{player}", name).replace("{uuid}", player.toString()).removePrefix("/"))
            }
        } else {
            val items = server.giveNew(player, entry.item, count * entry.amount)
            given = items / entry.amount
            if (given < count) {
                val keep = if (given == 0) Money.ZERO else total(currency, unit, given, buy = true).coerceAtMost(paid)
                val refund = paid - keep
                if (!refund.isZero) plugin.wallets.give(player, currency, refund, "refund")
                cost = keep
                if (given == 0) {
                    if (tell) texts.send(player, XMarketMessages.noRoom)
                    return done(PurchaseResult.Status.NO_ROOM, 0, Money.ZERO)
                }
                if (tell) {
                    texts.send(
                        player, XMarketMessages.boughtPartly, "amount" to given * entry.amount, "item" to displayName(placed),
                        "price" to texts.price(player, cost, currency), "refund" to texts.price(player, refund, currency),
                    )
                }
            }
        }
        plugin.limits.add(player, key(placed), entry.limits, TradeKind.BUY, given)
        record(player, true, placed, given * entry.amount, cost)
        if (plugin.bus.hasListeners(ShopBoughtEvent.TYPE)) plugin.bus.fire(ShopBoughtEvent.TYPE, ShopBoughtEvent(player, placed.api, given, cost))
        if (tell && given == count) {
            if (entry.commands.isNotEmpty()) texts.send(player, XMarketMessages.boughtCommand, "item" to displayName(placed), "price" to texts.price(player, cost, currency))
            else texts.send(player, XMarketMessages.bought, "amount" to given * entry.amount, "item" to displayName(placed), "price" to texts.price(player, cost, currency))
        }
        done(PurchaseResult.Status.SUCCESS, given, cost)
    }

    // ---- selling ----

    /** An item offered for sale: what it is, and where it is (an inventory slot, or out of it already as [native]). */
    public class Offer(public val item: ItemKey, public val slot: Int = -1, public val native: Any? = null)

    /** How a sale was asked for, which decides what's said. */
    public enum class Source { HAND, ALL, ITEM, GUI, API }

    // One line of a planned sale: which offer, as which entry, how many items, for what.
    private class Line(val offer: Int, val placed: PriceList.Placed, val items: Int, val units: Int, val total: Money)

    /**
     * Sells [offers] for online [player], on their thread. Offers with a [Offer.slot] are taken
     * from the inventory now; ones with a [Offer.native] are out of it already, and what isn't
     * sold of them goes back; API offers (neither) are paid for and the caller gives back the rest.
     */
    public fun sell(player: PlayerRef, offers: List<Offer>, source: Source, tell: Boolean = source != Source.API): Pending<SaleResult> {
        val result = newPending<SaleResult>()
        val giveBack = { offers.mapNotNull { it.native }.let { if (it.isNotEmpty()) server.give(player, it) } }
        if (!server.isOnline(player) || !plugin.ready) {
            if (server.isOnline(player)) giveBack()
            if (tell) texts.send(player, XMarketMessages.loading)
            return pendingOf(SaleResult(SaleResult.Status.UNAVAILABLE, offers.map { 0 }, emptyMap()))
        }
        if (busy.put(player, true) != null) {
            giveBack()
            if (tell) texts.send(player, XMarketMessages.busy)
            return pendingOf(SaleResult(SaleResult.Status.UNAVAILABLE, offers.map { 0 }, emptyMap()))
        }
        val done = { r: SaleResult ->
            busy.remove(player)
            result.resolve(r)
            Unit
        }
        try {
            startSale(player, offers, source, tell, done)
        } catch (e: Exception) {
            plugin.log.error("A sale for $player failed: $e")
            giveBack()
            done(SaleResult(SaleResult.Status.UNAVAILABLE, offers.map { 0 }, emptyMap()))
        }
        return result
    }

    private fun startSale(player: PlayerRef, offers: List<Offer>, source: Source, tell: Boolean, done: (SaleResult) -> Unit) {
        val prices = plugin.prices
        // Limits are shared by every line of an entry in this sale.
        val left = HashMap<String, Int>()
        val lines = ArrayList<Line>()
        var refused: Pair<MessageKey, Array<Pair<String, Any>>>? = null
        for ((index, offer) in offers.withIndex()) {
            val placed = prices.find(offer.item, TradeKind.SELL) ?: plugin.provided(offer.item) ?: continue
            val no = refusal(player, placed)
            if (no != null) {
                refused = no
                continue
            }
            val unit = unitSell(player, placed) ?: continue
            val k = key(placed)
            var units = offer.item.amount / placed.entry.amount
            val allowed = left.getOrPut(k) { plugin.limits.left(player, k, placed.entry.limits, TradeKind.SELL) ?: Int.MAX_VALUE }
            units = minOf(units, allowed)
            if (units <= 0) continue
            left[k] = allowed - units
            lines += Line(index, placed, units * placed.entry.amount, units, unit.times(units.toLong()))
        }
        // Events, per line: a cancelled one is left out; a changed price is kept.
        if (plugin.bus.hasListeners(ShopSellEvent.TYPE)) {
            plugin.eventsReady()
            val kept = ArrayList<Line>()
            for (line in lines) {
                val event = ShopSellEvent(player, line.placed.api, line.units, line.total)
                plugin.bus.fire(ShopSellEvent.TYPE, event)
                if (!event.isCancelled) kept += Line(line.offer, line.placed, line.items, line.units, if (event.price.isNegative) Money.ZERO else event.price)
            }
            lines.clear()
            lines += kept
        }
        if (lines.isEmpty()) {
            offers.mapNotNull { it.native }.takeIf { it.isNotEmpty() }?.let { server.give(player, it) }
            if (tell) {
                val r = refused
                when {
                    r != null -> texts.send(player, r.first, *r.second)
                    source == Source.HAND || source == Source.ITEM -> texts.send(player, XMarketMessages.notSellable, "item" to Texts.itemName(offers.firstOrNull()?.item?.id ?: "?"))
                    else -> texts.send(player, XMarketMessages.nothingToSell)
                }
            }
            return done(SaleResult(SaleResult.Status.NOTHING_SELLABLE, offers.map { 0 }, emptyMap()))
        }
        // The items out first: from the inventory, or (a deposit's) what isn't sold back now.
        val sold = IntArray(offers.size)
        for (line in lines) sold[line.offer] += line.items
        val takenByOffer = HashMap<Int, Any>()
        val inventory = offers.withIndex().filter { it.value.slot >= 0 && sold[it.index] > 0 }
        if (inventory.isNotEmpty()) {
            val taken = server.take(player, inventory.map { Take(it.value.slot, it.value.item, sold[it.index]) })
            if (taken == null) {
                if (tell) texts.send(player, XMarketMessages.failed)
                return done(SaleResult(SaleResult.Status.UNAVAILABLE, offers.map { 0 }, emptyMap()))
            }
            for ((i, o) in inventory.withIndex()) takenByOffer[o.index] = taken[i]
        }
        val back = ArrayList<Any>()
        var returnedCount = 0
        for ((index, offer) in offers.withIndex()) {
            val native = offer.native ?: continue
            val rest = offer.item.amount - sold[index]
            if (rest > 0) {
                back += if (sold[index] == 0) native else server.copy(native, rest)
                returnedCount += rest
            }
            if (sold[index] > 0) takenByOffer[index] = if (rest == 0) native else server.copy(native, sold[index])
        }
        if (back.isNotEmpty()) server.give(player, back)
        if (tell && returnedCount > 0) texts.send(player, XMarketMessages.returned, "count" to returnedCount)
        pay(player, offers, lines, takenByOffer, source, tell, done)
    }

    private fun pay(
        player: PlayerRef, offers: List<Offer>, lines: List<Line>, taken: Map<Int, Any>, source: Source, tell: Boolean,
        done: (SaleResult) -> Unit,
    ) {
        val byCurrency = lines.groupBy { it.placed.currency }.entries.toList()
        val paid = LinkedHashMap<String, Money>()
        val sold = IntArray(offers.size)
        val wallet = plugin.wallets
        val sellerName = lines.singleOrNull()?.let { reason(TradeKind.SELL, it.items, it.placed) } ?: "xMarket: ${lines.sumOf { it.items }} items"
        var step = 0
        fun next() {
            if (step == byCurrency.size) return finish(player, offers, lines, sold, paid, source, tell, done)
            val (currency, group) = byCurrency[step++]
            val exact = group.fold(Money.ZERO) { a, l -> a + l.total }
            val total = if (currency.whole) Money.of(exact.whole) else exact
            wallet.give(player, currency, total, sellerName).onSettled { ok, _ ->
                plugin.platform.scheduler.now(Where.on(player), retired = {
                    // They left while it paid: paid is paid; the items are already gone from them.
                    done(SaleResult(SaleResult.Status.UNAVAILABLE, sold.toList(), paid))
                }) {
                    if (ok == true) {
                        paid[currency.id] = (paid[currency.id] ?: Money.ZERO) + total
                        for (l in group) sold[l.offer] += l.items
                    } else {
                        // Not paid: that currency's items back, as they were.
                        val back = group.mapNotNull { l -> taken[l.offer] }.distinct()
                        if (back.isNotEmpty()) server.give(player, back)
                        if (tell) texts.send(player, if (ok == null && currency is Currency.Money && plugin.economy() == null) XMarketMessages.noEconomy else XMarketMessages.failed)
                    }
                    next()
                }
            }
        }
        next()
    }

    private fun finish(
        player: PlayerRef, offers: List<Offer>, lines: List<Line>, sold: IntArray, paid: Map<String, Money>, source: Source, tell: Boolean,
        done: (SaleResult) -> Unit,
    ) {
        if (paid.isEmpty()) return done(SaleResult(SaleResult.Status.UNAVAILABLE, sold.toList(), paid))
        val events = plugin.bus.hasListeners(ShopSoldEvent.TYPE)
        for (line in lines) {
            if (sold[line.offer] == 0 || paid[line.placed.currency.id] == null) continue
            plugin.limits.add(player, key(line.placed), line.placed.entry.limits, TradeKind.SELL, line.units)
            record(player, false, line.placed, line.items, line.total)
            if (events) plugin.bus.fire(ShopSoldEvent.TYPE, ShopSoldEvent(player, line.placed.api, line.units, line.total))
        }
        if (tell) {
            val prices = paid.entries.joinToString(", ") { (id, m) -> texts.price(player, m, Currency.of(id) ?: Currency.Money) }
            val counted = lines.filter { sold[it.offer] > 0 }
            val single = counted.map { it.placed.entry.id + "/" + it.placed.category.id }.distinct().size == 1
            if (single && (source == Source.HAND || source == Source.ITEM || counted.size == 1)) {
                texts.send(player, XMarketMessages.sold, "amount" to counted.sumOf { it.items }, "item" to displayName(counted.first().placed), "price" to prices)
            } else {
                texts.send(player, XMarketMessages.soldMany, "count" to counted.sumOf { it.items }, "price" to prices)
            }
        }
        done(SaleResult(SaleResult.Status.SUCCESS, sold.toList(), paid))
    }

    // ---- shared ----

    private fun record(player: PlayerRef, buy: Boolean, placed: PriceList.Placed, items: Int, total: Money) {
        if (!plugin.config[XMarketConfig.shop.history]) return
        val store = plugin.store ?: return
        store.record(Store.Trade(plugin.now(), player, buy, placed.category.id, placed.entry.id, placed.entry.item.key, items, placed.currency.id, total.units))
            .onSettled { r, e -> if (r !is Outcome.Ok) plugin.log.warn("Couldn't record a trade for $player: ${(r as? Outcome.Err)?.error ?: e}") }
    }

    /** How the currency's ledger names it: `xMarket: bought 16 diamond`. */
    private fun reason(kind: TradeKind, items: Int, placed: PriceList.Placed): String =
        "xMarket: ${if (kind == TradeKind.BUY) "bought" else "sold"} $items ${placed.entry.item.key}".take(100)

    /** An entry's name as messages show it: its own, or its item's. */
    internal fun displayName(placed: PriceList.Placed): String =
        placed.entry.name?.let { co.xenastudios.core.common.text.Markup.parse(it).render().plain() } ?: Texts.itemName(placed.entry.item.key)

    /** Forgets [player]'s trade under way (they left). */
    internal fun forget(player: PlayerRef) {
        busy.remove(player)
    }

    internal companion object {
        /** The most one trade buys: a full inventory of stacks, 36 × 64. */
        const val MAX_AMOUNT = 2304
    }
}

/**
 * Paying and charging in each [Currency]: money through the economy, an item currency from and
 * into the player's inventory, levels from their experience. The last two are at once, on the
 * player's thread; money settles when the economy answers.
 */
public class Wallets internal constructor(private val plugin: XMarketPlugin) {
    /** Whether [player] has [amount] of [currency]; `null` when it's money and there's no economy. */
    public fun has(player: PlayerRef, currency: Currency, amount: Money): Boolean? = when (currency) {
        is Currency.Money -> plugin.economy()?.has(player, amount)
        is Currency.Xp -> plugin.server.level(player) >= amount.wholeUp
        is Currency.Items -> plugin.server.count(player, currency.item) >= amount.wholeUp
    }

    /** Takes [amount]: settles `true` if taken, `false` if refused, `null` if it couldn't be asked (no economy). */
    public fun take(player: PlayerRef, currency: Currency, amount: Money, reason: String): Pending<Boolean?> {
        if (amount.isZero) return pendingOf(true)
        return when (currency) {
            is Currency.Money -> plugin.economy()?.withdraw(player, amount, reason)?.let(::nullable) ?: pendingOf(null)
            is Currency.Xp -> pendingOf(plugin.server.addLevels(player, -amount.wholeUp.toInt()))
            is Currency.Items -> pendingOf(plugin.server.takeItems(player, currency.item, amount.wholeUp.toInt()))
        }
    }

    /** Pays [amount]: as [take]. An item currency that doesn't fit is dropped at the player's feet. */
    public fun give(player: PlayerRef, currency: Currency, amount: Money, reason: String): Pending<Boolean?> {
        if (amount.isZero) return pendingOf(true)
        return when (currency) {
            is Currency.Money -> plugin.economy()?.deposit(player, amount, reason)?.let(::nullable) ?: pendingOf(null)
            is Currency.Xp -> pendingOf(plugin.server.addLevels(player, amount.whole.toInt()))
            is Currency.Items -> {
                val n = amount.whole.toInt()
                val given = plugin.server.giveNew(player, currency.item, n)
                if (given < n) plugin.server.dropNew(player, currency.item, n - given)
                pendingOf(true)
            }
        }
    }

    private fun nullable(p: Pending<Boolean>): Pending<Boolean?> {
        val out = newPending<Boolean?>()
        p.onSettled { v, e -> out.resolve(if (e != null) false else v) }
        return out
    }
}
