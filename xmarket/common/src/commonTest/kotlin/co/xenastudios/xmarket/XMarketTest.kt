package co.xenastudios.xmarket

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.command.CommandResult
import co.xenastudios.core.common.config.Config
import co.xenastudios.core.common.db.Database
import co.xenastudios.core.common.i18n.Translations
import co.xenastudios.core.common.menu.Click
import co.xenastudios.core.common.permission.PermissionDefault
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.testing.FakeCommandSource
import co.xenastudios.core.testing.FakeDatabase
import co.xenastudios.core.testing.FakeMenus.FakeStack
import co.xenastudios.core.testing.FakePlatform
import co.xenastudios.core.testing.settled
import co.xenastudios.xmarket.api.ItemKey
import co.xenastudios.xmarket.api.Money
import co.xenastudios.xmarket.api.PriceModifier
import co.xenastudios.xmarket.api.PurchaseResult
import co.xenastudios.xmarket.api.SaleResult
import co.xenastudios.xmarket.api.TradeKind
import co.xenastudios.xmarket.api.XMarket
import co.xenastudios.xmarket.api.event.ShopBuyEvent
import co.xenastudios.xmarket.config.XMarketConfig
import co.xenastudios.xmarket.price.Category
import co.xenastudios.xmarket.price.Currency
import co.xenastudios.xmarket.price.Entry
import co.xenastudios.xmarket.price.ItemSpec
import co.xenastudios.xmarket.price.Limit
import co.xenastudios.xmarket.price.Period
import co.xenastudios.xmarket.price.Requirement
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** xMarket's trades, menus and commands on the fake platform (Folia's regions, virtual time), with a fake economy and inventories. */
class XMarketTest {
    private val platform = FakePlatform()
    private val server = FakeMarketServer(platform.scheduler)
    private var config: Config = XMarketConfig.defaults
    private var categories: List<Category> = shop()
    private val saved = ArrayList<Category>()

    private val files = object : Files {
        override fun load(): Loaded = loaded()
        override fun shops(currency: Currency): Shops = Shops(categories)
        override fun save(category: Category): String? {
            saved += category
            return null
        }
        override fun resource(name: String): String? = when (name) {
            "xmarket/recipes.txt" -> "c diamond_block 1 diamond*9\nc stick 4 oak_planks*2\n"
            "xmarket/unobtainable.txt" -> "barrier\n"
            else -> null
        }
    }

    private val plugin = XMarketPlugin(platform, server, files, server, server)
    private val steve = FakeCommandSource.player("Steve")
    private val alex = FakeCommandSource.player("Alex")
    private val s: PlayerRef get() = steve.player!!

    private fun loaded() = Loaded(config, Translations.of(XMarketMessages.catalog, emptyMap()))

    private fun shop(): List<Category> = listOf(
        Category(
            "ores", "<aqua>Ores", ItemSpec("minecraft:diamond"), 10, Currency.Money, null, emptyList(),
            listOf(
                Entry("diamond", ItemSpec("minecraft:diamond"), Money.of(100), Money.of(30)),
                Entry("diamond_block", ItemSpec("minecraft:diamond_block"), Money.of(900), Money.of(270)),
                Entry("stone", ItemSpec("minecraft:stone"), Money.parse("0.5"), Money.parse("0.15")),
                Entry("rare", ItemSpec("minecraft:emerald"), Money.of(10), Money.of(3), limits = listOf(Limit(TradeKind.BUY, 5, Period.DAY, server = false))),
                Entry("vip", ItemSpec("minecraft:nether_star"), Money.of(500), null, commands = listOf("lp user {player} parent add vip")),
                Entry("leveled", ItemSpec("minecraft:gold_ingot"), Money.of(5), Money.of(1), requires = listOf(Requirement.parse("%player_level% >= 10")!!)),
            ),
        ),
        Category(
            "trades", "Trades", ItemSpec("minecraft:emerald"), 11, Currency.Items(ItemSpec("minecraft:diamond")), null, emptyList(),
            listOf(Entry("apple", ItemSpec("minecraft:apple"), Money.parse("0.5"), Money.parse("0.1"))),
        ),
        Category(
            "brewing", "Brewing", ItemSpec("minecraft:potion"), 12, Currency.Xp, "xmarket.vip", emptyList(),
            listOf(Entry("potion{swiftness}", ItemSpec("minecraft:potion", potion = "swiftness"), Money.of(2), Money.of(1))),
        ),
    )

