package co.xenastudios.xmarket

import co.xenastudios.core.common.i18n.CoreMessages
import co.xenastudios.core.common.i18n.MessageBundle
import co.xenastudios.core.common.i18n.MessageCatalog
import co.xenastudios.core.common.i18n.MessageKey

/** Every message xMarket sends, in `lang/<language>.yml` (English here, in code). */
public object XMarketMessages : MessageBundle() {
    private val ITEM = listOf("amount", "item", "price")
    private const val PAGES =
        " <gray>Page <page> of <pages></gray><has_previous> <click:run_command:'<previous>'><hover:show_text:'<previous>'><highlight>« Previous</highlight></hover></click></has_previous>" +
            "<has_next> <click:run_command:'<next>'><hover:show_text:'<next>'><highlight>Next »</highlight></hover></click></has_next>"
    private val PAGE_PLACEHOLDERS = listOf("page", "pages", "previous", "next", "has_previous", "has_next")

    public val info: MessageKey = message(
        "info", "<prefix>xMarket <highlight><version></highlight> on <platform>: <entries> items in <categories> categories, paid through <economy>. <gray>/xmarket reload | debug | import | forget",
        "/xmarket on its own.", placeholders = listOf("version", "platform", "entries", "categories", "economy"),
    )
    public val loading: MessageKey = message("loading", "<prefix><error>The shop is still loading. Try again in a moment.", "Before the price list and the database are ready, just after a start.")
    public val noCategory: MessageKey = message("no-category", "<prefix><error>There's no category called <highlight><category></highlight>.", placeholders = listOf("category"))
    public val usage: MessageKey = message("usage", "<prefix><error>Usage: <highlight><usage></highlight>", "/shop's words with what they need missing.", placeholders = listOf("usage"))
    public val noItem: MessageKey = message("no-item", "<prefix><error>The shop has no <highlight><item></highlight>.", "/shop buy, /sell and /worth with a name the shop doesn't know.", placeholders = listOf("item"))

    // ---- the menus ----

    public val menuBuy: MessageKey = message("menu.buy", "<gray>Buy: <highlight><price></highlight> <dark_gray>(left click)", "An item's lore in a category. <price> is for one, after the player's discount.", placeholders = listOf("price"))
    public val menuSell: MessageKey = message("menu.sell", "<gray>Sell: <highlight><price></highlight> <dark_gray>(right click)", placeholders = listOf("price"))
    public val menuSellAll: MessageKey = message("menu.sell-all", "<dark_gray>Shift-right click to sell all you have", "Under menu.sell, for items that sell.")
    public val menuNotForSale: MessageKey = message("menu.not-for-sale", "<dark_gray>Can't be bought here")
    public val menuNotBought: MessageKey = message("menu.not-bought", "<dark_gray>Can't be sold here")
    public val menuAmount: MessageKey = message("menu.amount", "<gray>Comes as <highlight><amount></highlight>", "An entry that's more than one item a time.", placeholders = listOf("amount"))
    public val menuLimit: MessageKey = message(
        "menu.limit", "<gray>Left this <period>: <highlight><left></highlight>",
        "An entry with a limit: what the player has left. <period> is period.day, period.week or period.ever.", placeholders = listOf("period", "left"),
    )
    public val menuCategory: MessageKey = message("menu.category", "<gray><count> items", "A category's icon in /shop.", placeholders = listOf("count"))
    public val menuBack: MessageKey = message("menu.back", "<yellow>Back")
    public val menuPrevious: MessageKey = message("menu.previous", "<yellow>Previous page")
    public val menuNext: MessageKey = message("menu.next", "<yellow>Next page")
    public val menuPage: MessageKey = message("menu.page", "<gray>Page <page> of <pages>", placeholders = listOf("page", "pages"))
    public val menuClose: MessageKey = message("menu.close", "<red>Close")
    public val menuSellItems: MessageKey = message("menu.sell-items", "<green>Sell items", "The /sellgui button in /shop.")
    public val menuSellItemsLore: MessageKey = message("menu.sell-items-lore", "<gray>Drop items into a chest to sell them")
    public val sellTitle: MessageKey = message("menu.sell-title", "<dark_gray>Drop items to sell", "/sellgui's title.")
    public val buyTitle: MessageKey = message("menu.buy-title", "<dark_gray>Buy <item>", "The amount screen's title, buying.", placeholders = listOf("item"))
    public val sellingTitle: MessageKey = message("menu.selling-title", "<dark_gray>Sell <item>", "The amount screen's title, selling.", placeholders = listOf("item"))
    public val amountLore: MessageKey = message("amount.chosen", "<gray>Amount: <highlight><amount></highlight>", placeholders = listOf("amount"))
    public val amountTotal: MessageKey = message("amount.total", "<gray>Total: <highlight><price></highlight>", placeholders = listOf("price"))
    public val amountAdd: MessageKey = message("amount.add", "<green>+<n>", placeholders = listOf("n"))
    public val amountRemove: MessageKey = message("amount.remove", "<red>-<n>", placeholders = listOf("n"))
    public val amountStack: MessageKey = message("amount.stack", "<yellow>A stack")
    public val amountFill: MessageKey = message("amount.fill", "<yellow>Fill my inventory")
    public val amountAll: MessageKey = message("amount.all", "<yellow>All I have")
    public val amountType: MessageKey = message("amount.type", "<yellow>Type an amount")
    public val amountConfirmBuy: MessageKey = message("amount.confirm-buy", "<green>Buy <amount> for <price>", placeholders = listOf("amount", "price"))
    public val amountConfirmSell: MessageKey = message("amount.confirm-sell", "<green>Sell <amount> for <price>", placeholders = listOf("amount", "price"))
    public val amountPrompt: MessageKey = message("amount.prompt", "<prefix>Type how many in chat, or <highlight>cancel</highlight>.", "After Type an amount; the next chat line is the answer.")
    public val amountBad: MessageKey = message("amount.bad", "<prefix><error><input> isn't an amount.", placeholders = listOf("input"))

