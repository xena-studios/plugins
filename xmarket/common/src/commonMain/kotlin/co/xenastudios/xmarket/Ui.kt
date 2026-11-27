package co.xenastudios.xmarket

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.menu.Click
import co.xenastudios.core.common.menu.Icon
import co.xenastudios.core.common.menu.Menu
import co.xenastudios.core.common.menu.MenuHandler
import co.xenastudios.core.common.menu.MenuStack
import co.xenastudios.core.common.menu.MenuView
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.scheduler.now
import co.xenastudios.core.common.text.Component
import co.xenastudios.core.common.text.Markup
import co.xenastudios.xmarket.api.TradeKind
import co.xenastudios.xmarket.config.XMarketConfig
import co.xenastudios.xmarket.price.Category
import co.xenastudios.xmarket.price.ItemSpec
import co.xenastudios.xmarket.price.PriceList

/**
 * The shop's menus, on the core's chest menus (nothing in them is a real item, and every click is
 * the platform's to cancel): `/shop`'s categories, a category's pages, and the amount screen,
 * where a player picks how many to buy or sell. A menu is drawn as it opens, for its viewer: the
 * prices after their discount or boost, and what they have left of a limit. `/sellgui` is a
 * deposit: a chest whose items are sold as it closes, and anything without a price goes back.
 */
public class Ui internal constructor(private val plugin: XMarketPlugin) {
    private val server: MarketServer get() = plugin.server
    private val texts: Texts get() = plugin.texts

    private fun markup(text: String): Component = Markup.parse(text, texts.translations.theme.tags).render()

    private fun button(item: String, name: Component, vararg lore: Component): Icon? =
        server.icon(ItemSpec.ofKey(item))?.named(name, lore.toList())

    // ---- /shop ----

    /** `/shop`'s menu of categories. */
    public fun main(player: PlayerRef) {
        val prices = plugin.prices
        val rows = plugin.config[XMarketConfig.shop.rows]
        val size = rows * 9
        val icons = arrayOfNulls<Icon>(size)
        val targets = arrayOfNulls<Category>(size)
        val shown = prices.scaled.filter { c -> c.permission == null || server.hasPermission(player, c.permission) }
        var next = 0
        for (c in shown) {
            var slot = c.slot
            if (slot !in 0 until size || icons[slot] != null) {
                while (next < size && icons[next] != null) next++
                slot = next
            }
            if (slot >= size) break
            val icon = server.icon(c.icon) ?: server.icon(ItemSpec("minecraft:chest")) ?: continue
            icons[slot] = icon.named(markup(c.name), listOf(texts.line(player, XMarketMessages.menuCategory, "count" to c.entries.size)))
            targets[slot] = c
        }
        val sellSlot = if (plugin.config[XMarketConfig.commands.sellgui.enabled] && size >= 9) size - 5 else -1
        if (sellSlot >= 0 && icons[sellSlot] == null) {
            icons[sellSlot] = button("hopper", texts.line(player, XMarketMessages.menuSellItems), texts.line(player, XMarketMessages.menuSellItemsLore))
        }
        if (icons[size - 1] == null) icons[size - 1] = button("barrier", texts.line(player, XMarketMessages.menuClose))
        server.menus.open(player, Menu(markup(plugin.config[XMarketConfig.shop.title]), rows, icons.toList(), MainClicks(targets, sellSlot, size - 1)))
    }

    private inner class MainClicks(private val targets: Array<Category?>, private val sell: Int, private val close: Int) : MenuHandler {
        override fun click(viewer: PlayerRef, view: MenuView, slot: Int, click: Click) {
            when (slot) {
                close -> view.close()
                sell -> sellGui(viewer)
                else -> targets.getOrNull(slot)?.let { category(viewer, it, 0) }
            }
        }
    }

    // ---- a category ----

    /** Page [page] (from 0) of [category]. */
    public fun category(player: PlayerRef, category: Category, page: Int) {
        val entries = category.entries.map { PriceList.Placed(category, it) }.filter { plugin.trades.visible(player, it) }
            .mapNotNull { p -> server.icon(p.entry.icon ?: p.entry.item)?.let { p to it } }
        val pages = maxOf(1, (entries.size + PAGE - 1) / PAGE)
        val shownPage = page.coerceIn(0, pages - 1)
        val icons = arrayOfNulls<Icon>(54)
        val targets = arrayOfNulls<PriceList.Placed>(54)
        // Entries with a slot of their own on the first page sit there; the rest fill in order.
        val onPage = entries.drop(shownPage * PAGE).take(PAGE)
        var next = 0
        for ((placed, base) in onPage) {
            var slot = if (shownPage == 0) placed.entry.slot else -1
            if (slot !in 0 until PAGE || icons[slot] != null) {
                while (next < PAGE && icons[next] != null) next++
                slot = next
            }
            if (slot >= PAGE) break
            icons[slot] = entryIcon(player, placed, base)
            targets[slot] = placed
        }
        icons[45] = button("arrow", texts.line(player, XMarketMessages.menuBack))
        if (shownPage > 0) icons[48] = button("arrow", texts.line(player, XMarketMessages.menuPrevious))
        icons[49] = button("paper", texts.line(player, XMarketMessages.menuPage, "page" to shownPage + 1, "pages" to pages))
        if (shownPage < pages - 1) icons[50] = button("arrow", texts.line(player, XMarketMessages.menuNext))
        icons[53] = button("barrier", texts.line(player, XMarketMessages.menuClose))
        server.menus.open(player, Menu(markup(category.name), 6, icons.toList(), CategoryClicks(category, shownPage, pages, targets)))
    }