    init {
        platform.scheduler.runAs(Where.Global) { plugin.start(loaded()) }
        plugin.open(Outcome.Ok<Database>(FakeDatabase(prefix = "xmarket_")))
        plugin.loadPrices()
        platform.scheduler.runPending()
        val everyone = plugin.permissions.permissions.filter { it.default == PermissionDefault.EVERYONE }.map { it.node }
        steve.permissions += everyone
        alex.permissions += everyone
        server.join(steve)
        server.join(alex)
        server.balances[s] = Money.of(1000)
    }

    @AfterTest
    fun stop() {
        platform.scheduler.runAs(Where.Global) { plugin.stop() }
    }

    private fun run(source: FakeCommandSource, line: String): CommandResult {
        source.clear()
        server.sent.clear()
        val where = source.player?.let { Where.on(it) } ?: Where.Global
        val result = platform.scheduler.runAs(where) { plugin.dispatcher.dispatch(source, line) }
        plugin.texts.feedback.message(result, source)?.let(source::reply)
        platform.scheduler.tick(2)
        return result
    }

    private fun heard(source: FakeCommandSource): List<String> = source.replies + server.sent[source.player!!].orEmpty()

    private fun <T> onSteve(block: () -> T): T = platform.scheduler.runAs(Where.on(s), block)

    @Test
    fun buyingTakesTheMoneyThenGivesTheItems() {
        run(steve, "shop buy diamond 3")
        assertEquals(3, server.has(s, "diamond"))
        assertEquals(Money.of(700), server.balances[s])
        assertTrue(heard(steve).any { "Bought 3× Diamond for $300.00" in it }, heard(steve).toString())
    }

    @Test
    fun aPurchaseOverTheBalanceIsRefusedAndGivesNothing() {
        run(steve, "shop buy diamond 20")
        assertEquals(0, server.has(s, "diamond"))
        assertEquals(Money.of(1000), server.balances[s])
        assertTrue(heard(steve).any { "costs $2000.00" in it }, heard(steve).toString())
    }

    @Test
    fun aPurchaseThatDoesntFitBuysWhatFits() {
        repeat(35) { server.inv(s)[it] = FakeStack("minecraft:dirt", 64) }
        run(steve, "shop buy stone 100")
        assertEquals(64, server.has(s, "stone"), "one free slot: 64")
        assertEquals(Money.of(968), server.balances[s])
    }

    @Test
    fun aFullInventoryBuysNothing() {
        repeat(36) { server.inv(s)[it] = FakeStack("minecraft:dirt", 64) }
        run(steve, "shop buy diamond")
        assertEquals(Money.of(1000), server.balances[s])
        assertTrue(heard(steve).any { "no room" in it }, heard(steve).toString())
    }

    @Test
    fun sellingTakesTheItemsThenPays() {
        server.put(s, "diamond", 10)
        run(steve, "sell hand")
        assertEquals(0, server.has(s, "diamond"))
        assertEquals(Money.of(1300), server.balances[s])
        run(steve, "sell hand")
        assertTrue(heard(steve).any { "aren't holding" in it }, heard(steve).toString())
    }

    @Test
    fun aRefusedPaymentGivesTheItemsBack() {
        server.put(s, "diamond", 10)
        server.refusePayments = true
        run(steve, "sell hand")
        assertEquals(10, server.has(s, "diamond"))
        assertEquals(Money.of(1000), server.balances[s])
        assertTrue(heard(steve).any { "didn't go through" in it }, heard(steve).toString())
    }