    // ---- trades ----

    public val bought: MessageKey = message("trade.bought", "<prefix><success>Bought</success> <highlight><amount>× <item></highlight> for <highlight><price></highlight>.", placeholders = ITEM)
    public val boughtPartly: MessageKey = message(
        "trade.bought-partly", "<prefix><success>Bought</success> <highlight><amount>× <item></highlight> for <highlight><price></highlight>: no room for the rest, so <highlight><refund></highlight> came back.",
        "Some didn't fit after paying, and were refunded.", placeholders = ITEM + "refund",
    )
    public val boughtCommand: MessageKey = message("trade.bought-command", "<prefix><success>Bought</success> <highlight><item></highlight> for <highlight><price></highlight>.", "A command entry.", placeholders = listOf("item", "price"))
    public val sold: MessageKey = message("trade.sold", "<prefix><success>Sold</success> <highlight><amount>× <item></highlight> for <highlight><price></highlight>.", placeholders = ITEM)
    public val soldMany: MessageKey = message(
        "trade.sold-many", "<prefix><success>Sold</success> <highlight><count></highlight> items for <highlight><price></highlight>.",
        "/sell all and /sellgui. <price> may list more than one currency.", placeholders = listOf("count", "price"),
    )
    public val returned: MessageKey = message("trade.returned", "<prefix><highlight><count></highlight> items that don't sell here went back to you.", placeholders = listOf("count"))
    public val nothingToSell: MessageKey = message("trade.nothing-to-sell", "<prefix><error>Nothing you have sells here.")
    public val handEmpty: MessageKey = message("trade.hand-empty", "<prefix><error>You aren't holding anything.")
    public val notSellable: MessageKey = message("trade.not-sellable", "<prefix><error><highlight><item></highlight> doesn't sell here.", placeholders = listOf("item"))
    public val notBuyable: MessageKey = message("trade.not-buyable", "<prefix><error><highlight><item></highlight> isn't for sale.", placeholders = listOf("item"))
    public val cantAfford: MessageKey = message("trade.cant-afford", "<prefix><error>That costs <highlight><price></highlight>, which you don't have.", placeholders = listOf("price"))
    public val noRoom: MessageKey = message("trade.no-room", "<prefix><error>Your inventory has no room for it.")
    public val limitReached: MessageKey = message(
        "trade.limit", "<prefix><error>You've had your <highlight><limit></highlight> of <highlight><item></highlight> for this <period>.",
        placeholders = listOf("limit", "item", "period"),
    )
    public val serverLimit: MessageKey = message("trade.server-limit", "<prefix><error>The server's <highlight><item></highlight> are gone for this <period>.", placeholders = listOf("item", "period"))
    public val notAllowed: MessageKey = message("trade.not-allowed", "<prefix><error>You can't use that.", "No permission for the category or the entry.")
    public val requirement: MessageKey = message("trade.requirement", "<prefix><error>You need <highlight><requirement></highlight> for that.", placeholders = listOf("requirement"))
    public val busy: MessageKey = message("trade.busy", "<prefix><error>Your last trade is still going through.")
    public val failed: MessageKey = message("trade.failed", "<prefix><error>That didn't go through, so nothing changed. Try again.", "The currency refused, or the items changed meanwhile.")
    public val noEconomy: MessageKey = message("trade.no-economy", "<prefix><error>The shop's money isn't working right now. Tell staff.", "Money, but neither xMoney nor another economy. The console says which.")
    public val sellAllConfirm: MessageKey = message("trade.sell-all-confirm", "<prefix>Run <highlight>/sell all</highlight> again within 10 seconds to sell everything that sells.")
    public val worth: MessageKey = message(
        "worth.sells", "<prefix><highlight><item></highlight> sells for <highlight><price></highlight> each<total>.",
        "/worth. <total> is worth.total when more than one is held, or nothing.", placeholders = listOf("item", "price", "total"),
    )
    public val worthTotal: MessageKey = message("worth.total", ", <highlight><price></highlight> for <amount>", placeholders = listOf("price", "amount"))
    public val worthBuys: MessageKey = message("worth.buys", "<prefix><highlight><item></highlight> costs <highlight><price></highlight> to buy.", "/worth, for an item the shop sells but doesn't buy back.", placeholders = listOf("item", "price"))
    public val worthNone: MessageKey = message("worth.none", "<prefix><highlight><item></highlight> has no price here.", placeholders = listOf("item"))

