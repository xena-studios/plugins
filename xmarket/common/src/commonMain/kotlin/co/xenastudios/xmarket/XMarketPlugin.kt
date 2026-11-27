package co.xenastudios.xmarket

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.event.EventBus
import co.xenastudios.core.api.map
import co.xenastudios.core.api.newPending
import co.xenastudios.core.api.onSettled
import co.xenastudios.core.api.pendingOf
import co.xenastudios.core.api.resolve
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.admin.Debug
import co.xenastudios.core.common.admin.Imports
import co.xenastudios.core.common.admin.PasteService
import co.xenastudios.core.common.admin.PlayerDataRegistry
import co.xenastudios.core.common.admin.RecentErrors
import co.xenastudios.core.common.command.AdminCommands
import co.xenastudios.core.common.command.CommandDispatcher
import co.xenastudios.core.common.command.CommandRegistrar
import co.xenastudios.core.common.command.CommandSource
import co.xenastudios.core.common.command.ModuleReloader
import co.xenastudios.core.common.command.OnlinePlayers
import co.xenastudios.core.common.concurrentMap
import co.xenastudios.core.common.config.Config
import co.xenastudios.core.common.db.Database
import co.xenastudios.core.common.db.migrate
import co.xenastudios.core.common.event.SimpleEventBus
import co.xenastudios.core.common.family.PlayerCache
import co.xenastudios.core.common.i18n.LanguageSource
import co.xenastudios.core.common.i18n.Translations
import co.xenastudios.core.common.locked
import co.xenastudios.core.common.module.Module
import co.xenastudios.core.common.module.ModuleContext
import co.xenastudios.core.common.module.ModuleSet
import co.xenastudios.core.common.permission.PermissionRegistrar
import co.xenastudios.core.common.platform.Platform
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.scheduler.now
import co.xenastudios.core.common.update.UpdateChecker
import co.xenastudios.core.common.update.UpdateSettings
import co.xenastudios.xmarket.api.ItemKey
import co.xenastudios.xmarket.api.Money
import co.xenastudios.xmarket.api.PriceModifier
import co.xenastudios.xmarket.api.PriceProvider
import co.xenastudios.xmarket.api.PurchaseResult
import co.xenastudios.xmarket.api.SaleResult
import co.xenastudios.xmarket.api.ShopEntry
import co.xenastudios.xmarket.api.TradeKind
import co.xenastudios.xmarket.api.XMarket
import co.xenastudios.xmarket.api.XMarketApi
import co.xenastudios.xmarket.config.XMarketConfig
import co.xenastudios.xmarket.price.Audit
import co.xenastudios.xmarket.price.Category
import co.xenastudios.xmarket.price.Currency
import co.xenastudios.xmarket.price.Entry
import co.xenastudios.xmarket.price.ItemSpec
import co.xenastudios.xmarket.price.PriceList
import co.xenastudios.xmarket.price.RecipeBook
import co.xenastudios.xmarket.store.Store
import kotlin.concurrent.Volatile

/** What the plugin's config files said when last read: the config, the lang files, and the problems found. */
public class Loaded(
    public val config: Config,
    public val translations: Translations,
    public val problems: List<String> = emptyList(),
)

/** What `shops/` held when last read: its categories, and each problem, in English. */
public class Shops(public val categories: List<Category>, public val problems: List<String> = emptyList())

/** xMarket's files: on the JVM, `JvmFiles`. */
public interface Files {
    /** `config.yml` and `lang/`. */
    public fun load(): Loaded

    /**
     * Every `shops/<id>.yml`, a category each; the shipped price list written there first when
     * the folder doesn't exist yet. [currency] is a category's when its file doesn't say. Off
     * the server's threads: it reads files.
     */
    public fun shops(currency: Currency): Shops

    /** Writes [category] to its file (to a temporary file, then renamed); the reason in English if it couldn't. */
    public fun save(category: Category): String?

    /** A text file shipped in the jar, `xmarket/recipes.txt`; `null` if there's none. */
    public fun resource(name: String): String?
}