    private fun entryIcon(player: PlayerRef, placed: PriceList.Placed, base: Icon): Icon {
        val e = placed.entry
        val lore = ArrayList<Component>()
        for (line in e.lore) lore += markup(line)
        val trades = plugin.trades
        val buy = trades.unitBuy(player, placed)
        val sell = if (e.commands.isEmpty()) trades.unitSell(player, placed) else null
        lore += if (buy != null) texts.line(player, XMarketMessages.menuBuy, "price" to texts.price(player, buy, placed.currency))
        else texts.line(player, XMarketMessages.menuNotForSale)
        if (e.commands.isEmpty()) {
            lore += if (sell != null) texts.line(player, XMarketMessages.menuSell, "price" to texts.price(player, sell, placed.currency))
            else texts.line(player, XMarketMessages.menuNotBought)
        }
        if (e.amount > 1) lore += texts.line(player, XMarketMessages.menuAmount, "amount" to e.amount)
        for (l in e.limits) {
            lore += texts.line(player, XMarketMessages.menuLimit, "period" to trades.periodWord(l.period), "left" to plugin.limits.left(player, trades.key(placed), l))
        }
        if (sell != null) lore += texts.line(player, XMarketMessages.menuSellAll)
        val name = e.name?.let(::markup)
        return base.named(name ?: base.name, lore).times(e.amount)
    }

    private inner class CategoryClicks(
        private val category: Category,
        private val page: Int,
        private val pages: Int,
        private val targets: Array<PriceList.Placed?>,
    ) : MenuHandler {
        override fun click(viewer: PlayerRef, view: MenuView, slot: Int, click: Click) {
            when (slot) {
                45 -> main(viewer)
                48 -> if (page > 0) category(viewer, category, page - 1)
                50 -> if (page < pages - 1) category(viewer, category, page + 1)
                53 -> view.close()
                else -> {
                    val placed = targets.getOrNull(slot) ?: return
                    when {
                        click == Click.SHIFT_RIGHT && placed.entry.sell != null -> sellMatching(viewer, placed, Int.MAX_VALUE)
                        click.right && placed.entry.sell != null && placed.entry.commands.isEmpty() -> amount(viewer, placed, TradeKind.SELL, 1, page)
                        placed.entry.buy != null -> amount(viewer, placed, TradeKind.BUY, 1, page)
                        placed.entry.sell != null -> amount(viewer, placed, TradeKind.SELL, 1, page)
                    }
                }
            }
        }
    }

    // ---- the amount screen ----

    /** The amount screen for [placed]: [kind], starting at [amount]. [page] is the category page to go back to. */
    public fun amount(player: PlayerRef, placed: PriceList.Placed, kind: TradeKind, amount: Int, page: Int) {
        val n = amount.coerceIn(1, Trades.MAX_AMOUNT)
        val trades = plugin.trades
        val unit = (if (kind == TradeKind.BUY) trades.unitBuy(player, placed) else trades.unitSell(player, placed)) ?: return category(player, placed.category, page)
        val total = trades.total(placed.currency, unit, n, kind == TradeKind.BUY)
        val price = texts.price(player, total, placed.currency)
        val items = n * placed.entry.amount
        val icons = arrayOfNulls<Icon>(54)
        for ((slot, step) in STEPS) {
            val add = step > 0
            icons[slot] = button(
                if (add) "lime_stained_glass_pane" else "red_stained_glass_pane",
                texts.line(player, if (add) XMarketMessages.amountAdd else XMarketMessages.amountRemove, "n" to kotlin.math.abs(step)),
            )
        }
        val base = server.icon(placed.entry.icon ?: placed.entry.item)
        icons[13] = base?.named(placed.entry.name?.let(::markup) ?: base.name, listOf(
            texts.line(player, XMarketMessages.amountLore, "amount" to items),
            texts.line(player, XMarketMessages.amountTotal, "price" to price),
        ))?.times(items)
        if (placed.entry.commands.isEmpty()) {
            icons[29] = button("chest", texts.line(player, XMarketMessages.amountStack))
            icons[33] = button("ender_chest", texts.line(player, if (kind == TradeKind.BUY) XMarketMessages.amountFill else XMarketMessages.amountAll))
        }
        icons[31] = button("oak_sign", texts.line(player, XMarketMessages.amountType))
        icons[40] = button(
            if (kind == TradeKind.BUY) "emerald" else "gold_ingot",
            texts.line(player, if (kind == TradeKind.BUY) XMarketMessages.amountConfirmBuy else XMarketMessages.amountConfirmSell, "amount" to items, "price" to price),
        )
        icons[45] = button("arrow", texts.line(player, XMarketMessages.menuBack))
        icons[53] = button("barrier", texts.line(player, XMarketMessages.menuClose))
        val title = texts.line(player, if (kind == TradeKind.BUY) XMarketMessages.buyTitle else XMarketMessages.sellingTitle, "item" to trades.displayName(placed))
        server.menus.open(player, Menu(title, 6, icons.toList(), AmountClicks(placed, kind, n, page)))
    }