    public val currencyItems: MessageKey = message("currency.items", "<amount> <item>", "A price in an item currency.", placeholders = listOf("amount", "item"))
    public val currencyXp: MessageKey = message("currency.xp", "<amount> levels", "A price in experience levels.", placeholders = listOf("amount"))
    public val periodDay: MessageKey = message("period.day", "day")
    public val periodWeek: MessageKey = message("period.week", "week")
    public val periodEver: MessageKey = message("period.ever", "server's life", "A limit that never starts again: \"this server's life\".")

    // ---- history and stats ----

    public val historyHeader: MessageKey = message("history.header", "<prefix><highlight><player></highlight>'s trades:", placeholders = listOf("player"))
    public val historyBought: MessageKey = message(
        "history.bought", " <dark_gray><when></dark_gray> <success>bought</success> <amount>× <item> for <price>",
        "One line per trade, newest first. <when> is how long ago.", placeholders = listOf("when", "amount", "item", "price"),
    )
    public val historySold: MessageKey = message("history.sold", " <dark_gray><when></dark_gray> <highlight>sold</highlight> <amount>× <item> for <price>", placeholders = listOf("when", "amount", "item", "price"))
    public val historyFooter: MessageKey = message("history.footer", PAGES, placeholders = PAGE_PLACEHOLDERS)
    public val historyNone: MessageKey = message("history.none", "<prefix><highlight><player></highlight> hasn't traded yet.", placeholders = listOf("player"))
    public val unknownPlayer: MessageKey = message("history.unknown-player", "<prefix><error>No one called <highlight><player></highlight> has played here.", placeholders = listOf("player"))
    public val statsHeader: MessageKey = message("stats.header", "<prefix>The shop over the last <highlight><days></highlight> days:", placeholders = listOf("days"))
    public val statsMoney: MessageKey = message(
        "stats.money", " <currency>: players paid <highlight><in></highlight> and were paid <highlight><out></highlight>, so the shop <change>",
        "Per currency. <change> is stats.added or stats.removed.", placeholders = listOf("currency", "in", "out", "change"),
    )
    public val statsAdded: MessageKey = message("stats.added", "<error>added <highlight><amount></highlight></error> to the economy", "More paid out for sales than taken for purchases.", placeholders = listOf("amount"))
    public val statsRemoved: MessageKey = message("stats.removed", "<success>took <highlight><amount></highlight></success> out of it", placeholders = listOf("amount"))
    public val statsTop: MessageKey = message("stats.top", " <gray>Sold most:</gray> <items>", "The most sold items, as stats.top-item joined with commas.", placeholders = listOf("items"))
    public val statsTopItem: MessageKey = message("stats.top-item", "<highlight><item></highlight> ×<amount>", placeholders = listOf("item", "amount"))
    public val statsNone: MessageKey = message("stats.none", "<prefix>No trades in the last <days> days.", placeholders = listOf("days"))
    public val noHistory: MessageKey = message("history.off", "<prefix><error>History is off (shop.history in config.yml), or the database isn't open.")

