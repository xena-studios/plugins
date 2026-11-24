package co.xenastudios.core.sample

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.event.CancellableEvent
import co.xenastudios.core.api.event.Event
import co.xenastudios.core.api.event.EventType
import co.xenastudios.core.api.pendingOf
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.admin.Debug
import co.xenastudios.core.common.admin.HealthChecks
import co.xenastudios.core.common.admin.Importer
import co.xenastudios.core.common.admin.Imports
import co.xenastudios.core.common.admin.PlayerDataRegistry
import co.xenastudios.core.common.admin.PlayerLookup
import co.xenastudios.core.common.command.AdminCommands
import co.xenastudios.core.common.command.ArgumentTypes
import co.xenastudios.core.common.command.CommandDispatcher
import co.xenastudios.core.common.command.CommandRegistrar
import co.xenastudios.core.common.command.CommandTree
import co.xenastudios.core.common.command.ModuleReloader
import co.xenastudios.core.common.command.OnlinePlayers
import co.xenastudios.core.common.command.commands
import co.xenastudios.core.common.config.Config
import co.xenastudios.core.common.config.ConfigKey
import co.xenastudios.core.common.config.ConfigSchema
import co.xenastudios.core.common.config.ConfigSection
import co.xenastudios.core.common.event.SimpleEventBus
import co.xenastudios.core.common.i18n.CoreMessages
import co.xenastudios.core.common.i18n.MessageBundle
import co.xenastudios.core.common.i18n.MessageCatalog
import co.xenastudios.core.common.i18n.MessageKey
import co.xenastudios.core.common.family.MessagesSection
import co.xenastudios.core.common.manifest.EventInfo
import co.xenastudios.core.common.manifest.PluginReference
import co.xenastudios.core.common.module.Module
import co.xenastudios.core.common.module.ModuleContext
import co.xenastudios.core.common.module.ModuleSet
import co.xenastudios.core.common.permission.Permission
import co.xenastudios.core.common.permission.PermissionDefault
import co.xenastudios.core.common.permission.PermissionSet
import co.xenastudios.core.common.placeholder.PlaceholderSet
import co.xenastudios.core.common.placeholder.placeholders
import co.xenastudios.core.common.platform.Platform
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.scheduler.every
import co.xenastudios.core.common.text.Component
import kotlin.jvm.JvmField
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

// xSample: a small plugin on the rewrite's core, never released. It proves the command and
// permission DSL, the admin subcommands and the reference manifest end to end, the way a real
// plugin will use them: its tests run its commands on the fake platform, and its build writes
// its manifest to docs/src/generated/xsample/ (no page shows it).

/** xSample's config. */
public object SampleConfig : ConfigSchema(1, "xSample: the core's sample plugin. Never released.") {
    /** Its look and language: the family's (`plugins/Xena/config.yml`) unless set here. */
    public val messages: MessagesSection = MessagesSection(this)
    public val startingCoins: ConfigKey<Int> =
        int("starting-coins", 100, "Coins a player has before their first payment.", min = 0)
    public val payCooldown: ConfigKey<Duration> =
        duration("pay-cooldown", 10.seconds, "How long a player waits between payments.", min = 1.seconds)
    public val interest: Interest = Interest(this)

    public class Interest(parent: SampleConfig) : ConfigSection(parent, "interest", "Coins paid on every balance now and then.") {
        public val enabled: ConfigKey<Boolean> = boolean("enabled", true, "Whether interest is paid.")
        public val percent: ConfigKey<Int> = int("percent", 1, "How much of each balance, in percent.", min = 0, max = 100)
        public val every: ConfigKey<Duration> = duration("every", 1.hours, "How often.", min = 1.minutes)
    }
}

/** xSample's messages, which lang/de.yml translates (and `checkTranslations` checks). */
public object SampleMessages : MessageBundle() {
    public val paid: MessageKey = message(
        "sample.paid", "<prefix>You paid <highlight><player></highlight> <amount> coins.",
        "After /pay.",
        placeholders = listOf("player", "amount"),
    )
    public val broke: MessageKey = message("sample.broke", "<prefix><error>You don't have <amount> coins.", placeholders = listOf("amount"))

