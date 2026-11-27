package co.xenastudios.xmarket

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.onSettled
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.command.CommandContext
import co.xenastudios.core.common.command.CommandSource
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.scheduler.now
import co.xenastudios.core.common.text.Placeholder
import co.xenastudios.xmarket.api.ItemKey
import co.xenastudios.xmarket.api.Money
import co.xenastudios.xmarket.api.TradeKind
import co.xenastudios.xmarket.price.Audit
import co.xenastudios.xmarket.price.Category
import co.xenastudios.xmarket.price.Currency
import co.xenastudios.xmarket.price.Entry
import co.xenastudios.xmarket.price.ItemSpec
import co.xenastudios.xmarket.price.PriceList
import kotlin.time.Duration.Companion.milliseconds

/** What each command does, made the first time one runs: not as the server starts. */
internal class Actions(private val plugin: XMarketPlugin) {
    private val texts: Texts get() = plugin.texts
    private val server: MarketServer get() = plugin.server

    private fun ready(source: CommandSource): Boolean {
        if (plugin.ready) return true
        texts.reply(source, XMarketMessages.loading)
        return false
    }

    fun info(context: CommandContext) {
        val prices = plugin.prices
        texts.reply(
            context.source, XMarketMessages.info, "version" to plugin.version, "platform" to plugin.platform.name,
            "entries" to prices.categories.sumOf { it.entries.size }, "categories" to prices.categories.size,
            "economy" to (plugin.economy()?.name ?: "no economy"),
        )
    }

    /** `/shop [what] [more]`: a category, or one of its words, each with its own permission. */
    fun shop(context: CommandContext, what: String?, more: List<String>) {
        val source = context.source
        val perms = plugin.permissions
        fun allowed(permission: co.xenastudios.core.common.permission.Permission): Boolean =
            source.hasPermission(permission.node).also { if (!it) texts.reply(source, co.xenastudios.core.common.i18n.CoreMessages.commandNoPermission) }
        fun usage(line: String) = texts.reply(source, XMarketMessages.usage, "usage" to line)
        when (what?.lowercase()) {
            "buy" -> {
                if (source.player == null) return texts.reply(source, co.xenastudios.core.common.i18n.CoreMessages.commandPlayersOnly)
                val item = more.getOrNull(0) ?: return usage("/shop buy <item> [amount]")
                val amount = more.getOrNull(1)?.let { it.toIntOrNull()?.takeIf { n -> n in 1..Trades.MAX_AMOUNT } ?: return usage("/shop buy <item> [amount]") } ?: 1
                return buy(context, item, amount)
            }
            "history" -> {
                if (!allowed(perms.history)) return
                return history(context, more.getOrNull(0), more.getOrNull(1)?.toIntOrNull())
            }
            "stats" -> if (allowed(perms.stats)) return stats(context, more.getOrNull(0)?.toIntOrNull()?.coerceIn(1, 3650) ?: 7) else return
            "audit" -> if (allowed(perms.audit)) return audit(context) else return
            "edit" -> {
                if (!allowed(perms.edit)) return
                val args = more.drop(1)
                when (more.getOrNull(0)?.lowercase()) {
                    "add" -> {
                        if (source.player == null) return texts.reply(source, co.xenastudios.core.common.i18n.CoreMessages.commandPlayersOnly)
                        val c = args.getOrNull(0) ?: return usage("/shop edit add <category> [buy|-] [sell|-]")
                        return editAdd(context, c, args.getOrNull(1), args.getOrNull(2))
                    }
                    "price" -> if (args.size >= 4) return editPrice(context, args[0], args[1], args[2], args[3]) else return usage("/shop edit price <category> <item> <buy|-> <sell|->")
                    "move" -> if (args.size >= 3) return editMove(context, args[0], args[1], args[2]) else return usage("/shop edit move <category> <item> <to>")
                    "remove" -> if (args.size >= 2) return editRemove(context, args[0], args[1]) else return usage("/shop edit remove <category> <item>")
                    else -> return usage("/shop edit add | price | move | remove")
                }
            }
        }
        val category = what
        val player = source.player ?: return info(context)
        if (!ready(source)) return
        if (category == null) return plugin.ui.main(player)
        val c = plugin.prices.category(category)
        if (c == null || (c.permission != null && !server.hasPermission(player, c.permission))) return texts.reply(context.source, XMarketMessages.noCategory, "category" to category)
        plugin.ui.category(player, c, 0)
    }

    private fun item(name: String): ItemKey = ItemSpec.ofKey(name.lowercase().removePrefix("minecraft:")).toKey()

