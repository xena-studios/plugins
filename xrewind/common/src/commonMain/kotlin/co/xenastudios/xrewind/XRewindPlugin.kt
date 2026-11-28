package co.xenastudios.xrewind

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.WorldRef
import co.xenastudios.core.api.event.Event
import co.xenastudios.core.api.event.EventBus
import co.xenastudios.core.api.event.EventType
import co.xenastudios.core.api.newPending
import co.xenastudios.core.api.onSettled
import co.xenastudios.core.api.pendingOf
import co.xenastudios.core.api.resolve
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.admin.PasteService
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
import co.xenastudios.core.common.scheduler.Task
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.text.Component
import co.xenastudios.core.common.update.UpdateChecker
import co.xenastudios.core.common.update.UpdateSettings
import co.xenastudios.core.common.world.WorldHooks
import co.xenastudios.xrewind.api.Actor
import co.xenastudios.xrewind.api.Entry
import co.xenastudios.xrewind.api.LogFilter
import co.xenastudios.xrewind.api.Plan
import co.xenastudios.xrewind.api.Query
import co.xenastudios.xrewind.api.RollbackResult
import co.xenastudios.xrewind.api.XRewind
import co.xenastudios.xrewind.api.XRewindApi
import co.xenastudios.xrewind.config.XRewindConfig
import co.xenastudios.xrewind.log.LogQueue
import co.xenastudios.xrewind.log.Logging
import co.xenastudios.xrewind.log.StoredTime
import co.xenastudios.xrewind.log.Writer
import co.xenastudios.xrewind.rollback.Rollbacks
import co.xenastudios.xrewind.store.Tables
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

/** What the plugin's files said when they were last read: the config, the lang files, and the problems found. */
public class Loaded(
    public val config: Config,
    public val translations: Translations,
    public val problems: List<String> = emptyList(),
)

/** xRewind's files: on the JVM, `JvmFiles`. */
public fun interface Files {
    public fun load(): Loaded
}

/** Opens the database a config names (on the JVM, `JvmDatabase`), off the server's threads, or says why it couldn't, in English. */
public interface Opener {
    public fun open(config: Config): Outcome<Database, String>

    /** What [open] would open, for telling whether a reload asks for another database. */
    public fun describe(config: Config): String
}

/** Another logger's history, for `/xrewind import`: on Bukkit, CoreProtect's (jvmMain's `CoreProtectImport`). */
public interface HistoryImport {
    /** Where the history is, for the reply, or why there's none to import. Off the server's threads. */
    public fun find(): Outcome<String, String>

    /**
     * Reads everything and hands each change to [add], waiting whenever [waiting] says the writer
     * has a lot to catch up on, with progress in the console; returns how many, or why it stopped.
     * Off the server's threads; [running] is asked as it goes.
     */
    public fun run(add: (co.xenastudios.xrewind.log.Record) -> Unit, waiting: () -> Int, running: () -> Boolean): Outcome<Long, String>
}

/**
 * xRewind, the same on every platform: each adapter makes one with its [Platform] and its
 * [RewindServer], reads the files and calls [start] as the plugin enables; the database is opened
 * off the server's threads, and until it's ready changes wait in memory. Everything the config
 * says applies on `/xrewind reload`, the database included (1.x's rule): one that can't be opened
 * leaves the old one in use.
 */