    // ---- audit and editing ----

    public val auditHeader: MessageKey = message("audit.header", "<prefix>Audit: <highlight><entries></highlight> entries in <categories> categories.", placeholders = listOf("entries", "categories"))
    public val auditMissing: MessageKey = message(
        "audit.missing", "<prefix><highlight><count></highlight> items this server has have no price: <gray><items></gray>",
        "Up to 30 names, then how many more.", placeholders = listOf("count", "items"),
    )
    public val auditAllPriced: MessageKey = message("audit.all-priced", "<prefix><success>Every item this server has is priced.")
    public val auditLoop: MessageKey = message(
        "audit.loop", " <error><item></error> sells for <highlight><sell></highlight>, but can be had for <highlight><cost></highlight><via>",
        "A loop: an item that sells for more than buying it, or what it's made of, costs. <via> is audit.via or nothing.", placeholders = listOf("item", "sell", "cost", "via"),
    )
    public val auditVia: MessageKey = message("audit.via", " by making it from <recipe>", placeholders = listOf("recipe"))
    public val auditLoops: MessageKey = message("audit.loops", "<prefix><error><count> loops let players buy, craft and sell for a profit:", placeholders = listOf("count"))
    public val auditNoLoops: MessageKey = message("audit.no-loops", "<prefix><success>No loops:</success> nothing sells for more than it costs to buy or make.")
    public val editAdded: MessageKey = message("edit.added", "<prefix><success>Added</success> <highlight><entry></highlight> to <category>.", placeholders = listOf("entry", "category"))
    public val editPriced: MessageKey = message("edit.priced", "<prefix><success>Set</success> <highlight><entry></highlight>: buy <buy>, sell <sell>.", placeholders = listOf("entry", "buy", "sell"))
    public val editMoved: MessageKey = message("edit.moved", "<prefix><success>Moved</success> <highlight><entry></highlight> to <category>.", placeholders = listOf("entry", "category"))
    public val editRemoved: MessageKey = message("edit.removed", "<prefix>Removed <highlight><entry></highlight> from <category>.", placeholders = listOf("entry", "category"))
    public val editNoEntry: MessageKey = message("edit.no-entry", "<prefix><error><category> has no <highlight><entry></highlight>.", placeholders = listOf("category", "entry"))
    public val editExists: MessageKey = message("edit.exists", "<prefix><error><category> already has <highlight><entry></highlight>: set its price with /shop edit price.", placeholders = listOf("category", "entry"))
    public val editBadPrice: MessageKey = message("edit.bad-price", "<prefix><error>A price is a number, such as 12.5, or - for none: not <input>.", placeholders = listOf("input"))
    public val editNotSaved: MessageKey = message("edit.not-saved", "<prefix><error>Couldn't save shops/<category>.yml, so nothing changed: <reason>", placeholders = listOf("category", "reason"))
    public val editNoPrice: MessageKey = message("edit.no-price", "-", "Where a price is left out, in edit.priced.")

    /** Every message the lang files hold: the core's, then xMarket's own. */
    public val catalog: MessageCatalog = MessageCatalog.of(CoreMessages, this)
}