    private fun buy(context: CommandContext, name: String, amount: Int) {
        val player = context.player
        if (!ready(context.source)) return
        val placed = plugin.prices.find(item(name), TradeKind.BUY) ?: return texts.reply(context.source, XMarketMessages.noItem, "item" to name)
        plugin.trades.buy(player, placed, (amount + placed.entry.amount - 1) / placed.entry.amount)
    }

    fun sell(context: CommandContext, what: String, amount: Int?) {
        val player = context.player
        if (!ready(context.source)) return
        val max = amount ?: Int.MAX_VALUE
        when (what.lowercase()) {
            "hand" -> {
                val held = server.hand(player) ?: return texts.reply(context.source, XMarketMessages.handEmpty)
                val n = minOf(held.item.amount, max)
                plugin.trades.sell(player, listOf(Trades.Offer(held.item.withAmount(n), held.slot)), Trades.Source.HAND)
            }
            "all" -> {
                val offers = server.inventory(player).filter { plugin.prices.find(it.item, TradeKind.SELL) != null || plugin.provided(it.item) != null }
                    .map { Trades.Offer(it.item, it.slot) }
                if (offers.isEmpty()) return texts.reply(context.source, XMarketMessages.nothingToSell)
                plugin.trades.sell(player, offers, Trades.Source.ALL)
            }
            else -> {
                val placed = plugin.prices.find(item(what), TradeKind.SELL) ?: return texts.reply(context.source, XMarketMessages.notSellable, "item" to Texts.itemName(what))
                plugin.ui.sellMatching(player, placed, max)
            }
        }
    }

    fun sellGui(context: CommandContext) {
        if (!ready(context.source)) return
        plugin.ui.sellGui(context.player)
    }

    fun worth(context: CommandContext, name: String?) {
        val source = context.source
        if (!ready(source)) return
        val player = source.player
        val held = if (name == null) {
            if (player == null) return texts.reply(source, XMarketMessages.handEmpty)
            server.hand(player)?.item ?: return texts.reply(source, XMarketMessages.handEmpty)
        } else {
            item(name)
        }
        val label = Texts.itemName(held.id + (held.potion?.let { "{$it}" } ?: ""))
        val placed = plugin.prices.find(held, TradeKind.SELL) ?: plugin.provided(held)
        val each = placed?.let { plugin.trades.unitSell(player, it) }
        if (placed == null || each == null) {
            val buyable = plugin.prices.find(held, TradeKind.BUY)
            val cost = buyable?.let { plugin.trades.unitBuy(player, it) }
            if (buyable != null && cost != null) return texts.reply(source, XMarketMessages.worthBuys, "item" to label, "price" to texts.price(player, cost, buyable.currency))
            return texts.reply(source, XMarketMessages.worthNone, "item" to label)
        }
        val per = placed.entry.amount
        val unitsHeld = held.amount / per
        val total = if (unitsHeld > 1) {
            Placeholder.component(texts.line(player, XMarketMessages.worthTotal, "price" to texts.price(player, plugin.trades.total(placed.currency, each, unitsHeld, false), placed.currency), "amount" to unitsHeld * per))
        } else {
            Placeholder.text("")
        }
        val shownEach = if (per == 1) each else each.divide(per.toLong(), up = false)
        texts.reply(source, XMarketMessages.worth, "item" to label, "price" to texts.price(player, shownEach, placed.currency), "total" to total)
    }

    // ---- history and stats ----

    fun history(context: CommandContext, first: String?, second: Int?) {
        val source = context.source
        val store = plugin.store
        if (store == null || !plugin.config[co.xenastudios.xmarket.config.XMarketConfig.shop.history]) return texts.reply(source, XMarketMessages.noHistory)
        val page = first?.toIntOrNull()
        if (first == null || page != null) {
            val player = source.player ?: return texts.reply(source, XMarketMessages.handEmpty)
            return showHistory(source, player, source.name, page ?: 1)
        }
        if (!source.hasPermission(plugin.permissions.historyOthers.node)) return texts.reply(source, co.xenastudios.core.common.i18n.CoreMessages.commandNoPermission, "permission" to plugin.permissions.historyOthers.node)
        plugin.findPlayer(first).onSettled { found, _ ->
            plugin.platform.scheduler.now(source.player?.let { Where.on(it) } ?: Where.Global) {
                if (found == null) texts.reply(source, XMarketMessages.unknownPlayer, "player" to first)
                else showHistory(source, found.first, found.second, second ?: 1)
            }
        }
    }