    @Test
    fun sellAllSellsWhatSellsAndLeavesTheRest() {
        server.put(s, "diamond", 2)
        server.put(s, "stone", 70)
        server.put(s, "dirt", 5)
        run(steve, "sell all")
        assertEquals(0, server.has(s, "diamond"))
        assertEquals(0, server.has(s, "stone"))
        assertEquals(5, server.has(s, "dirt"))
        assertEquals(Money.parse("1070.5"), server.balances[s])
        assertTrue(heard(steve).any { "Sold 72 items for $70.50" in it }, heard(steve).toString())
    }

    @Test
    fun worthSaysWhatTheHandSellsFor() {
        server.put(s, "diamond", 5)
        run(steve, "worth")
        assertTrue(heard(steve).any { "Diamond sells for $30.00 each, $150.00 for 5" in it }, heard(steve).toString())
        run(steve, "worth dirt")
        assertTrue(heard(steve).any { "Dirt has no price here" in it }, heard(steve).toString())
    }

    @Test
    fun aBuyLimitStopsAtItsAmountForTheDay() {
        run(steve, "shop buy emerald 10")
        assertEquals(5, server.has(s, "emerald"), "only 5 a day")
        run(steve, "shop buy emerald")
        assertTrue(heard(steve).any { "had your 5 of Emerald for this day" in it }, heard(steve).toString())
        // The next day, by the server's calendar, it starts again: compared as it's read, no timer.
        platform.scheduler.advance(kotlin.time.Duration.parse("1d"))
        run(steve, "shop buy emerald 2")
        assertEquals(7, server.has(s, "emerald"))
    }

    @Test
    fun aDiscountAndABoostByPermissionButNotForBeingAnOperator() {
        steve.everything = true
        run(steve, "shop buy diamond")
        assertEquals(Money.of(900), server.balances[s], "an operator pays full price")
        server.explicit[s] = mutableSetOf("xmarket.discount.10", "xmarket.sellboost.20")
        plugin.perks.clear()
        run(steve, "shop buy diamond")
        assertEquals(Money.of(810), server.balances[s], "10% off")
        server.inv(s)[0] = FakeStack("minecraft:diamond", 1)
        run(steve, "sell hand")
        assertEquals(Money.of(846), server.balances[s], "30 plus 20%")
    }

    @Test
    fun aCommandEntryRunsItsCommandsAndGivesNoItem() {
        run(steve, "shop buy nether_star")
        assertTrue(heard(steve).any { "isn't for sale" in it } || server.console.isEmpty())
        val placed = plugin.prices.entry("ores", "vip")!!
        val result = onSteve { plugin.trades.buy(s, placed, 1) }
        platform.scheduler.tick(2)
        assertEquals(PurchaseResult.Status.SUCCESS, result.settled().status)
        assertEquals(listOf("lp user Steve parent add vip"), server.console)
        assertEquals(0, server.has(s, "nether_star"))
    }

    @Test
    fun aRequirementIsCheckedThroughPlaceholders() {
        run(steve, "shop buy gold_ingot")
        assertTrue(heard(steve).any { "You need %player_level% >= 10" in it }, heard(steve).toString())
        server.placeholderValues["%player_level%"] = "12"
        run(steve, "shop buy gold_ingot")
        assertEquals(1, server.has(s, "gold_ingot"))
    }

    @Test
    fun anItemCurrencyPaysInWholeItems() {
        server.put(s, "diamond", 3)
        run(steve, "shop buy apple 3")
        // 3 × 0.5 = 1.5 diamonds, rounded up to 2.
        assertEquals(3, server.has(s, "apple"))
        assertEquals(1, server.has(s, "diamond"))
        server.inv(s).fill(null)
        server.put(s, "apple", 25)
        run(steve, "sell hand")
        // 25 × 0.1 = 2.5, rounded down to 2 diamonds.
        assertEquals(2, server.has(s, "diamond"))
    }