    private inner class AmountClicks(private val placed: PriceList.Placed, private val kind: TradeKind, private val n: Int, private val page: Int) : MenuHandler {
        override fun click(viewer: PlayerRef, view: MenuView, slot: Int, click: Click) {
            val per = placed.entry.amount
            when (slot) {
                in STEPS.keys -> amount(viewer, placed, kind, n + STEPS.getValue(slot), page)
                29 -> amount(viewer, placed, kind, maxOf(1, 64 / per), page)
                33 -> {
                    val most = if (kind == TradeKind.BUY) server.room(viewer, placed.entry.item, Trades.MAX_AMOUNT) / per
                    else matching(viewer, placed).sumOf { it.item.amount } / per
                    amount(viewer, placed, kind, maxOf(1, most), page)
                }
                31 -> {
                    view.close()
                    texts.send(viewer, XMarketMessages.amountPrompt)
                    server.ask(viewer, 30, Typed(placed, kind, page, viewer))
                }
                40 -> {
                    view.close()
                    if (kind == TradeKind.BUY) plugin.trades.buy(viewer, placed, n) else sellMatching(viewer, placed, n * per)
                }
                45 -> category(viewer, placed.category, page)
                53 -> view.close()
            }
        }
    }

    // A typed amount, back on the player's thread.
    private inner class Typed(private val placed: PriceList.Placed, private val kind: TradeKind, private val page: Int, private val player: PlayerRef) : (String?) -> Unit {
        override fun invoke(answer: String?) {
            val text = answer?.trim() ?: return
            if (text.equals("cancel", ignoreCase = true)) return
            val n = text.toIntOrNull()
            if (n == null || n < 1) {
                texts.send(player, XMarketMessages.amountBad, "input" to text)
                return
            }
            amount(player, placed, kind, (n + placed.entry.amount - 1) / placed.entry.amount, page)
        }
    }

    // ---- selling from the inventory ----

    /** Every stack in [player]'s inventory that sells as [placed]. */
    internal fun matching(player: PlayerRef, placed: PriceList.Placed): List<Held> =
        server.inventory(player).filter { h -> plugin.prices.find(h.item, TradeKind.SELL)?.let { it.entry === placed.entry || (it.category.id == placed.category.id && it.entry.id == placed.entry.id) } == true }

    /** Sells up to [items] of what [player] has that sells as [placed]. */
    internal fun sellMatching(player: PlayerRef, placed: PriceList.Placed, items: Int) {
        var left = items
        val offers = ArrayList<Trades.Offer>()
        for (h in matching(player, placed)) {
            if (left <= 0) break
            val take = minOf(left, h.item.amount)
            offers += Trades.Offer(h.item.withAmount(take), h.slot)
            left -= take
        }
        if (offers.isEmpty()) {
            texts.send(player, XMarketMessages.nothingToSell)
            return
        }
        plugin.trades.sell(player, offers, Trades.Source.ITEM)
    }

    // ---- /sellgui ----

    /** `/sellgui`: a chest whose items are sold as it closes. */
    public fun sellGui(player: PlayerRef) {
        server.menus.deposit(player, texts.line(player, XMarketMessages.sellTitle), plugin.config[XMarketConfig.sell.guiRows], SellGui)
    }

    private val SellGui = Deposit()

    private inner class Deposit : co.xenastudios.core.common.menu.DepositHandler {
        override fun closed(viewer: PlayerRef, items: List<MenuStack>, online: Boolean) {
            if (items.isEmpty()) return
            val offers = items.map { Trades.Offer(server.key(it.native), native = it.native) }
            // A quit or a stop: the items go back (into the inventory that's saved with them), not sold.
            if (!online || !plugin.running) {
                server.give(viewer, items.map { it.native })
                return
            }
            plugin.platform.scheduler.now(Where.on(viewer), retired = { server.give(viewer, items.map { it.native }) }) {
                plugin.trades.sell(viewer, offers, Trades.Source.GUI)
            }
        }
    }

    private companion object {
        const val PAGE = 45
        val STEPS: Map<Int, Int> = linkedMapOf(10 to -64, 11 to -10, 12 to -1, 14 to 1, 15 to 10, 16 to 64)
    }
}
