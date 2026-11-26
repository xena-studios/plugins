package co.xenastudios.xmoney

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.event.EventBus
import co.xenastudios.core.api.map
import co.xenastudios.core.api.newPending
import co.xenastudios.core.api.onSettled
import co.xenastudios.core.api.pendingOf
import co.xenastudios.core.api.resolve
import co.xenastudios.core.common.Outcome as DbOutcome
import co.xenastudios.core.common.admin.Debug
import co.xenastudios.core.common.admin.HealthChecks
import co.xenastudios.core.common.admin.PasteService
import co.xenastudios.core.common.admin.PlayerData
import co.xenastudios.core.common.admin.PlayerDataRegistry
import co.xenastudios.core.common.admin.PlayerLookup
import co.xenastudios.core.common.family.PlayerCache
import co.xenastudios.core.common.admin.RecentErrors
import co.xenastudios.core.common.command.AdminCommands
import co.xenastudios.core.common.command.CommandDispatcher
import co.xenastudios.core.common.command.CommandRegistrar
import co.xenastudios.core.common.command.ModuleReloader
import co.xenastudios.core.common.config.Config
import co.xenastudios.core.common.db.Database
import co.xenastudios.core.common.db.migrate
import co.xenastudios.core.common.event.SimpleEventBus
import co.xenastudios.core.common.i18n.LanguageSource
import co.xenastudios.core.common.i18n.Translations
import co.xenastudios.core.common.isFatal
import co.xenastudios.core.common.locked
import co.xenastudios.core.common.module.Module
import co.xenastudios.core.common.module.ModuleSet
import co.xenastudios.core.common.permission.PermissionRegistrar
import co.xenastudios.core.common.platform.Platform
import co.xenastudios.core.common.scheduler.Task
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.scheduler.every
import co.xenastudios.core.common.scheduler.later
import co.xenastudios.core.common.update.UpdateChecker
import co.xenastudios.core.common.update.UpdateSettings
import co.xenastudios.xmoney.api.Amount
import co.xenastudios.xmoney.api.Currency
import co.xenastudios.xmoney.api.HistoryEntry
import co.xenastudios.xmoney.api.HistoryPage
import co.xenastudios.xmoney.api.TopEntry
import co.xenastudios.xmoney.api.TransactionHook
import co.xenastudios.xmoney.api.TransactionKind
import co.xenastudios.xmoney.api.TransactionResult
import co.xenastudios.xmoney.api.XMoney
import co.xenastudios.xmoney.api.XMoneyApi
import co.xenastudios.xmoney.api.event.BalanceChangedEvent
import co.xenastudios.xmoney.api.event.PreTransactionEvent
import co.xenastudios.xmoney.config.XMoneyConfig
import co.xenastudios.xmoney.ledger.Account
import co.xenastudios.xmoney.ledger.Cause
import co.xenastudios.xmoney.ledger.Feed
import co.xenastudios.xmoney.ledger.Ledger
import co.xenastudios.xmoney.ledger.LedgerHooks
import co.xenastudios.xmoney.ledger.Outcome
import co.xenastudios.xmoney.ledger.SavedChange
import co.xenastudios.xmoney.ledger.Tables
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** What the plugin's files said when they were last read: the config, the lang files, and the problems found. */
public class Loaded(
    public val config: Config,
    public val translations: Translations,
    /** Each problem, in English, already logged. */
    public val problems: List<String> = emptyList(),
)

/** xMoney's files: on the JVM, `JvmFiles`. [load] reads `config.yml` and `lang/`. */
public fun interface Files {
    public fun load(): Loaded
}

/**
 * Another economy's balances, for `/xmoney import`: [read] off the server's threads (or on the
 * thread the other plugin needs, for Vault), never failing, with what it found or why not.
 */
public interface BalanceSource {
    /** How the console and messages name it: `EssentialsX`. */
    public val name: String

    public fun read(): Pending<Read>