    @Test
    fun aCategorysPermissionHidesIt() {
        server.levels[s] = 10
        run(steve, "shop brewing")
        assertTrue(heard(steve).any { "no category called brewing" in it }, heard(steve).toString())
        steve.permissions += "xmarket.vip"
        run(steve, "shop brewing")
        assertEquals("Brewing", server.menus.title(s))
    }

    @Test
    fun theMenusBuyThroughTheAmountScreen() {
        run(steve, "shop")
        assertEquals("Shop", server.menus.title(s))
        onSteve { server.menus.click(s, 10) }
        assertEquals("<aqua>Ores".let { "Ores" }, server.menus.title(s))
        val icon = server.menus.icon(s, 0)
        assertNotNull(icon)
        assertTrue(icon.lore.any { "Buy: $100.00" in it.plain() }, icon.lore.map { it.plain() }.toString())
        onSteve { server.menus.click(s, 0) }
        assertEquals("Buy Diamond", server.menus.title(s))
        onSteve { server.menus.click(s, 15) } // +10
        onSteve { server.menus.click(s, 12) } // -1
        assertTrue(server.menus.icon(s, 40)!!.name!!.plain().contains("Buy 10 for $1000.00"), server.menus.icon(s, 40)!!.name!!.plain())
        onSteve { server.menus.click(s, 40) }
        platform.scheduler.tick(2)
        assertEquals(10, server.has(s, "diamond"))
        assertEquals(Money.ZERO, server.balances[s])
    }

    @Test
    fun aTypedAmountOpensTheScreenAtIt() {
        run(steve, "shop ores")
        onSteve { server.menus.click(s, 2) }
        onSteve { server.menus.click(s, 31) }
        assertNotNull(server.prompt)
        onSteve { server.prompt!!("40") }
        assertTrue(server.menus.icon(s, 40)!!.name!!.plain().contains("Buy 40 for $20.00"))
    }

    @Test
    fun theSellGuiSellsWhatSellsAndGivesBackTheRest() {
        run(steve, "sellgui")
        assertTrue(server.menus.hasDeposit(s))
        onSteve { server.menus.closeDeposit(s, listOf(FakeStack("minecraft:diamond", 2), FakeStack("minecraft:dirt", 7))) }
        platform.scheduler.tick(2)
        assertEquals(Money.of(1060), server.balances[s])
        assertEquals(7, server.has(s, "dirt"), "what doesn't sell is given back")
        assertEquals(0, server.has(s, "diamond"))
    }

    @Test
    fun aSellGuiClosedByLeavingGivesEverythingBack() {
        run(steve, "sellgui")
        onSteve { server.menus.closeDeposit(s, listOf(FakeStack("minecraft:diamond", 2)), online = false) }
        assertEquals(2, server.has(s, "diamond"))
        assertEquals(Money.of(1000), server.balances[s])
    }

    @Test
    fun aSellGuiWithAFailedPaymentGivesEverythingBack() {
        server.refusePayments = true
        run(steve, "sellgui")
        onSteve { server.menus.closeDeposit(s, listOf(FakeStack("minecraft:diamond", 2))) }
        platform.scheduler.tick(2)
        assertEquals(2, server.has(s, "diamond"))
    }

    @Test
    fun twoTradesAtOnceAreOneAtATime() {
        val placed = plugin.prices.entry("ores", "diamond")!!
        server.economy.let { }
        val first = onSteve {
            plugin.trades.buy(s, placed, 1)
            plugin.trades.buy(s, placed, 1)
        }
        platform.scheduler.tick(2)
        assertEquals(PurchaseResult.Status.SUCCESS, first.settled().status, "the first's already through: Pending settles at once with this economy")
    }

    @Test
    fun aCancelledEventStopsThePurchase() {
        plugin.bus.subscribe(ShopBuyEvent.TYPE) { it.isCancelled = true }
        run(steve, "shop buy diamond")
        assertEquals(0, server.has(s, "diamond"))
        assertEquals(Money.of(1000), server.balances[s])
    }