    /** Every message the sample's lang files hold: the core's, then its own. */
    public val catalog: MessageCatalog = MessageCatalog.of(CoreMessages, this)
}

/** A player is about to pay another: cancel it to stop the payment. */
public class CoinsPaidEvent(public val from: PlayerRef, public val to: PlayerRef, public val amount: Int) : CancellableEvent() {
    public companion object {
        @JvmField
        public val TYPE: EventType<CoinsPaidEvent> = EventType.of("xsample:coins_paid")
    }
}

/** Interest was paid on every balance. */
public class InterestPaidEvent(public val percent: Int) : Event {
    public companion object {
        @JvmField
        public val TYPE: EventType<InterestPaidEvent> = EventType.of("xsample:interest_paid")
    }
}

/** xSample's permissions. */
public object SamplePermissions : PermissionSet("xsample") {
    public val pay: Permission = permission("pay", "Pay other players.", PermissionDefault.EVERYONE)
    public val give: Permission = permission("give", "Give coins out of nowhere.")
    public val noCooldown: Permission = permission("pay.no-cooldown", "Pay without waiting between payments.")
    public val all: Permission = permission("*", "Everything in xSample but the admin subcommands.", children = grants(pay, give, noCooldown))
}

/**
 * The plugin: its coins, its one module, and its commands and placeholders, built from its
 * config. [settings] gives the config, read again on every reload. On a real server,
 * [registrar] puts the commands there, at [enable] and after every reload; the tests run the
 * [dispatcher] directly.
 */