public class XRewindPlugin(
    public val platform: Platform,
    public val server: RewindServer,
    private val files: Files,
    languages: LanguageSource,
    internal val players: OnlinePlayers,
    internal val cache: PlayerCache? = null,
    private val opener: Opener,
    internal val registrar: CommandRegistrar? = null,
    internal val permissionRegistrar: PermissionRegistrar? = null,
    internal val about: () -> Map<String, String> = { emptyMap() },
    internal val paste: PasteService? = null,
    /** CoreProtect's history, where it can be (Bukkit), made the first time `/xrewind import` runs; `null` elsewhere. */
    private val importer: (() -> HistoryImport)? = null,
    override val version: String = VERSION,
) : XRewindApi {
    public val log: RecentErrors = RecentErrors(platform.log, platform.clock)

    @Volatile
    private var madeHistory: HistoryImport? = null
    internal val history: HistoryImport? get() = madeHistory ?: importer?.let { make -> locked(this) { madeHistory ?: make().also { madeHistory = it } } }
    public val texts: Texts = Texts(languages) { server }
    public val bus: SimpleEventBus = SimpleEventBus(log)
    override val events: EventBus get() = bus
    public val modules: ModuleSet = ModuleSet(platform)

    @Volatile
    public var config: Config = XRewindConfig.defaults
        private set

    @Volatile
    internal var problems: List<String> = emptyList()
        private set

    public val queue: LogQueue = LogQueue(log)
    public val writer: Writer = Writer(platform, queue, log, server::itemBytes)

    /** The database, or `null` before the first is ready. */
    public val database: Database? get() = writer.current

    /** Why there's no database, while opening it fails. */
    @Volatile
    public var failure: String? = null
        internal set

    /** Settles `true` once the first database is open; `false` if it couldn't be. */
    public val opened: Pending<Boolean> = newPending()

    @Volatile
    public var running: Boolean = false
        internal set

    /** The console, for what a restart carries on: its replies go to the log. */
    internal val console: CommandSource get() = Console(this)

    private class Console(private val plugin: XRewindPlugin) : CommandSource {
        override val name: String = "CONSOLE"
        override val player: PlayerRef? = null
        override fun hasPermission(node: String): Boolean = true
        override fun reply(message: Component) = plugin.log.info(message.plain())
    }

    @Volatile
    internal var madeRollbacks: Rollbacks? = null

    /** Rollbacks, restores, previews and undo: made with the first, or as a restart's is carried on, not as the server starts. */
    public val rollbacks: Rollbacks get() = madeRollbacks ?: locked(this) { madeRollbacks ?: Rollbacks(this).also { madeRollbacks = it } }

    @Volatile
    internal var madeLookups: Lookups? = null
    internal val lookups: Lookups get() = madeLookups ?: locked(this) { madeLookups ?: Lookups(this).also { madeLookups = it } }

    @Volatile
    internal var madeHooks: WorldHooks? = null

    /** The world hooks, made the first time something's logged from them; closed with the plugin. */
    internal fun hooks(): WorldHooks = madeHooks ?: locked(this) { madeHooks ?: server.hooks().also { madeHooks = it } }

    /** The API's log filters, by owner: made with the first a plugin registers. */
    @Volatile
    internal var filters: Filters? = null
        private set

    private fun filters(): Filters = filters ?: locked(this) { filters ?: Filters(this).also { filters = it } }

    public val admin: AdminCommands = AdminCommands(
        PLUGIN_ID, NAME, platform, reloader(),
        debug = AdminPart(this, true),
        playerData = AdminPart(this, false),
        text = texts.admin,
    )

    private val commands = Commands(this)

    @Volatile
    public var dispatcher: CommandDispatcher = CommandDispatcher(commands.tree(), platform.clock, log)
        private set

    @Volatile
    public var permissions: XRewindPermissions = commands.permissions
        private set

    @Volatile
    public var updates: UpdateChecker? = null

    public val updateSettings: UpdateSettings get() = XRewindConfig.updates.settings(config)

    internal fun now(): Int = StoredTime.of(platform.clock.now())

    // ---- starting and stopping ----

    /** Turns the plugin on with what [Files] read: permissions, commands, logging. The database comes with [open]. */
    public fun start(loaded: Loaded) {
        running = true
        writer.start()
        apply(loaded)
        XRewind.register(this)
    }

    /** Opens the database the config names, off the server's threads. Any thread. */
    public fun open() {
        val config = config
        wanted = opener.describe(config)
        val ticket = locked(this) { ++generation }
        platform.scheduler.run(Where.Async, action = OpenTask(this, config, ticket))
    }

    @Volatile
    private var wanted: String? = null
    private var generation = 0

    private class OpenTask(private val plugin: XRewindPlugin, private val config: Config, private val ticket: Int) : (Task) -> Unit {
        override fun invoke(task: Task) = plugin.openNow(config, ticket)
    }

    private fun current(ticket: Int): Boolean = locked(this) { ticket == generation } && running

    private fun openNow(config: Config, ticket: Int) {
        if (!current(ticket)) return
        val db = when (val opened = opener.open(config)) {
            is Outcome.Ok -> opened.value
            is Outcome.Err -> return failedToOpen(opened.error)
        }
        db.migrate(Tables.MIGRATIONS).onSettled { result, error ->
            if (result !is Outcome.Ok) {
                db.close()
                return@onSettled failedToOpen("Couldn't bring ${db.description}'s tables up to date: ${(result as? Outcome.Err)?.error ?: error}")
            }
            if (!current(ticket)) {
                db.close()
                return@onSettled
            }
            val old = writer.current
            writer.use(db)
            failure = null
            log.info("Storage ready: ${db.description}, schema ${result.value}.")
            if (old != null && old !== db) {
                old.close()
            } else {
                opened.resolve(true)
                platform.scheduler.run(Where.Async, action = Resume(this, db))
            }
        }
    }

    // A rollback a restart stopped, carried on: read only when its file is there (a class, not a lambda).
    private class Resume(private val plugin: XRewindPlugin, private val db: Database) : (Task) -> Unit {
        override fun invoke(task: Task) {
            if (plugin.platform.storage.read(co.xenastudios.core.common.platform.StoragePath.of(JOB_FILE)).let { it is Outcome.Ok && it.value != null }) plugin.rollbacks.resume(db)
        }
    }

    private fun failedToOpen(problem: String) {
        failure = problem
        log.error(problem)
        log.error(database?.let { "Still using ${it.description}." } ?: "Nothing can be saved until the storage settings are fixed and xRewind is reloaded.")
        // So reloading with the same settings tries again, as when a server was just down.
        wanted = null
        opened.resolve(false)
    }

    /** Turns the plugin off, as the server stops: logging, a running rollback, then what's queued is written and the database closed. */
    public fun stop(): Pending<Unit> = Api.stop(this)

    @Suppress("UNCHECKED_CAST")
    private fun reloader(): ModuleReloader =
        ModuleReloader(modules, reloadFiles = Reloading(this, 0) as () -> List<String>, wanted = Reloading(this, 1) as () -> Map<String, () -> Module>)

    private class Reloading(private val plugin: XRewindPlugin, private val kind: Int) : () -> Any {
        override fun invoke(): Any = if (kind == 0) plugin.apply(plugin.files.load()) else plugin.wanted()
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

    /** Uses what [Files] read: the commands, what's logged, and another database if the config names one. On the global thread. */
    public fun apply(loaded: Loaded): List<String> {
        config = loaded.config
        texts.translations = loaded.translations
        queue.excluded = config[XRewindConfig.log.excludedWorlds].toSet()
        dispatcher = CommandDispatcher(commands.tree(), platform.clock, log)
        permissions = commands.permissions
        permissionRegistrar?.sync(permissions.permissions + admin.permissions.permissions)
        val warnings = registrar?.sync(dispatcher).orEmpty()
        warnings.forEach(log::warn)
        problems = loaded.problems + warnings
        updates?.apply(updateSettings)
        modules.sync(wanted())
        if (running && database != null && opener.describe(config) != wanted) open()
        return problems
    }

    private fun wanted(): Map<String, () -> Module> = buildMap {
        put("log", LOGGING)
        if (config[XRewindConfig.purge.keepDays] > 0) put("purge", PURGE)
    }

    private val LOGGING = object : () -> Module {
        override fun invoke(): Module = Logging(this@XRewindPlugin, config)
    }

    private val PURGE = object : () -> Module {
        override fun invoke(): Module = AutoPurge(this@XRewindPlugin)
    }

    /** Deletes history older than `purge.keep-days`, five minutes after startup and then once a day (1.x's `AutoPurge`). */
    private class AutoPurge(private val plugin: XRewindPlugin) : Module, (Task) -> Unit {
        override fun enable(context: ModuleContext) {
            context.scheduler.run(Where.Async, 5.minutes, 1.days, action = this)
        }

        override fun invoke(task: Task) = co.xenastudios.xrewind.store.Purge.daily(plugin)
    }

    // ---- the API ----

    override fun logPlacement(actor: Actor, world: WorldRef, x: Int, y: Int, z: Int, placed: String): Unit =
        Api.log(this, actor, world, x, y, z, "minecraft:air", placed)

    override fun logRemoval(actor: Actor, world: WorldRef, x: Int, y: Int, z: Int, removed: String): Unit =
        Api.log(this, actor, world, x, y, z, removed, "minecraft:air")

    override fun logChange(actor: Actor, world: WorldRef, x: Int, y: Int, z: Int, before: String, after: String): Unit =
        Api.log(this, actor, world, x, y, z, before, after)

    override fun logContainerChange(actor: Actor, world: WorldRef, x: Int, y: Int, z: Int, item: String, amount: Int): Unit =
        Api.container(this, actor, world, x, y, z, item, null, amount)

    /** [logContainerChange] with the item as the platform's bytes ([RewindServer.itemBytes]): for an adapter's own helpers. */
    public fun logContainerItem(actor: Actor, world: WorldRef, x: Int, y: Int, z: Int, type: String, bytes: ByteArray, amount: Int): Unit =
        Api.container(this, actor, world, x, y, z, type, bytes, amount)

    override fun lookup(query: Query): Pending<List<Entry>> = Api.lookup(this, query)

    override fun planRollback(query: Query): Pending<Plan> = Api.plan(this, query, restore = false)

    override fun planRestore(query: Query): Pending<Plan> = Api.plan(this, query, restore = true)

    override fun apply(plan: Plan): Pending<RollbackResult> = Api.apply(this, plan)

    override fun flush(): Pending<Boolean> = writer.flush()

    override fun registerFilter(owner: String, filter: LogFilter): Unit = filters().add(owner, filter)

    override fun unregisterAll(owner: String) {
        filters?.remove(owner)
    }

    public companion object {
        public const val PLUGIN_ID: String = "xrewind"
        public const val NAME: String = "xRewind"

        /** xRewind's version. The adapters' builds check theirs is the same. */
        public const val VERSION: String = "2.0.0"

        /** Where a running rollback is written down, to carry on after a restart: `plugins/xRewind/rollback-running.txt`. */
        public const val JOB_FILE: String = "rollback-running.txt"
    }
}

/** The API's log filters: asked about each change only while there are any. */
internal class Filters(private val plugin: XRewindPlugin) {
    private val byOwner = concurrentMap<LogFilter, String>()

    @Volatile
    private var any = false

    fun add(owner: String, filter: LogFilter) {
        byOwner[filter] = owner
        any = true
    }

    fun remove(owner: String) {
        for ((f, o) in byOwner.entries.toList()) if (o == owner) byOwner.remove(f)
        any = byOwner.isNotEmpty()
    }

    fun allow(world: WorldRef, x: Int, y: Int, z: Int, actor: String): Boolean {
        if (!any) return true
        for ((filter, owner) in byOwner.entries) {
            val logs = try {
                filter.logs(world, x, y, z, actor)
            } catch (e: Exception) {
                plugin.log.warn("$owner's log filter failed: $e")
                true
            }
            if (!logs) return false
        }
        return true
    }
}

/** `/xrewind debug`'s report or `forget`'s player data, made when the command first asks. */
private class AdminPart<T>(private val plugin: XRewindPlugin, private val debug: Boolean) : () -> T {
    @Suppress("UNCHECKED_CAST")
    override fun invoke(): T = (if (debug) AdminParts.debug(plugin) else AdminParts.playerData(plugin)) as T
}