    private fun showHistory(source: CommandSource, player: PlayerRef, name: String, page: Int) {
        val store = plugin.store ?: return texts.reply(source, XMarketMessages.noHistory)
        store.history(player, page, PER_PAGE).onSettled { r, _ ->
            plugin.platform.scheduler.now(source.player?.let { Where.on(it) } ?: Where.Global) {
                val ok = r as? Outcome.Ok ?: return@now texts.reply(source, XMarketMessages.noHistory)
                val (trades, count) = ok.value
                if (count == 0L) return@now texts.reply(source, XMarketMessages.historyNone, "player" to name)
                val pages = ((count + PER_PAGE - 1) / PER_PAGE).toInt()
                texts.reply(source, XMarketMessages.historyHeader, "player" to name)
                val now = plugin.now()
                for (t in trades) {
                    val currency = Currency.of(t.currency) ?: Currency.Money
                    texts.reply(
                        source, if (t.buy) XMarketMessages.historyBought else XMarketMessages.historySold,
                        "when" to (now - t.at).coerceAtLeast(0).milliseconds, "amount" to t.amount, "item" to Texts.itemName(t.item),
                        "price" to texts.price(source.player, Money.ofUnits(t.total), currency),
                    )
                }
                if (pages > 1) {
                    val base = if (source.player == player) "/shop history " else "/shop history $name "
                    texts.reply(
                        source, XMarketMessages.historyFooter, "page" to page, "pages" to pages,
                        "previous" to base + (page - 1), "next" to base + (page + 1),
                        "has_previous" to Placeholder.section(page > 1), "has_next" to Placeholder.section(page < pages),
                    )
                }
            }
        }
    }

    fun stats(context: CommandContext, days: Int) {
        val source = context.source
        val store = plugin.store ?: return texts.reply(source, XMarketMessages.noHistory)
        store.stats(plugin.now() - days * 86_400_000L, 5).onSettled { r, _ ->
            plugin.platform.scheduler.now(source.player?.let { Where.on(it) } ?: Where.Global) {
                val ok = r as? Outcome.Ok ?: return@now texts.reply(source, XMarketMessages.noHistory)
                val (totals, top) = ok.value
                if (totals.isEmpty()) return@now texts.reply(source, XMarketMessages.statsNone, "days" to days)
                texts.reply(source, XMarketMessages.statsHeader, "days" to days)
                for (t in totals) {
                    val currency = Currency.of(t.currency) ?: Currency.Money
                    val net = t.sold - t.bought
                    val change = if (net > 0) {
                        texts.line(source.player, XMarketMessages.statsAdded, "amount" to texts.price(source.player, Money.ofUnits(net), currency))
                    } else {
                        texts.line(source.player, XMarketMessages.statsRemoved, "amount" to texts.price(source.player, Money.ofUnits(-net), currency))
                    }
                    texts.reply(
                        source, XMarketMessages.statsMoney, "currency" to t.currency,
                        "in" to texts.price(source.player, Money.ofUnits(t.bought), currency), "out" to texts.price(source.player, Money.ofUnits(t.sold), currency),
                        "change" to change,
                    )
                }
                if (top.isNotEmpty()) {
                    val items = top.joinToString(", ") { texts.line(source.player, XMarketMessages.statsTopItem, "item" to Texts.itemName(it.item), "amount" to it.amount).plain() }
                    texts.reply(source, XMarketMessages.statsTop, "items" to items)
                }
            }
        }
    }

    // ---- the audit ----

    fun audit(context: CommandContext) {
        val source = context.source
        if (!ready(source)) return
        val prices = plugin.prices
        texts.reply(source, XMarketMessages.auditHeader, "entries" to prices.categories.sumOf { it.entries.size }, "categories" to prices.categories.size)
        // Off the command's thread: the loop search reads every recipe.
        plugin.platform.scheduler.run(Where.Async) {
            val report = plugin.audit()
            plugin.platform.scheduler.now(source.player?.let { Where.on(it) } ?: Where.Global) {
                if (report.missing.isEmpty()) {
                    texts.reply(source, XMarketMessages.auditAllPriced)
                } else {
                    val names = report.missing.take(30).joinToString(", ") { it.removePrefix("minecraft:") } +
                        if (report.missing.size > 30) ", +${report.missing.size - 30}" else ""
                    texts.reply(source, XMarketMessages.auditMissing, "count" to report.missing.size, "items" to names)
                }
                if (report.loops.isEmpty()) {
                    texts.reply(source, XMarketMessages.auditNoLoops)
                } else {
                    texts.reply(source, XMarketMessages.auditLoops, "count" to report.loops.size)
                    for (loop in report.loops.take(20)) {
                        val via = loop.via?.let { r ->
                            Placeholder.component(texts.line(source.player, XMarketMessages.auditVia, "recipe" to r.ingredients.joinToString(" + ") { i -> "${i.count}× ${Texts.itemName(i.options.first())}" }))
                        } ?: Placeholder.text("")
                        texts.reply(
                            source, XMarketMessages.auditLoop, "item" to Texts.itemName(loop.key),
                            "sell" to loop.sell.toString(), "cost" to loop.cost.toString(), "via" to via,
                        )
                    }
                }
            }
        }
    }