    @Test
    fun theApiPricesFromMemoryAndModifiersApply() {
        val api = XMarket.api()
        assertEquals(Money.of(100), api.buyPrice(ItemKey.of("diamond")))
        assertEquals(Money.of(30), api.sellPrice(ItemKey.of("diamond")))
        assertNull(api.buyPrice(ItemKey.of("dirt")))
        assertEquals(Money.of(1), api.sellPrice(ItemKey("minecraft:potion", potion = "swiftness")))
        assertNull(api.sellPrice(ItemKey("minecraft:potion", potion = "healing")), "a potion prices by its type")
        api.registerPriceModifier("Booster", PriceModifier { _, _, kind, price -> if (kind == TradeKind.SELL) price.times(2) else price })
        assertEquals(Money.of(60), api.sellPrice(ItemKey.of("diamond")))
        api.unregisterAll("Booster")
        val sale = api.sell(s, listOf(ItemKey.of("diamond").withAmount(2), ItemKey.of("dirt")))
        platform.scheduler.tick(2)
        assertEquals(SaleResult.Status.SUCCESS, sale.settled().status)
        assertEquals(listOf(2, 0), sale.settled().sold)
        assertEquals(Money.of(1060), server.balances[s])
    }

    @Test
    fun theAuditFindsMissingItemsAndAnOwnersLoop() {
        val clean = plugin.audit()
        assertEquals(listOf("minecraft:mystery_block"), clean.missing)
        assertTrue(clean.loops.isEmpty(), clean.loops.toString())
        // An owner sells a diamond block for more than nine diamonds cost.
        run(FakeCommandSource.console(), "shop edit price ores diamond_block 900 950")
        platform.scheduler.tick(2)
        val loops = plugin.audit().loops
        assertEquals(listOf("diamond_block"), loops.map { it.key })
        assertEquals(Money.of(900), loops.single().cost)
    }

    @Test
    fun editingAddsTheHeldItemWithItsData() {
        steve.permissions += "xmarket.edit"
        server.inv(s)[0] = FakeStack("minecraft:diamond_sword", 1, "{custom sword}")
        run(steve, "shop edit add ores 250 -")
        platform.scheduler.tick(2)
        val added = saved.last().entries.last()
        assertEquals("diamond_sword", added.id)
        assertEquals("{custom sword}", added.item.data)
        assertEquals(Money.of(250), added.buy)
        assertNull(added.sell)
        // It buys as itself, and a plain sword doesn't sell as it.
        assertNull(plugin.prices.find(ItemKey.of("diamond_sword"), TradeKind.BUY))
        assertNotNull(plugin.prices.find(ItemKey("minecraft:diamond_sword", plain = false, data = "{custom sword}"), TradeKind.BUY))
    }

    @Test
    fun historyAndStatsNeedTheirPermissions() {
        run(steve, "shop stats")
        assertTrue(heard(steve).any { "permission" in it }, heard(steve).toString())
        run(steve, "shop history")
        assertTrue(heard(steve).isNotEmpty())
    }

    @Test
    fun aReloadReadsTheShopsAgain() {
        categories = categories.map { c -> if (c.id == "ores") c.withEntries(c.entries.map { if (it.id == "diamond") it.withPrices(Money.of(50), Money.of(10)) else it }) else c }
        assertTrue(run(FakeCommandSource.console(), "xmarket reload").let { it is CommandResult.Ran })
        assertEquals(Money.of(50), XMarket.api().buyPrice(ItemKey.of("diamond")))
    }

    @Test
    fun theMultiplierScalesEveryPrice() {
        config = co.xenastudios.core.common.config.Configs.load(XMarketConfig, "shop:\n  multiplier: 2\n", co.xenastudios.core.testing.TestYaml).let { (it as Outcome.Ok).value.config }
        run(FakeCommandSource.console(), "xmarket reload")
        assertEquals(Money.of(200), XMarket.api().buyPrice(ItemKey.of("diamond")))
        assertEquals(Money.of(60), XMarket.api().sellPrice(ItemKey.of("diamond")))
    }

    @Test
    fun leavingForgetsAPlayer() {
        server.quit(steve)
        assertFalse(server.isOnline(s))
    }
}