/**
 * xMarket, the same on every platform: each adapter makes one with its [Platform] and its
 * [MarketServer], reads the config ([Files]) and calls [start] as the plugin enables, then
 * [loadPrices] and [open]s the database off the server's threads. Until the price list is in,
 * commands say it's loading.
 */
public class XMarketPlugin(
    public val platform: Platform,
    public val server: MarketServer,
    private val files: Files,
    languages: LanguageSource,
    private val players: OnlinePlayers,
    private val cache: PlayerCache? = null,
    private val registrar: CommandRegistrar? = null,
    private val permissionRegistrar: PermissionRegistrar? = null,
    about: () -> Map<String, String> = { emptyMap() },
    paste: PasteService? = null,
    /** The other shop plugins' data, for `/xmarket import`; `null` where there's none to read (tests). */
    importers: (XMarketPlugin) -> Imports? = { null },
    override val version: String = VERSION,
) : XMarketApi {
    public val log: RecentErrors = RecentErrors(platform.log, platform.clock)
    public val texts: Texts = Texts(languages) { server }
    public val bus: SimpleEventBus = SimpleEventBus(log)
    override val events: EventBus get() = bus
    public val modules: ModuleSet = ModuleSet(platform)

    @Volatile
    public var config: Config = XMarketConfig.defaults
        private set

    @Volatile
    internal var problems: List<String> = emptyList()
        private set

    /** The price list, after the multiplier; empty until loaded. */
    @Volatile
    public var prices: PriceList = PriceList.EMPTY
        private set

    /** Whether the price list is in, so trades can run. */
    @Volatile
    public var ready: Boolean = false
        private set

    @Volatile
    public var store: Store? = null
        private set

    @Volatile
    public var failure: String? = null
        private set

    public val opened: Pending<Boolean> = newPending()

    @Volatile
    public var running: Boolean = false
        private set

    // Made on first use, so a server that's just started loads none of them (decisions/2026-11-25-a-lighter-core.md).
    @Volatile
    private var madeLimits: Limits? = null
    public val limits: Limits get() = madeLimits ?: locked(this) { madeLimits ?: Limits(Clock(), log).also { l -> l.store = store; l.offsetHours = config[XMarketConfig.rules.timezone]; madeLimits = l } }

    @Volatile
    private var madePerks: Perks? = null
    public val perks: Perks get() = madePerks ?: locked(this) { madePerks ?: Perks(this).also { madePerks = it } }

    @Volatile
    private var madeTrades: Trades? = null
    public val trades: Trades get() = madeTrades ?: locked(this) { madeTrades ?: Trades(this).also { madeTrades = it } }

    @Volatile
    private var madeWallets: Wallets? = null
    public val wallets: Wallets get() = madeWallets ?: locked(this) { madeWallets ?: Wallets(this).also { madeWallets = it } }

    // The time, for the limits: one class rather than a lambda.
    private inner class Clock : () -> Long {
        override fun invoke(): Long = now()
    }

    @Volatile
    private var madeUi: Ui? = null
    public val ui: Ui get() = madeUi ?: locked(this) { madeUi ?: Ui(this).also { madeUi = it } }

    public val admin: AdminCommands = AdminCommands(
        PLUGIN_ID, NAME, platform, reloader(),
        debug = object : () -> Debug {
            override fun invoke() = AdminParts.debug(this@XMarketPlugin, about, paste)
        },
        imports = importers(this),
        playerData = object : () -> PlayerDataRegistry {
            override fun invoke() = AdminParts.playerData(this@XMarketPlugin, cache)
        },
        text = texts.admin,
    )

    private val commands = Commands(this)

    @Volatile
    public var dispatcher: CommandDispatcher = CommandDispatcher(commands.tree(config), platform.clock, log)
        private set

    @Volatile
    public var permissions: XMarketPermissions = commands.permissions
        private set

    @Volatile
    public var updates: UpdateChecker? = null

    public val updateSettings: UpdateSettings get() = XMarketConfig.updates.settings(config)

    private val modifiers = concurrentMap<PriceModifier, String>()
    private val providers = concurrentMap<PriceProvider, String>()

    internal fun now(): Long = platform.clock.now().toEpochMilliseconds()

    public fun start(loaded: Loaded) {
        running = true
        apply(loaded)
    }

    /**
     * Reads `shops/` and puts the price list in place: as the server starts (the adapter calls it
     * off the server's threads, so no file is read there) and on every reload. Returns the problems.
     */
    public fun loadPrices(): List<String> {
        val read = try {
            files.shops(config[XMarketConfig.shop.currency])
        } catch (e: Exception) {
            Shops(emptyList(), listOf("Couldn't read shops/: $e"))
        }
        read.problems.forEach(log::warn)
        prices = PriceList(read.categories, XMarketConfig.multiplierPercent(config))
        ready = true
        val entries = read.categories.sumOf { it.entries.size }
        log.info("Loaded $entries items in ${read.categories.size} categories.")
        XMarket.register(this)
        return read.problems
    }

    /** The database the adapter opened (off the server's threads), or why it couldn't be. */
    public fun open(result: Outcome<Database, String>) {
        val db = when (result) {
            is Outcome.Ok -> result.value
            is Outcome.Err -> return fail(result.error)
        }
        db.migrate(Store.MIGRATIONS).onSettled { value, error ->
            if (value !is Outcome.Ok) return@onSettled fail("couldn't bring ${db.description}'s tables up to date: ${(value as? Outcome.Err)?.error ?: error}")
            val s = Store(db)
            store = s
            madeLimits?.let { l ->
                l.store = s
                l.load(Limits.SERVER)
                for (player in server.online()) l.load(player)
            }
            log.info("History and limits are kept in ${db.description}.")
            opened.resolve(true)
        }
    }

    private fun fail(reason: String) {
        failure = reason
        log.error("$reason $NAME keeps no history or limits without its database: fix storage in config.yml and restart. The shop still works.")
        opened.resolve(false)
    }

    /** Turns the plugin off: every menu closed (a `/sellgui`'s items back to its player), then the database once what's queued is saved. */
    public fun stop(): Pending<Unit> {
        running = false
        try {
            server.menus.closeAll()
        } catch (e: Exception) {
            log.warn("Closing the menus failed: $e")
        }
        XMarket.register(null)
        updates?.close()
        updates = null
        modules.clear()
        registrar?.clear()
        permissionRegistrar?.sync(emptyList())
        val s = store ?: return pendingOf(Unit)
        store = null
        madeLimits?.store = null
        return s.close()
    }

    @Suppress("UNCHECKED_CAST")
    private fun reloader(): ModuleReloader =
        ModuleReloader(modules, reloadFiles = Reloading(0) as () -> List<String>, wanted = Reloading(1) as () -> Map<String, () -> Module>)

    private inner class Reloading(private val kind: Int) : () -> Any {
        override fun invoke(): Any = if (kind == 0) apply(files.load()) + loadPrices() else wanted()
    }

    @Volatile
    public var beforeFirstEvent: (() -> Unit)? = null

    internal fun eventsReady() {
        val bridge = beforeFirstEvent ?: return
        val run = locked(this) { if (beforeFirstEvent === bridge) true.also { beforeFirstEvent = null } else false }
        if (run) bridge()
    }

    /** Uses what [Files] read: the commands rebuilt, the cached perks forgotten. On the global thread. */
    public fun apply(loaded: Loaded): List<String> {
        config = loaded.config
        texts.translations = loaded.translations
        texts.money = { m -> economy()?.format(m) }
        server.renamedIsPlain = config[XMarketConfig.sell.renamed]
        madeLimits?.offsetHours = config[XMarketConfig.rules.timezone]
        madePerks?.clear()
        dispatcher = CommandDispatcher(commands.tree(config), platform.clock, log)
        permissions = commands.permissions
        permissionRegistrar?.sync(permissions.permissions + admin.permissions.permissions)
        val warnings = ArrayList<String>()
        warnings += registrar?.sync(dispatcher).orEmpty() + commands.warnings
        warnings.forEach(log::warn)
        problems = loaded.problems + warnings
        updates?.apply(updateSettings)
        modules.sync(wanted())
        return problems
    }

    private fun wanted(): Map<String, () -> Module> = mapOf("players" to PLAYERS)

    private val PLAYERS = object : () -> Module {
        override fun invoke(): Module = Listen()
    }

    /** Each player's limits read as they join; everything about them forgotten as they leave. */
    private inner class Listen : Module, PlayerEvents {
        private var handle: AutoCloseable? = null

        override fun enable(context: ModuleContext) {
            handle = server.watchPlayers(this)
        }

        override fun disable() {
            handle?.close()
            handle = null
        }

        override fun joined(player: PlayerRef, name: String) {
            // Only once a limit's been asked about: until then there's nothing to keep.
            if (store != null) madeLimits?.load(player)
        }

        override fun quit(player: PlayerRef) {
            madeLimits?.forget(player)
            madePerks?.forget(player)
            madeTrades?.forget(player)
        }
    }

    /** The economy money goes through, as `shop.economy` says; `null` without one. */
    public fun economy(): Economy? = server.economy(config[XMarketConfig.shop.economy])

    /** [price] after every registered [PriceModifier], in the order they were registered. */
    internal fun modify(player: PlayerRef?, placed: PriceList.Placed, kind: TradeKind, price: Money): Money {
        if (modifiers.isEmpty()) return price
        var p = price
        val entry = placed.api
        for ((m, owner) in modifiers.entries.toList()) {
            p = try {
                m.modify(player, entry, kind, p).takeIf { !it.isNegative } ?: p
            } catch (e: Exception) {
                log.warn("$owner's price modifier failed: $e")
                p
            }
        }
        return p
    }

    private val provided = Category("provided", "Provided", ItemSpec("minecraft:chest"), -1, Currency.Money, null, emptyList(), emptyList())

    /** An item no entry prices, as a [PriceProvider] prices it: sold in money, never bought. */
    internal fun provided(item: ItemKey): PriceList.Placed? {
        if (providers.isEmpty()) return null
        for ((p, owner) in providers.entries.toList()) {
            val price = try {
                p.sellPrice(item.withAmount(1))
            } catch (e: Exception) {
                log.warn("$owner's price provider failed: $e")
                null
            } ?: continue
            if (price.isNegative) continue
            val spec = ItemSpec(item.id, item.potion, item.enchantments, item.data)
            return PriceList.Placed(provided, Entry(item.id.removePrefix("minecraft:"), spec, null, price))
        }
        return null
    }

    /** `/shop audit`'s findings: off the server's threads, it reads the recipes. */
    public fun audit(): AuditReport {
        val list = prices
        val known = try {
            server.knownItems()
        } catch (e: Exception) {
            emptySet()
        }
        val unobtainable = files.resource("xmarket/unobtainable.txt")?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() && !it.startsWith("#") }?.map { ItemKey.namespaced(it) }?.toSet().orEmpty()
        val missing = known.filter { it !in list.ids && it !in unobtainable && !it.endsWith("_spawn_egg") && it != "minecraft:air" }.sorted()
        val buy = HashMap<String, Money>()
        val sell = HashMap<String, Money>()
        for (p in list.all()) {
            if (p.entry.commands.isNotEmpty() || p.entry.item.data != null || p.currency !is Currency.Money) continue
            val per = p.entry.amount.toLong()
            p.entry.buy?.let { b -> buy[p.entry.item.key] = minOf(buy[p.entry.item.key] ?: b.divide(per, true), b.divide(per, true)) }
            p.entry.sell?.let { s -> sell[p.entry.item.key] = maxOf(sell[p.entry.item.key] ?: s.divide(per, false), s.divide(per, false)) }
        }
        val book = files.resource("xmarket/recipes.txt")?.let(RecipeBook::parse) ?: RecipeBook(emptyList())
        return AuditReport(missing, Audit.loops(buy, sell, book))
    }

    /** Writes [categories] (each a whole file), then the price list from them; [done] on [source]'s thread, or a message why not. */
    internal fun save(categories: List<Category>, source: CommandSource, done: () -> Unit) {
        platform.scheduler.run(Where.Async) {
            var error: Pair<String, String>? = null
            for (c in categories) {
                val e = files.save(c)
                if (e != null) {
                    error = c.id to e
                    break
                }
            }
            if (error == null) for (c in categories) replace(c)
            platform.scheduler.now(source.player?.let { Where.on(it) } ?: Where.Global) {
                val e = error
                if (e == null) done() else texts.reply(source, XMarketMessages.editNotSaved, "category" to e.first, "reason" to e.second)
            }
        }
    }

    /** Puts [category] in the price list in memory, in its old place or after the rest: an import's or an edit's, once its file is written. */
    public fun replace(category: Category) {
        locked(this) {
            val now = prices.categories
            val list = if (now.any { it.id == category.id }) now.map { if (it.id == category.id) category else it } else now + category
            prices = PriceList(list, XMarketConfig.multiplierPercent(config))
        }
    }

    /** A player called [name], online or not, with their name as last seen; `null` if no one by that name has played here. */
    internal fun findPlayer(name: String): Pending<Pair<PlayerRef, String>?> {
        server.online().firstOrNull { server.name(it).equals(name, ignoreCase = true) }?.let { return pendingOf(it to (server.name(it) ?: name)) }
        val c = cache ?: return pendingOf(null)
        return c.lookup(name).map { found -> (found as? Outcome.Ok)?.value?.let { it.player to it.name } }
    }

    // ---- the API ----

    override val isLoaded: Boolean get() = ready

    override fun buyPrice(item: ItemKey): Money? = prices.find(item, TradeKind.BUY)?.let { trades.unitBuy(null, it) }

    override fun sellPrice(item: ItemKey): Money? = (prices.find(item, TradeKind.SELL) ?: provided(item))?.let { trades.unitSell(null, it) }

    override fun sellPrice(player: PlayerRef, item: ItemKey): Money? = (prices.find(item, TradeKind.SELL) ?: provided(item))?.let { trades.unitSell(player, it) }

    override fun isSellable(item: ItemKey): Boolean = sellPrice(item) != null

    override fun entry(item: ItemKey): ShopEntry? = prices.find(item)?.api

    override fun entry(category: String, id: String): ShopEntry? = prices.entry(category, id)?.api

    override fun entries(): List<ShopEntry> = prices.all().map { it.api }

    override fun sell(player: PlayerRef, items: List<ItemKey>): Pending<SaleResult> = onPlayer(player, SaleResult(SaleResult.Status.UNAVAILABLE, items.map { 0 }, emptyMap())) {
        trades.sell(player, items.map { Trades.Offer(it) }, Trades.Source.API)
    }

    override fun buy(player: PlayerRef, entry: ShopEntry, amount: Int): Pending<PurchaseResult> {
        val placed = prices.entry(entry.category, entry.id) ?: return pendingOf(PurchaseResult(PurchaseResult.Status.NOT_FOR_SALE, 0, Money.ZERO))
        return onPlayer(player, PurchaseResult(PurchaseResult.Status.UNAVAILABLE, 0, Money.ZERO)) { trades.buy(player, placed, amount, tell = false) }
    }

    // Runs [work] on [player]'s thread, settling with what it settles with; [gone] if they left first.
    private fun <T> onPlayer(player: PlayerRef, gone: T, work: () -> Pending<T>): Pending<T> {
        val out = newPending<T>()
        if (!server.isOnline(player)) return pendingOf(gone)
        platform.scheduler.now(Where.on(player), retired = { out.resolve(gone) }) {
            work().onSettled { v, e -> out.resolve(if (e != null || v == null) gone else v) }
        }
        return out
    }

    override fun registerPriceModifier(owner: String, modifier: PriceModifier) {
        modifiers[modifier] = owner
    }

    override fun registerPriceProvider(owner: String, provider: PriceProvider) {
        providers[provider] = owner
    }

    override fun unregisterAll(owner: String) {
        for ((m, o) in modifiers.entries.toList()) if (o == owner) modifiers.remove(m)
        for ((p, o) in providers.entries.toList()) if (o == owner) providers.remove(p)
    }

    public companion object {
        public const val PLUGIN_ID: String = "xmarket"
        public const val NAME: String = "xMarket"

        /** xMarket's version. The adapters' builds check theirs is the same. */
        public const val VERSION: String = "1.0.0"
    }
}
