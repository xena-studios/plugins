package co.xenastudios.xplots

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.WorldRef
import co.xenastudios.core.api.event.Event
import co.xenastudios.core.api.event.EventBus
import co.xenastudios.core.api.event.EventType
import co.xenastudios.core.api.newPending
import co.xenastudios.core.api.onSettled
import co.xenastudios.core.api.resolve
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.admin.PasteService
import co.xenastudios.core.common.admin.RecentErrors
import co.xenastudios.core.common.command.AdminCommands
import co.xenastudios.core.common.command.CommandDispatcher
import co.xenastudios.core.common.command.CommandRegistrar
import co.xenastudios.core.common.command.OnlinePlayers
import co.xenastudios.core.common.config.Config
import co.xenastudios.core.common.db.Database
import co.xenastudios.core.common.db.migrate
import co.xenastudios.core.common.db.write
import co.xenastudios.core.common.event.SimpleEventBus
import co.xenastudios.core.common.family.PlayerCache
import co.xenastudios.core.common.i18n.LanguageSource
import co.xenastudios.core.common.i18n.Translations
import co.xenastudios.core.common.locked
import co.xenastudios.core.common.module.Module
import co.xenastudios.core.common.module.ModuleSet
import co.xenastudios.core.common.permission.PermissionRegistrar
import co.xenastudios.core.common.platform.Platform
import co.xenastudios.core.common.scheduler.Task
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.update.UpdateChecker
import co.xenastudios.core.common.update.UpdateSettings
import co.xenastudios.xplots.api.AccessRule
import co.xenastudios.xplots.api.ChangeResult
import co.xenastudios.xplots.api.FieldInfo
import co.xenastudios.xplots.api.PlotInfo
import co.xenastudios.xplots.api.PriceModifier
import co.xenastudios.xplots.api.RegionBackend
import co.xenastudios.xplots.api.RentKind
import co.xenastudios.xplots.api.XPlots
import co.xenastudios.xplots.api.XPlotsApi
import co.xenastudios.xplots.config.XPlotsConfig
import co.xenastudios.xplots.model.Plots
import co.xenastudios.xplots.model.info
import co.xenastudios.xplots.reset.Deflate
import co.xenastudios.xplots.store.Store
import co.xenastudios.xplots.store.StoreReader
import co.xenastudios.xplots.store.Stored
import co.xenastudios.xplots.store.Tables
import kotlin.concurrent.Volatile

/** What the plugin's files said when they were last read: the config, the lang files, and the problems found. */
public class Loaded(
    public val config: Config,
    public val translations: Translations,
    public val problems: List<String> = emptyList(),
)

/** xPlots' files: on the JVM, `JvmFiles`. */
public fun interface Files {
    public fun load(): Loaded
}

/** Opens the database a config names (on the JVM, `JvmDatabase`), off the server's threads, or says why it couldn't, in English. */
public interface Opener {
    public fun open(config: Config): Outcome<Database, String>

    /** What [open] would open, for the console. */
    public fun describe(config: Config): String
}

/**
 * xPlots, the same on every platform: each adapter makes one with its [Platform], its
 * [PlotServer] and the family's [Hooks], reads `config.yml` and calls [start] as the plugin
 * enables, then [open]. The database is opened and every plot read off the server's threads;
 * until they're in memory ([isReady]), protection refuses every change a player makes inside...
 * anywhere (it can't yet tell where the fields are), and changes nothing else, so there's never
 * a moment a plot isn't protected (1.x read them on the server thread for the same reason).
 * `storage` applies on restart, as in 1.x; everything else on `/xplots reload`.
 */