    /** One balance it has: the player's uuid and name, and the amount as the plugin wrote it. */
    public class Found(public val id: PlayerRef, public val name: String, public val player: Boolean, public val balance: String)

    /** What [read] found: [found], or [missing] (nothing to import, and why) or [failed] (couldn't read it, and why). */
    public class Read(public val found: List<Found>, public val missing: String? = null, public val failed: String? = null)
}

/**
 * xMoney, the same on every platform: each adapter makes one with its [Platform] and its
 * [MoneyServer], reads the files ([Files]) and calls [start] as the plugin enables, then [open]s
 * the database off the server's threads. Until it's open, money commands say so, and the
 * adapters' synchronous calls (Vault) wait for it ([ready]). Everything the config says applies
 * on `/xmoney reload`, but where the data is (`storage`, `network`), which is read once.
 */
public class XMoneyPlugin(
    internal val platform: Platform,
    /** What the adapter does to players. */
    public val server: MoneyServer,
    private val files: Files,
    languages: LanguageSource,
    private val registrar: CommandRegistrar? = null,
    private val permissionRegistrar: PermissionRegistrar? = null,
    /** Finds a player by name for `/xmoney forget`: the family's player cache, asked only when the command runs. */
    players: PlayerCache? = null,
    /** What `/xmoney debug`'s report says about the server. */
    about: () -> Map<String, String> = { emptyMap() },
    /** Where `/xmoney debug` uploads its report; `null` to write a file. */
    paste: PasteService? = null,
    override val version: String = VERSION,
) : XMoneyApi {

    /** Where the plugin's console lines go; the last 50 warnings and errors are kept for `/xmoney debug`. */
    public val log: RecentErrors = RecentErrors(platform.log, platform.clock)

    /** The messages, in each reader's language. */
    public val texts: Texts = Texts(languages)

    /** The API's events. On Bukkit the adapter bridges them to Bukkit events. */
    public val bus: SimpleEventBus = SimpleEventBus(log)

    override val events: EventBus get() = bus

    /**
     * What the adapter does just before the first change, or the first add-on hook: Bukkit's
     * bridges the API's events to Bukkit events there, so their classes don't load as the server
     * starts. Set before [open].
     */
    public var beforeFirstChange: (() -> Unit)? = null

    /** The modules: pruning the ledger, while `ledger.keep-days` is set. */
    public val modules: ModuleSet = ModuleSet(platform)

    /** The server software, for `/xmoney`. */
    public val platformName: String get() = platform.name

    /** The config and what's worked out from it, swapped whole on reload. */
    public class Compiled(public val config: Config, public val format: CurrencyFormat, public val max: Long, public val start: Long)

    @Volatile
    public var compiled: Compiled = compile(XMoneyConfig.defaults)
        private set

    /** The config in use. */
    public val config: Config get() = compiled.config

    @Volatile
    internal var problems: List<String> = emptyList()
        private set

    /** The ledger, once the database is open; `null` before, or if it couldn't be. */
    @Volatile
    public var ledger: Ledger? = null
        private set

    @Volatile
    internal var database: Database? = null
        private set

    // On a network only: a single server never loads the change feed's classes.
    @Volatile
    private var feed: Feed? = null

    /** Why there's no database, once opening it failed. */
    @Volatile
    public var failure: String? = null
        private set

    /** Settled `true` once the balances are read and the ledger runs, `false` if the database couldn't be opened. */
    public val ready: Pending<Boolean> = newPending()

    /** `/xmoney reload`, `debug`, `migrate` and `forget`. */
    public val admin: AdminCommands = AdminCommands(
        PLUGIN_ID, NAME, platform,
        ModuleReloader(modules, reloadFiles = ::reload, wanted = ::wanted),
        // Objects, not lambdas, so neither class loads until the command runs (AdminCommands), and
        // what they make is in AdminParts, which loads with it.
        debug = object : () -> Debug {
            override fun invoke() = AdminParts.debug(this@XMoneyPlugin, about, paste)
        },
        playerData = object : () -> PlayerDataRegistry {
            override fun invoke() = AdminParts.playerData(this@XMoneyPlugin, players)
        },
        text = texts.admin,
    )

    private val commands = Commands(this)

    /** The importers the adapter offers, by the id `/xmoney import` takes. */
    @Volatile
    public var sources: BalanceSources? = null

    /** What runs the commands; built again on reload, from the new config. */
    @Volatile
    public var dispatcher: CommandDispatcher = CommandDispatcher(commands.tree(config), platform.clock, log)
        private set

    /** The permissions the config declares. */
    @Volatile
    public var permissions: XMoneyPermissions = commands.permissions
        private set

    /** The platform's update checker, which the adapter starts; every reload gives it the config's settings again. */
    @Volatile
    public var updates: UpdateChecker? = null

    /** What the config says about checking for updates. */
    public val updateSettings: UpdateSettings get() = XMoneyConfig.updates.settings(config)

    /** The placeholders, which read balances and the top list from memory. */
    public val placeholders: Placeholders
        get() = madePlaceholders ?: Placeholders(this).also { madePlaceholders = it }

    // Made when PlaceholderAPI or zChat first asks, not as the server starts.
    @Volatile
    private var madePlaceholders: Placeholders? = null

    /** Turns the plugin on with what [Files] read: permissions and commands. The database comes with [open]. */
    public fun start(loaded: Loaded) {
        apply(loaded)
    }

    /**
     * The database the adapter opened (off the server's threads), or why it couldn't: its tables
     * brought up to date, every balance read into memory, the change feed started on a network,
     * then the API registered and [ready] settled. On the database's threads.
     */
    public fun open(opened: DbOutcome<Database, String>, network: Boolean) {
        val db = when (opened) {
            is DbOutcome.Ok -> opened.value
            is DbOutcome.Err -> return fail(opened.error)
        }
        database = db
        db.migrate(Tables.MIGRATIONS).onSettled(Opening(db, network))
    }

    /**
     * Opening the database once the adapter has connected: its tables brought up to date, the
     * change feed started on a network, every balance read into memory, then the ledger runs.
     * One class for the steps rather than a lambda each (each would be a class of its own, made
     * as the server starts: decisions/2026-11-25-a-lighter-core.md). On the database's threads.
     */
    private inner class Opening(private val db: Database, private val network: Boolean) :
        (Any?, Throwable?) -> Unit, (Task) -> Unit {
        private var reading: Ledger? = null

        // The migration's outcome, then the balances read.
        override fun invoke(value: Any?, error: Throwable?) {
            val ledger = reading
            if (ledger == null) migrated(value, error) else stored(ledger, value, error)
        }

        private fun migrated(migrated: Any?, error: Throwable?) {
            if (migrated !is DbOutcome.Ok<*>) return fail("couldn't bring ${db.description}'s tables up to date: ${(migrated as? DbOutcome.Err<*>)?.error ?: error}")
            if (!network) return read(null)
            val settings = co.xenastudios.core.common.db.NetworkSettings(true, config[XMoneyConfig.network.checkEvery].seconds)
            Feed.open(db, settings, platform).onSettled { feed, _ -> read(feed) }
        }

        private fun read(feed: Feed?) {
            this@XMoneyPlugin.feed = feed
            val ledger = Ledger(co.xenastudios.xmoney.ledger.Accounts(), db, feed, feed != null, platform, side)
            reading = ledger
            db.read { session -> ledger.readAll(session) }.onSettled(this)
        }

        @Suppress("UNCHECKED_CAST")
        private fun stored(ledger: Ledger, stored: Any?, readError: Throwable?) {
            if (stored !is DbOutcome.Ok<*>) return fail("couldn't read the balances in ${db.description}: ${(stored as? DbOutcome.Err<*>)?.error ?: readError}")
            ledger.load(stored.value as List<Ledger.Stored>)
            ledger.hooks = side
            val feed = feed
            if (feed != null) {
                ledger.listen()
                feed.start()
            }
            this@XMoneyPlugin.ledger = ledger
            log.info("Loaded ${ledger.accounts.size} accounts from ${db.description}${if (feed != null) ", shared with the other servers on it" else ""}.")
            platform.scheduler.run(Where.Global, action = this)
            XMoney.register(this@XMoneyPlugin)
            ready.resolve(true)
        }

        // The modules, on the global thread, once the ledger runs.
        override fun invoke(task: Task) {
            modules.sync(wanted())
        }
    }

    private fun fail(reason: String) {
        failure = reason
        log.error("$reason $NAME can't run without its database: fix storage in config.yml and restart. Until then there's no economy, and plugins that use Vault find none.")
        ready.resolve(false)
    }

    /** Turns the plugin off, as the server stops: the API, the modules, the commands, then the database, once what's queued is saved. */
    public fun stop(): Pending<Unit> {
        XMoney.register(null)
        updates?.close()
        updates = null
        modules.clear()
        made?.hooks?.clear()
        registrar?.clear()
        permissionRegistrar?.sync(emptyList())
        feed?.close()
        feed = null
        made?.postEvents?.flush()
        val db = database ?: return pendingOf(Unit)
        database = null
        ledger = null
        return db.close()
    }

    // /xmoney reload: the files again, then everything built from them.
    private fun reload(): List<String> = apply(files.load())

    /** Uses what [Files] read: the currency compiled, the commands rebuilt. On the global thread. */
    public fun apply(loaded: Loaded): List<String> {
        val warnings = ArrayList<String>()
        compiled = compile(loaded.config)
        texts.translations = loaded.translations
        dispatcher = CommandDispatcher(commands.tree(config), platform.clock, log)
        permissions = commands.permissions
        permissionRegistrar?.sync(permissions.permissions + admin.permissions.permissions)
        warnings += registrar?.sync(dispatcher).orEmpty() + commands.warnings
        warnings.forEach(log::warn)
        problems = loaded.problems + warnings
        updates?.apply(updateSettings)
        return problems
    }

    private fun compile(config: Config): Compiled {
        val format = CurrencyFormat(XMoneyConfig.currency(config))
        val max = config[XMoneyConfig.maxBalance].units
        // A starting balance is typed money: rounded half up to the currency's decimals, as 1.x did.
        val start = roundTo(config[XMoneyConfig.startingBalance].units, format.decimals)
        return Compiled(config, format, max, minOf(start, max))
    }

    private fun wanted(): Map<String, () -> Module> {
        val ledger = ledger ?: return emptyMap()
        if (config[XMoneyConfig.ledger.keepDays] <= 0) return emptyMap()
        return mapOf("prune" to { Prune(ledger) })
    }

    /** Deletes old ledger rows a minute after start and once a day: the only repeating task, and only while keep-days is set. */
    private inner class Prune(private val ledger: Ledger) : Module, (Task) -> Unit {
        private var task: Task? = null

        override fun enable(context: co.xenastudios.core.common.module.ModuleContext) {
            task = platform.scheduler.every(Where.Async, 1.days, delay = 1.minutes, action = this)
        }

        // Each day's prune: itself, not a lambda of its own.
        override fun invoke(task: Task) {
            val days = config[XMoneyConfig.ledger.keepDays]
            if (days > 0) ledger.prune(platform.clock.now().toEpochMilliseconds() - days.days.inWholeMilliseconds)
        }

        override fun disable() {
            task?.cancel()
            task = null
        }
    }

    // ---- players ----

    /** [player] joined as [name]: their account, made with the starting balance if it's their first time. */
    public fun joined(player: PlayerRef, name: String) {
        ledger?.open(player, name, compiled.start)
    }

    // ---- money, for the commands, Vault and the API ----

    /** The ledger, or why there isn't one, for commands: [opening] while it's still being read. */
    internal val opening: Boolean get() = ledger == null && failure == null

    /** Now, in epoch milliseconds. */
    internal fun now(): Long = platform.clock.now().toEpochMilliseconds()

    /** [units] as the config shows it. */
    public fun format(units: Long): String = compiled.format.format(units)

    /** The balance of [player], or 0 without an account (or before the database is open). */
    public fun balanceOf(player: PlayerRef): Long = ledger?.accounts?.get(player)?.balance ?: 0

    internal fun cause(kind: TransactionKind, source: String, reason: String) = Cause(kind, source, reason)

    /** The account a plugin pays: made empty if there's none. Players' uuids are version 4 or 3; Citizens' NPCs use 2, and stay out of the top list. */
    public fun payee(ledger: Ledger, id: PlayerRef): Account =
        ledger.accounts[id] ?: ledger.account(id, server.cachedName(id), co.xenastudios.xmoney.ledger.NameUuid.version(id) != 2)

    override fun balance(player: PlayerRef): Amount = Amount.ofUnits(balanceOf(player))

    override fun hasAccount(player: PlayerRef): Boolean = ledger?.accounts?.get(player) != null

    override fun has(player: PlayerRef, amount: Amount): Boolean = amount.units <= 0 || balanceOf(player) >= amount.units

    override fun deposit(source: String, player: PlayerRef, amount: Amount, reason: String): Pending<TransactionResult> =
        money().deposit(source, player, amount, reason)

    override fun withdraw(source: String, player: PlayerRef, amount: Amount, reason: String): Pending<TransactionResult> =
        money().withdraw(source, player, amount, reason)

    override fun transfer(source: String, from: PlayerRef, to: PlayerRef, amount: Amount, reason: String): Pending<TransactionResult> =
        money().transfer(source, from, to, amount, reason)

    override fun top(from: Int, count: Int): List<TopEntry> = money().top(from, count)

    override fun history(player: PlayerRef, page: Int): Pending<HistoryPage> = money().history(player, page)

    override fun format(amount: Amount): String = format(amount.units)

    override val currency: Currency get() = compiled.format.currency

    override fun registerHook(owner: String, hook: TransactionHook) {
        money().hooks.add(owner, hook)
    }

    override fun unregisterHook(hook: TransactionHook) {
        made?.hooks?.remove(hook)
    }

    // The API's changes, its hooks and the events, made with the first change or hook: a server
    // that's just started hasn't loaded them (decisions/2026-11-25-a-lighter-core.md).
    @Volatile
    private var made: Money? = null

    internal fun money(): Money = made ?: locked(this) { made ?: Money(this).also { made = it } }

    private val side = LedgerSide()

    /** What the ledger asks around a change, handed to [Money] once there's anything to ask, and the most a balance may be. */
    private inner class LedgerSide : LedgerHooks, () -> Long {
        override fun invoke(): Long = compiled.max

        override fun refuse(account: PlayerRef, change: Long, cause: Cause, other: PlayerRef?): String? =
            money().hooks.refuse(account, change, cause, other)

        override fun saved(entries: List<SavedChange>) {
            money().postEvents.add(entries)
        }
    }

    public companion object {
        public const val PLUGIN_ID: String = "xmoney"
        public const val NAME: String = "xMoney"

        /** xMoney's version. The adapters' builds check theirs is the same. */
        public const val VERSION: String = "2.0.0"

        /** History entries a page, in `/eco history` and the API. */
        public const val HISTORY_PAGE: Int = 10

        /** [units] rounded half up to [decimals] places. */
        public fun roundTo(units: Long, decimals: Int): Long {
            var step = 1L
            repeat(Amount.SCALE - decimals) { step *= 10 }
            if (step == 1L) return units
            return (units + step / 2) / step * step
        }
    }
}