public class XSample(
    private val platform: Platform,
    private val players: OnlinePlayers,
    private val settings: () -> Config = { SampleConfig.defaults },
    /** Other plugins' coins it can import, for `/xsample import`; a real plugin ships its own. */
    importers: List<Importer<*>> = emptyList(),
    /** Who has played, by name, for `/xsample forget`: the family's player cache on a server. */
    lookup: PlayerLookup = PlayerLookup { pendingOf(null) },
    private val registrar: CommandRegistrar? = null,
) {
    private var config: Config = settings()
    private val coins = HashMap<PlayerRef, Int>()

    /** Its API's events. */
    public val events: SimpleEventBus = SimpleEventBus(platform.log)

    /** The plugin's modules: interest, when the config wants it. */
    public val modules: ModuleSet = ModuleSet(platform)

    /** What it keeps per player, which `/xsample forget` deletes: the coins. */
    public val playerData: PlayerDataRegistry = PlayerDataRegistry(lookup).apply {
        register("coins") { player -> pendingOf(Outcome.Ok(if (withCoins { remove(player) } != null) 1L else 0L)) }
    }

    /** The admin subcommands: `forget`, since the plugin keeps player data, and `import` when it's given importers. */
    public val admin: AdminCommands = AdminCommands(
        "xsample", "xSample", platform,
        ModuleReloader(modules, reloadFiles = ::reloadFiles, wanted = ::wantedModules),
        debug = Debug(platform, "xSample 1.0.0", listOf(HealthChecks.threading(platform), HealthChecks.modules(modules))),
        imports = if (importers.isEmpty()) null else Imports(platform, importers),
        playerData = playerData,
    )

    /** What runs commands; built again on reload, from the new config. */
    public var dispatcher: CommandDispatcher = CommandDispatcher(commands(), platform.clock, platform.log)
        private set

    /** The placeholders, which read the coins. */
    public val placeholders: PlaceholderSet = placeholders("xsample") {
        player("balance", "The player's balance.", "120") { balance(it).toString() }
        global("richest", "The most coins anyone has.", "4,200") { withCoins { values.maxOrNull() }?.toString() }
    }

    /** What the docs show: built from the same objects the plugin runs on. */
    public val reference: PluginReference
        get() = PluginReference(
            "xsample", "xSample", "The core's sample plugin, never released.",
            dispatcher.tree, listOf(SamplePermissions, admin.permissions),
            config = SampleConfig,
            placeholders = placeholders,
            catalog = SampleMessages.catalog,
            events = listOf(
                EventInfo.of(CoinsPaidEvent.TYPE, "A player is about to pay another.", since = "1.0.0"),
                EventInfo.of(InterestPaidEvent.TYPE, "Interest was paid on every balance.", since = "1.0.0"),
            ),
        )

    /** Turns the plugin on: its commands, and its modules as the config says. */
    public fun enable() {
        syncCommands()
        modules.sync(wantedModules())
    }

    /** A player's coins. */
    public fun balance(player: PlayerRef): Int = withCoins { get(player) } ?: config[SampleConfig.startingCoins]

    /** Sets a player's coins, as an importer does: the same value twice changes nothing. */
    public fun setBalance(player: PlayerRef, coins: Int) {
        withCoins { put(player, coins) }
    }

    private fun commands(): CommandTree = commands {
        root("xsample", "Shows your coins.", aliases = listOf("coins")) {
            playerOnly()
            executes { ctx -> ctx.reply(Component.text("You have ${balance(ctx.player)} coins.")) }
            literal("give", "Gives a player coins.", permission = SamplePermissions.give) {
                val to = argument("player", ArgumentTypes.player(players), "Who gets them.")
                val amount = argument("amount", ArgumentTypes.integer(min = 1, max = 1_000_000), "How many coins.")
                executes { ctx ->
                    add(ctx[to], ctx[amount])
                    ctx.reply(Component.text("Gave ${ctx[amount]} coins."))
                }
            }
            mount(admin)
        }
        root("pay", "Pays another player.", permission = SamplePermissions.pay) {
            playerOnly()
            val to = argument("player", ArgumentTypes.player(players), "Who to pay.")
            val amount = argument("amount", ArgumentTypes.integer(min = 1), "How many coins.")
            cooldown(config[SampleConfig.payCooldown], bypass = SamplePermissions.noCooldown)
            executes { ctx ->
                val from = ctx.player
                if (ctx[to] == from) ctx.fail(Component.text("You can't pay yourself."))
                if (balance(from) < ctx[amount]) ctx.fail(Component.text("You don't have ${ctx[amount]} coins."))
                if (events.fire(CoinsPaidEvent.TYPE, CoinsPaidEvent(from, ctx[to], ctx[amount])).isCancelled) ctx.fail(Component.text("The payment was stopped."))
                add(from, -ctx[amount])
                add(ctx[to], ctx[amount])
                ctx.reply(Component.text("Paid ${ctx[amount]} coins."))
            }
        }
    }

    private fun add(player: PlayerRef, amount: Int) {
        val start = balance(player)
        withCoins { put(player, start + amount) }
    }

    // The sample's tests run everything on one thread. A real plugin keeps balances where any
    // region's thread can read them (ROADMAP.md, Fast), and writes them from one.
    private inline fun <T> withCoins(block: HashMap<PlayerRef, Int>.() -> T): T = coins.block()

    private fun reloadFiles(): List<String> {
        config = settings()
        dispatcher = CommandDispatcher(commands(), platform.clock, platform.log)
        return syncCommands()
    }

    // Each warning (a name another plugin has) is also a problem the reload reports.
    private fun syncCommands(): List<String> {
        val warnings = registrar?.sync(dispatcher).orEmpty()
        warnings.forEach { platform.log.warn(it) }
        return warnings
    }

    private fun wantedModules(): Map<String, () -> Module> =
        if (config[SampleConfig.interest.enabled]) mapOf("interest" to { Interest() }) else emptyMap()

    private inner class Interest : Module {
        override fun enable(context: ModuleContext) {
            val percent = config[SampleConfig.interest.percent]
            context.scheduler.every(Where.Global, config[SampleConfig.interest.every]) {
                withCoins { for (entry in entries) entry.setValue(entry.value + entry.value * percent / 100) }
                events.fire(InterestPaidEvent.TYPE, InterestPaidEvent(percent))
            }
        }
    }
}