public class XPlotsPlugin(
    public val platform: Platform,
    public val server: PlotServer,
    private val files: Files,
    languages: LanguageSource,
    internal val players: OnlinePlayers,
    internal val cache: PlayerCache? = null,
    private val opener: Opener,
    public val hooks: Hooks,
    internal val deflate: Deflate,
    internal val registrar: CommandRegistrar? = null,
    internal val permissionRegistrar: PermissionRegistrar? = null,
    internal val about: () -> Map<String, String> = { emptyMap() },
    internal val paste: PasteService? = null,
    override val version: String = VERSION,
) : XPlotsApi {
    public val log: RecentErrors = RecentErrors(platform.log, platform.clock)
    public val texts: Texts = Texts(languages) { server }
    public val bus: SimpleEventBus = SimpleEventBus(log)
    override val events: EventBus get() = bus
    public val modules: ModuleSet = ModuleSet(platform)

    /** Changes to plots are made holding this, from whichever thread makes them; reads need nothing. */
    internal val lock = Any()

    @Volatile
    public var config: Config = XPlotsConfig.defaults
        private set

    @Volatile
    internal var problems: List<String> = emptyList()
        private set

    /** Every field and plot: empty until the database is read. */
    @Volatile
    public var plots: Plots = Plots()
        private set

    /** The database, once it's open. */
    @Volatile
    public var database: Database? = null
        private set

    @Volatile
    private var madeStore: Store? = null

    /** Writes to the database, made with the first change; `null` before the database is open. */
    public val store: Store?
        get() = madeStore ?: database?.let { db -> locked(this) { madeStore ?: Store(db, log).also { madeStore = it } } }

    @Volatile
    private var ready = false

    override val isReady: Boolean get() = ready

    /** Why there's no database, if opening it failed. */
    @Volatile
    public var failure: String? = null
        internal set

    /** Settles `true` once the plots are in memory, `false` if the database couldn't be read. */
    public val opened: Pending<Boolean> = newPending()

    @Volatile
    public var running: Boolean = false
        internal set

    public fun now(): Long = platform.clock.now().toEpochMilliseconds()

    public val protection: Protection = Protection(this)

    // Each made with its first use, not as the server starts: a server with no fields yet never
    // loads renting, renewals, signs, the timer or the API's add-ons (a lighter core's way).
    @Volatile
    private var madeRentals: Rentals? = null
    public val rentals: Rentals get() = madeRentals ?: locked(this) { madeRentals ?: Rentals(this).also { madeRentals = it } }

    @Volatile
    private var madeRenewals: Renewals? = null
    public val renewals: Renewals get() = madeRenewals ?: locked(this) { madeRenewals ?: Renewals(this).also { madeRenewals = it } }

    /** The renewals read from the database, which [Renewals] takes as it's made. */
    @Volatile
    internal var storedRenewals: Map<co.xenastudios.xplots.model.Plot, co.xenastudios.xplots.store.Renewal> = emptyMap()

    // Renewal notices kept for owners who were away, read with the plots.
    internal var storedNotices: List<co.xenastudios.xplots.store.Notice> = emptyList()

    @Volatile
    private var madeExpiry: Expiry? = null
    public val expiry: Expiry get() = madeExpiry ?: locked(this) { madeExpiry ?: Expiry(this).also { madeExpiry = it } }

    @Volatile
    private var madeSigns: Signs? = null
    public val signs: Signs get() = madeSigns ?: locked(this) { madeSigns ?: Signs(this).also { madeSigns = it } }

    @Volatile
    private var madeAddons: Addons? = null
    public val addons: Addons get() = madeAddons ?: locked(this) { madeAddons ?: Addons(this).also { madeAddons = it } }

    /** The add-ons, if any plugin has registered one (or a region was made). */
    internal val addonsIfMade: Addons? get() = madeAddons

    @Volatile
    private var madeSelections: Selections? = null
    public val selections: Selections get() = madeSelections ?: locked(this) { madeSelections ?: Selections().also { madeSelections = it } }

    @Volatile
    private var madeConfirms: Confirms? = null
    public val confirms: Confirms get() = madeConfirms ?: locked(this) { madeConfirms ?: Confirms(this).also { madeConfirms = it } }

    @Volatile
    private var madeResets: co.xenastudios.xplots.reset.Resets? = null

    /** Snapshots and putting plots back: made with the first field or reset, not at startup. */
    public val resets: co.xenastudios.xplots.reset.Resets
        get() = madeResets ?: locked(this) { madeResets ?: co.xenastudios.xplots.reset.Resets(this).also { madeResets = it } }

    /** Whether [plot] is being put back, without making [resets]. */
    internal fun isResetting(plot: co.xenastudios.xplots.model.Plot): Boolean = madeResets?.isBusy(plot) == true

    @Volatile
    private var madeBuilder: Builder? = null
    internal val builder: Builder get() = madeBuilder ?: locked(this) { madeBuilder ?: Builder(this).also { madeBuilder = it } }

    @Volatile
    private var madeUi: Ui? = null
    internal val ui: Ui get() = madeUi ?: locked(this) { madeUi ?: Ui(this).also { madeUi = it } }

    @Volatile
    private var madeTeleports: Teleports? = null
    internal val teleports: Teleports get() = madeTeleports ?: locked(this) { madeTeleports ?: Teleports(this).also { madeTeleports = it } }

    @Volatile
    private var madePlaceholders: Placeholders? = null
    public val placeholders: Placeholders get() = madePlaceholders ?: locked(this) { madePlaceholders ?: Placeholders(this).also { madePlaceholders = it } }

    public val admin: AdminCommands = AdminCommands(
        PLUGIN_ID, NAME, platform, co.xenastudios.core.common.command.ModuleReloader(modules, reloadFiles = Reloading(this), wanted = Wanted(this)),
        debug = AdminPart(this, true),
        playerData = AdminPart(this, false),
        text = texts.admin,
    )

    private val commands = Commands(this)

    /** The wand was used on a block at [x] [y] [z], whose clicked face points by [faceX] [faceY] [faceZ]. On the player's thread. */
    public fun wand(player: PlayerRef, name: String, left: Boolean, sneaking: Boolean, world: String, x: Int, y: Int, z: Int, faceX: Int, faceY: Int, faceZ: Int): Unit =
        commands.actions().wand(player, name, left, sneaking, world, x, y, z, faceX, faceY, faceZ)

    /** A plot's sign was right-clicked. On the player's thread. */
    public fun signClicked(player: PlayerRef, name: String, plot: co.xenastudios.xplots.model.Plot): Unit = commands.actions().signClicked(player, name, plot)

    /** A player joined: their plots' names, warnings and renewals. */
    public fun joined(player: PlayerRef, name: String) {
        if (plots.owned(player).isNotEmpty() || madeRenewals != null) expiry.joined(player, name)
    }

    /** A chunk loaded: its plots' signs, if it has any. */
    public fun chunkLoaded(world: String, chunkX: Int, chunkZ: Int) {
        if (plots.signsInChunk(world, chunkX, chunkZ).isNotEmpty()) signs.chunkLoaded(world, chunkX, chunkZ)
    }

    /** A player left: what's kept for them goes. */
    public fun quit(player: PlayerRef, name: String) {
        madeTeleports?.cancel(player)
        madeSelections?.forget(name)
        madeConfirms?.cancel(name)
        protection.forget(player)
    }

    @Volatile
    public var dispatcher: CommandDispatcher = CommandDispatcher(commands.tree(), platform.clock, log)
        private set

    @Volatile
    public var permissions: XPlotsPermissions = commands.permissions
        private set

    @Volatile
    public var updates: UpdateChecker? = null

    public val updateSettings: UpdateSettings get() = XPlotsConfig.updates.settings(config)

    // ---- starting and stopping ----

    /** Turns the plugin on with what [Files] read: permissions and commands. The plots come with [open]. */
    public fun start(loaded: Loaded) {
        running = true
        apply(loaded)
        XPlots.register(this)
    }

    /** Opens the database the config names and reads every plot, off the server's threads. Once, as the plugin starts. */
    public fun open() {
        platform.scheduler.run(Where.Async, action = OpenTask(this, config))
    }

    private class OpenTask(private val plugin: XPlotsPlugin, private val config: Config) : (Task) -> Unit {
        override fun invoke(task: Task) = plugin.openNow(config)
    }

    private fun openNow(config: Config) {
        if (!running) return
        val db = when (val opened = opener.open(config)) {
            is Outcome.Ok -> opened.value
            is Outcome.Err -> return failedToOpen(opened.error)
        }
        db.migrate(Tables.MIGRATIONS).onSettled { result, error ->
            if (result !is Outcome.Ok) {
                db.close()
                return@onSettled failedToOpen("Couldn't bring ${db.description}'s tables up to date: ${(result as? Outcome.Err)?.error ?: error}")
            }
            db.write(Tables.extras(db.sql)).onSettled { extras, _ ->
                if (extras is Outcome.Err) log.warn("Couldn't make the renewals' tables (${extras.error}); renewals stay off until it can be.")
                StoreReader(db, log).load().onSettled { read, readError ->
                    if (read !is Outcome.Ok) {
                        db.close()
                        return@onSettled failedToOpen("Couldn't read plots from ${db.description}: ${(read as? Outcome.Err)?.error ?: readError}")
                    }
                    platform.scheduler.run(Where.Global, action = Install(this, db, read.value))
                }
            }
        }
    }

    // On the global thread: the plots go live, then everything that runs on them starts.
    private class Install(private val plugin: XPlotsPlugin, private val db: Database, private val stored: Stored) : (Task) -> Unit {
        override fun invoke(task: Task) = plugin.install(db, stored)
    }

    private fun install(db: Database, stored: Stored) {
        if (!running) {
            db.close()
            return
        }
        locked(lock) {
            this.database = db
            plots = stored.plots
            storedRenewals = stored.renewals
            storedNotices = stored.notices
            ready = true
        }
        failure = null
        val count = plots.fields().sumOf { it.plots().size }
        log.info("Loaded ${plots.fields().size} fields with $count plots from ${db.description}.")
        modules.sync(wanted())
        madeAddons?.regionsLoaded()
        opened.resolve(true)
        if (stored.renewals.values.any { it.charge != null }) renewals.settleUnfinished()
        if (config[XPlotsConfig.currency.type] != XPlotsConfig.CurrencyType.ITEM && hooks.economy() == null) {
            // Economy plugins often register after xPlots starts, so this is only a heads-up.
            log.info("Rent is paid through the server's economy, and none is installed yet (xMoney, or Vault with an economy plugin). Plots can't be rented until one is, or until currency.type is item.")
        }
    }

    private fun failedToOpen(problem: String) {
        failure = problem
        log.error(problem)
        log.error("xPlots can't run without its database. Fix storage in config.yml and restart. Until then nothing in its fields can be changed.")
        opened.resolve(false)
    }

    /** Turns the plugin off, as the server stops: timers, menus and what's being put back, then what's queued is written. */
    public fun stop(): Pending<Unit> {
        running = false
        XPlots.register(null)
        modules.clear()
        madeTeleports?.clear()
        madeConfirms?.clear()
        madeResets?.stop()
        madeAddons?.clear()
        val out = newPending<Unit>()
        val db = database
        if (db == null) {
            out.resolve(Unit)
        } else {
            db.close().onSettled { _, _ -> out.resolve(Unit) }
        }
        return out
    }

    /** What the adapter does just before the first event could be heard: Bukkit's bridges the API's events to Bukkit events there. */
    @Volatile
    public var beforeFirstEvent: (() -> Unit)? = null

    /** Whether anything listens to [type], after bridging to the platform's events the first time it's asked. */
    internal fun <E : Event> hasListeners(type: EventType<E>): Boolean {
        val bridge = beforeFirstEvent
        if (bridge != null) {
            val run = locked(this) { if (beforeFirstEvent === bridge) true.also { beforeFirstEvent = null } else false }
            if (run) bridge()
        }
        return bus.hasListeners(type)
    }

    /** Fires [event] if anything listens; returns whether it's cancelled. */
    internal fun <E : Event> fire(type: EventType<E>, event: () -> E): E? = if (hasListeners(type)) bus.fire(type, event()) else null

    /** Uses what [Files] read: the commands and the modules. On the global thread. */
    public fun apply(loaded: Loaded): List<String> {
        config = loaded.config
        texts.translations = loaded.translations
        dispatcher = CommandDispatcher(commands.tree(), platform.clock, log)
        permissions = commands.permissions
        permissionRegistrar?.sync(permissions.permissions + admin.permissions.permissions)
        val warnings = registrar?.sync(dispatcher).orEmpty()
        warnings.forEach(log::warn)
        problems = loaded.problems + warnings
        updates?.apply(updateSettings)
        if (ready) {
            modules.sync(wanted())
            if (!plots.isEmpty) signs.refreshAll()
        }
        return problems
    }

    // The timers only once there's a field: with none, nothing runs out and no sign needs writing.
    private fun wanted(): Map<String, () -> Module> = if (!ready) emptyMap() else buildMap {
        if (!plots.isEmpty) put("timers", TIMERS)
        put("homes", HOMES)
    }

    /** A field was made or deleted: the timers start with the first and stop with the last. */
    internal fun fieldsChanged() {
        if (ready) modules.sync(wanted())
    }

    private val TIMERS = object : () -> Module {
        override fun invoke(): Module = Timers(this@XPlotsPlugin)
    }

    private val HOMES = object : () -> Module {
        override fun invoke(): Module = HomesModule(this@XPlotsPlugin)
    }

    private class Reloading(private val plugin: XPlotsPlugin) : () -> List<String> {
        override fun invoke(): List<String> = plugin.apply(plugin.files.load())
    }

    private class Wanted(private val plugin: XPlotsPlugin) : () -> Map<String, () -> Module> {
        override fun invoke(): Map<String, () -> Module> = plugin.wanted()
    }

    // ---- the API ----

    override fun fields(): List<FieldInfo> = plots.fields().sortedBy { it.name }.map { info(it) }

    override fun field(name: String): FieldInfo? = plots.field(name)?.let { info(it) }

    override fun plot(id: String): PlotInfo? = plots.plot(id.lowercase())?.let { info(it, isResetting(it)) }

    override fun plotAt(world: WorldRef, x: Int, y: Int, z: Int): PlotInfo? {
        val plot = plots.at(world.name, x, y, z) ?: return null
        if (plot === co.xenastudios.xplots.model.Field.PATH) return null
        return info(plot, isResetting(plot))
    }

    override fun isInField(world: WorldRef, x: Int, y: Int, z: Int): Boolean = plots.at(world.name, x, y, z) != null

    override fun plotsOf(player: PlayerRef): List<PlotInfo> = plots.owned(player).map { info(it, isResetting(it)) }

    override fun mayBuild(player: PlayerRef, world: WorldRef, x: Int, y: Int, z: Int): Boolean = protection.mayBuild(player, false, world.name, x, y, z)

    override fun setOwner(id: String, player: PlayerRef, name: String): Pending<ChangeResult> = Api.setOwner(this, id, player, name)

    override fun evict(id: String): Pending<ChangeResult> = Api.evict(this, id)

    override fun addMember(id: String, player: PlayerRef, name: String): Pending<ChangeResult> = Api.addMember(this, id, player, name)

    override fun removeMember(id: String, player: PlayerRef): Pending<ChangeResult> = Api.removeMember(this, id, player)

    override fun extend(id: String, millis: Long): Pending<ChangeResult> = Api.extend(this, id, millis)

    override fun price(player: PlayerRef, plot: String, kind: RentKind, periods: Int): Double? {
        val p = plots.plot(plot.lowercase()) ?: return null
        return rentals.price(player, p, kind, periods)
    }

    override fun registerPriceModifier(owner: String, modifier: PriceModifier): Unit = addons.add(owner, modifier)

    override fun registerAccessRule(owner: String, rule: AccessRule): Unit = addons.add(owner, rule)

    override fun registerRegionBackend(owner: String, backend: RegionBackend): Unit = addons.add(owner, backend)

    override fun unregisterAll(owner: String): Unit = addons.remove(owner)

    public companion object {
        public const val PLUGIN_ID: String = "xplots"
        public const val NAME: String = "xPlots"

        /** xPlots' version. The adapters' builds check theirs is the same. */
        public const val VERSION: String = "2.0.0"

        /** Renting plots and `/plots`. Everyone has it. */
        public const val USE: String = "xplots.use"

        /** Building and opening anything in any field. */
        public const val BYPASS: String = "xplots.bypass"

        /** Teleporting without the warmup. */
        public const val WARMUP_BYPASS: String = "xplots.warmup.bypass"

        /** `xplots.limit.<n>`: renting up to n plots at once. */
        public const val LIMIT_PREFIX: String = "xplots.limit."
    }
}

/** `/xplots debug`'s report or `forget`'s player data, made when the command first asks. */
private class AdminPart<T>(private val plugin: XPlotsPlugin, private val debug: Boolean) : () -> T {
    @Suppress("UNCHECKED_CAST")
    override fun invoke(): T = (if (debug) AdminParts.debug(plugin) else AdminParts.playerData(plugin)) as T
}