    // ---- editing ----

    private fun price(source: CommandSource, text: String?): Pair<Boolean, Money?> {
        if (text == null || text == "-" || text.equals("none", true)) return true to null
        val m = Money.parse(text)
        if (m == null || m.isNegative) {
            texts.reply(source, XMarketMessages.editBadPrice, "input" to text)
            return false to null
        }
        return true to m
    }

    private fun category(source: CommandSource, id: String): Category? =
        plugin.prices.categories.firstOrNull { it.id.equals(id, true) } ?: null.also { texts.reply(source, XMarketMessages.noCategory, "category" to id) }

    fun editAdd(context: CommandContext, categoryId: String, buyText: String?, sellText: String?) {
        val source = context.source
        val player = context.player
        if (!ready(source)) return
        val category = category(source, categoryId) ?: return
        val held = server.hand(player) ?: return texts.reply(source, XMarketMessages.handEmpty)
        val (okBuy, buy) = price(source, buyText)
        val (okSell, sell) = price(source, sellText)
        if (!okBuy || !okSell) return
        val item = held.item
        val spec = if (item.plain) ItemSpec(item.id, item.potion, item.enchantments) else ItemSpec(item.id, item.potion, item.enchantments, item.data ?: server.menus.data(held.native))
        var id = spec.key.takeIf { spec.data == null } ?: spec.shortId
        if (spec.data == null && category.entry(id) != null) return texts.reply(source, XMarketMessages.editExists, "category" to category.id, "entry" to id)
        var n = 2
        val base = id
        while (category.entry(id) != null) id = base + "_" + n++
        val entry = Entry(id, spec, buy, sell)
        plugin.save(listOf(category.withEntries(category.entries + entry)), source) {
            texts.reply(source, XMarketMessages.editAdded, "entry" to id, "category" to category.id)
        }
    }

    fun editPrice(context: CommandContext, categoryId: String, entryId: String, buyText: String, sellText: String) {
        val source = context.source
        if (!ready(source)) return
        val category = category(source, categoryId) ?: return
        val entry = category.entry(entryId) ?: return texts.reply(source, XMarketMessages.editNoEntry, "category" to category.id, "entry" to entryId)
        val (okBuy, buy) = price(source, buyText)
        val (okSell, sell) = price(source, sellText)
        if (!okBuy || !okSell) return
        val changed = category.withEntries(category.entries.map { if (it === entry) it.withPrices(buy, sell) else it })
        plugin.save(listOf(changed), source) {
            val none = texts.line(source.player, XMarketMessages.editNoPrice).plain()
            texts.reply(source, XMarketMessages.editPriced, "entry" to entry.id, "buy" to (buy?.toString() ?: none), "sell" to (sell?.toString() ?: none))
        }
    }

    fun editMove(context: CommandContext, categoryId: String, entryId: String, toId: String) {
        val source = context.source
        if (!ready(source)) return
        val from = category(source, categoryId) ?: return
        val to = category(source, toId) ?: return
        val entry = from.entry(entryId) ?: return texts.reply(source, XMarketMessages.editNoEntry, "category" to from.id, "entry" to entryId)
        if (from.id == to.id) return
        var id = entry.id
        var n = 2
        while (to.entry(id) != null) id = entry.id + "_" + n++
        plugin.save(listOf(from.withEntries(from.entries.filter { it !== entry }), to.withEntries(to.entries + entry.withId(id))), source) {
            texts.reply(source, XMarketMessages.editMoved, "entry" to id, "category" to to.id)
        }
    }

    fun editRemove(context: CommandContext, categoryId: String, entryId: String) {
        val source = context.source
        if (!ready(source)) return
        val category = category(source, categoryId) ?: return
        val entry = category.entry(entryId) ?: return texts.reply(source, XMarketMessages.editNoEntry, "category" to category.id, "entry" to entryId)
        plugin.save(listOf(category.withEntries(category.entries.filter { it !== entry })), source) {
            texts.reply(source, XMarketMessages.editRemoved, "entry" to entry.id, "category" to category.id)
        }
    }

    private companion object {
        const val PER_PAGE = 10
    }
}

/** What `/shop audit` found: items this server has with no price, and the loops. */
public class AuditReport(public val missing: List<String>, public val loops: List<Audit.Loop>)
