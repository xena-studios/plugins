package co.xenastudios.xstats

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.event.Event as ApiEvent
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
import co.xenastudios.core.common.command.ModuleReloader
import co.xenastudios.core.common.command.OnlinePlayers
import co.xenastudios.core.common.config.Config
import co.xenastudios.core.common.db.ChangeFeed
import co.xenastudios.core.common.db.Database
import co.xenastudios.core.common.db.DbError
import co.xenastudios.core.common.db.migrate
import co.xenastudios.core.common.event.SimpleEventBus
import co.xenastudios.core.common.family.PlayerCache
import co.xenastudios.core.common.i18n.LanguageSource
import co.xenastudios.core.common.i18n.MessageKey
import co.xenastudios.core.common.i18n.Translations
import co.xenastudios.core.common.locked
import co.xenastudios.core.common.module.Module
import co.xenastudios.core.common.module.ModuleContext
import co.xenastudios.core.common.module.ModuleSet
import co.xenastudios.core.common.permission.PermissionRegistrar
import co.xenastudios.core.common.platform.Platform
import co.xenastudios.core.common.scheduler.TICK
import co.xenastudios.core.common.scheduler.Task
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.update.UpdateChecker
import co.xenastudios.core.common.update.UpdateSettings
import co.xenastudios.xstats.api.CustomStat
import co.xenastudios.xstats.api.LeaderboardEntry
import co.xenastudios.xstats.api.Period
import co.xenastudios.xstats.api.ProfileSection
import co.xenastudios.xstats.api.StatInfo
import co.xenastudios.xstats.api.StatUnit
import co.xenastudios.xstats.api.StatsProfile
import co.xenastudios.xstats.api.XStats
import co.xenastudios.xstats.api.XStatsApi
import co.xenastudios.xstats.api.event.LeaderboardChangeEvent
import co.xenastudios.xstats.api.event.StatChangeEvent
import co.xenastudios.xstats.api.event.StatsSavedEvent
import co.xenastudios.xstats.config.XStatsConfig
import co.xenastudios.xstats.data.Board
import co.xenastudios.xstats.data.Calendar
import co.xenastudios.xstats.data.Engine
import co.xenastudios.xstats.data.Event
import co.xenastudios.xstats.data.PlayerRecord
import co.xenastudios.xstats.data.Zones
import co.xenastudios.xstats.stat.Catalog
import co.xenastudios.xstats.stat.Definitions
import co.xenastudios.xstats.store.Q
import co.xenastudios.xstats.store.Store
import co.xenastudios.xstats.store.Tables
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** What the plugin's files said when they were last read: the config, the lang files, and the problems found. */
public class Loaded(
    public val config: Config,
    public val translations: Translations,
    /** Each problem, in English, already logged. */
    public val problems: List<String> = emptyList(),
)

/** xStats' files: on the JVM, `JvmFiles`. [load] reads `config.yml` and `lang/`. */
public fun interface Files {
    public fun load(): Loaded
}

/** Opens the database a config names (on the JVM, `JvmDatabase`), off the server's threads, or says why it couldn't, in English. */
public fun interface Opener {
    public fun open(config: Config): Outcome<Database, String>
}

/**
 * What only the JVM has: the world's stats files, the website and the time limit on other plugins'
 * profile sections (`JvmExtras`). `null` in tests that don't need them.
 */
public interface Extras {
    /** Reads players' history from the world's stats files and adds what's new, telling [reply] how it went. Off the server's threads. */
    public fun importHistory(plugin: XStatsPlugin, firstStart: Boolean, reply: (MessageKey, List<Pair<String, Any>>) -> Unit)

    /** The website while `web.enabled` is on: a module, made from the config. */
    public fun webModule(plugin: XStatsPlugin): Module

    /** The website's port while it runs, or 0. */
    public val webPort: Int

    /** Starts a test copy of the website on this machine, checks it and stops it: how many checks, and what failed. Blocks. */
    public fun webTest(plugin: XStatsPlugin): Pair<Int, List<String>>

    /** Writes the website as files: the folder's name, or why it couldn't. Blocks. */
    public fun webExport(plugin: XStatsPlugin): Outcome<String, String>

    /** Runs each of [tasks] at once, giving each until [millis] from now: what each gave, `null` for one too slow or failed. Blocks. */
    public fun <T> all(tasks: List<() -> T>, millis: Long): List<T?>

    /** Makes the website's pages again after a save, if it's running. On the database thread. */
    public fun pagesChanged(plugin: XStatsPlugin) {}
}

/**
 * xStats, the same on every server and on the proxy: each adapter makes one with its [Platform] and
 * its [StatsServer], reads the files and calls [start] as the plugin enables; the database is
 * opened, migrated and every player read off the server's threads ([open]). Everything the config
 * says applies on `/xstats reload`, except where the stats are kept (`storage`, `server-name` and
 * `network`), which applies on restart, as in 1.x.
 *
 * Counting is cheap: other plugins' changes and joins wait in memory ([co.xenastudios.xstats.data.Inbox]),
 * Minecraft's own counters are read every `collecting.save-every-minutes` on each player's own
 * thread, a few a tick, and everything is written in one transaction per save on the database's
 * own thread. Nothing is written per event.
 */
public class XStatsPlugin(
    public val platform: Platform,
    public val server: StatsServer,
    private val files: Files,
    languages: LanguageSource,
    /** Who's online, by name, as each command source can see them. */
    internal val players: OnlinePlayers,
    /** The family's player cache: names of players who aren't online, and who's vanished. */
    public val cache: PlayerCache? = null,
    private val opener: Opener,
    internal val registrar: CommandRegistrar? = null,
    internal val permissionRegistrar: PermissionRegistrar? = null,
    /** Where `/xstats debug` uploads its report; `null` to write a file. */
    internal val paste: PasteService? = null,
    /** Makes the website's and the import's code: called the first time either is wanted, not as the plugin starts. */
    private val makeExtras: (() -> Extras)? = null,
    private val zones: Zones = Zones.UTC_ONLY,
    override val version: String = VERSION,
) : XStatsApi, (Task) -> Unit, () -> Unit, (Board, PlayerRecord?) -> Unit {

    /** Where the plugin's console lines go; the last 50 warnings and errors are kept for `/xstats debug`. */
    public val log: RecentErrors = RecentErrors(platform.log, platform.clock)

    public val texts: Texts = Texts(languages, server)

    /** The API's events. On Bukkit the adapter bridges them to Bukkit events. */
    public val bus: SimpleEventBus = SimpleEventBus(log)

    override val events: EventBus get() = bus

    override val proxy: Boolean get() = server.proxy

    public val modules: ModuleSet = ModuleSet(platform)

    @Volatile
    public var config: Config = XStatsConfig.defaults
        private set

    @Volatile
    internal var problems: List<String> = emptyList()
        private set

    public val catalog: Catalog = Catalog()

    internal val registered = ArrayList<Definitions.Registered>()

    public val engine: Engine = Engine(catalog, settings(XStatsConfig.defaults), log, platform.clock, readOnly = server.proxy)

    /** The database, or `null` before it's open. */
    @Volatile
    public var database: Database? = null
        private set

    /** Why there's no database, if opening it failed. */
    @Volatile
    public var failure: String? = null
        internal set

    /** Settled `true` once the database is open and every player is in memory, `false` if it couldn't be opened. */
    public val ready: Pending<Boolean> = newPending()

    @Volatile
    public var running: Boolean = false
        internal set

    @Volatile
    private var madeExtras: Extras? = null

    /** The website and the import, made the first time they're wanted. */
    public val extras: Extras?
        get() = madeExtras ?: makeExtras?.let { make -> locked(this) { madeExtras ?: make().also { madeExtras = it } } }

    /** [extras] if anything has made it yet: whether the website could be running. */
    internal val extrasIfMade: Extras? get() = madeExtras

    @Volatile
    private var madeSections: Sections? = null

    /** Other plugins' profile sections, made with the first one or the first profile. */
    public val sections: Sections
        get() = madeSections ?: locked(this) { madeSections ?: Sections(this).also { madeSections = it } }

    /** The save every `collecting.save-every-minutes` (the proxy's refresh), and what it was started with. */
    internal var collector: Task? = null
    private var collectorEvery = 0

    /** Boards in the world, as last read. */
    @Volatile
    internal var boards: List<Store.BoardRow> = emptyList()

    internal val saveLock = Any()
    internal var saving = false

    /** `/xstats reload`, `debug` and `forget`, or on the proxy `/xstatsproxy`'s. */
    @Suppress("UNCHECKED_CAST")
    public val admin: AdminCommands = AdminCommands(
        PLUGIN_ID, NAME, platform, reloader(),
        debug = Reloading(2) as () -> co.xenastudios.core.common.admin.Debug,
        playerData = if (server.proxy) null else Reloading(3) as () -> co.xenastudios.core.common.admin.PlayerDataRegistry,
        text = texts.admin,
    )

    private val commands = Commands(this)

    @Volatile
    public var dispatcher: CommandDispatcher = CommandDispatcher(commands.tree(config), platform.clock, log)
        private set

    public val permissions: XStatsPermissions get() = commands.permissions

    @Volatile
    public var updates: UpdateChecker? = null

    public val updateSettings: UpdateSettings get() = XStatsConfig.updates.settings(config)

    @Volatile
    internal var madeActions: Actions? = null

    /**
     * What the adapter does just before the first event could be heard: Bukkit's bridges the API's
     * events to Bukkit events there, so their classes don't load as the server starts.
     */
    @Volatile
    public var beforeFirstEvent: (() -> Unit)? = null

    /** Tests only: network mode on an SQLite file two plugins share, standing in for a network's MySQL. */
    internal var networkOnAnyDatabase: Boolean = false

    // ---- starting and stopping ----

    /** Turns the plugin on with what [Files] read: the stats, permissions and commands. The database comes with [open]. */
    public fun start(loaded: Loaded) {
        running = true
        engine.vanishedIn = cache
        engine.onNewLeader = this
        // Someone hidden or shown again: the website's pages are made again straight away, not at the next save.
        engine.onHiddenChanged = this
        apply(loaded)
    }

    /** Opens the database the config names, off the server's threads, then reads every player. Any thread. */
    public fun open() {
        platform.scheduler.run(Where.Async, action = Opening(this, config))
    }

    /** Opening the database: its tables, the change feed (on a network), every player. One class for its steps rather than a lambda each. */
    private class Opening(private val plugin: XStatsPlugin, private val config: Config) : (Task) -> Unit, (Any?, Throwable?) -> Unit {
        private var step = 0
        private var db: Database? = null
        private var feed: ChangeFeed? = null

        override fun invoke(task: Task) {
            if (!plugin.running) return
            db = when (val opened = plugin.opener.open(config)) {
                is Outcome.Ok -> opened.value
                is Outcome.Err -> return fail(opened.error, false)
            }
            db!!.migrate(Tables.MIGRATIONS).onSettled(this)
        }

        override fun invoke(value: Any?, error: Throwable?) {
            val db = db!!
            when (step++) {
                0 -> {
                    if (value !is Outcome.Ok<*>) return fail("Couldn't bring ${db.description}'s tables up to date: ${(value as? Outcome.Err<*>)?.error ?: error}", true)
                    val network = config[XStatsConfig.network.enabled]
                    if (!network) return invoke(Outcome.Ok(null), null)
                    val settings = XStatsConfig.network.read(config)
                    if (plugin.networkOnAnyDatabase) {
                        ChangeFeed.openOnAny(db, settings, plugin.platform.scheduler, plugin.platform.clock, plugin.log, kotlin.uuid.Uuid.random().toHexString().substring(0, 16)).onSettled(this)
                    } else {
                        ChangeFeed.open(db, settings, plugin.platform.scheduler, plugin.platform.clock, plugin.log).onSettled(this)
                    }
                }
                1 -> {
                    if (value !is Outcome.Ok<*>) return fail("Couldn't start network mode: ${(value as? Outcome.Err<*>)?.error ?: error}", true)
                    feed = (value.value as? ChangeFeed)?.takeIf { it.isOn }
                    db.session(Load(plugin, db, feed, configServer(config))).onSettled(this)
                }
                else -> {
                    if (value !is Outcome.Ok<*>) return fail("Couldn't read the stats from ${db.description}: ${(value as? Outcome.Err<*>)?.error ?: error}", true)
                    if (!plugin.running) {
                        feed?.close()
                        db.close()
                        return
                    }
                    plugin.database = db
                    plugin.failure = null
                    feed?.let {
                        for (kind in 0..2) News(plugin, kind).subscribe(it)
                        it.start()
                    }
                    val imported = value.value as Boolean
                    if (plugin.server.proxy) {
                        plugin.log.info("Network stats ready: ${plugin.engine.playerCount} players in ${db.description}" + if (feed != null) ", read again as the servers save." else ".")
                        if (feed == null) {
                            plugin.log.warn("xStats on the proxy reads ${db.description}, which the servers don't share, so it has no server's stats. " +
                                "Give the proxy and every server the same MySQL or MariaDB database in storage, with network.enabled: true.")
                        }
                    } else {
                        plugin.log.info("Stats ready: ${plugin.engine.playerCount} players in ${db.description}." + if (feed != null) " Sharing stats with the network." else "")
                    }
                    XStats.register(plugin)
                    plugin.ready.resolve(true)
                    plugin.syncCollector()
                    val wanted = plugin.wantedModules()
                    if (wanted.isNotEmpty()) plugin.modules.sync(wanted)
                    plugin.afterSave()
                    // Players online already (a reload of the server): treat them as joining now.
                    plugin.rejoin()
                    if (!plugin.server.proxy && !imported && plugin.config[XStatsConfig.collecting.importOnFirstStart]) {
                        val extras = plugin.extras
                        // Off the database's thread, which the import waits on.
                        if (extras != null) plugin.platform.scheduler.run(Where.Async) { _ -> extras.importHistory(plugin, true, plugin.logReply()) }
                    }
                }
            }
        }

        private fun fail(why: String, opened: Boolean) {
            feed?.close()
            if (opened) db?.close()
            plugin.failure = why
            plugin.log.error(why)
            plugin.log.error("xStats can't keep stats without its database. Fix storage in config.yml and restart.")
            plugin.ready.resolve(false)
        }

        private fun configServer(config: Config): String {
            val name = config[XStatsConfig.serverName]
            if (SERVER_NAME.matches(name)) return name
            plugin.log.warn("config.yml: server-name can only use letters, digits, - and _. Using \"main\".")
            return "main"
        }
    }

    /** The first read, on the database's writer: this server's id, every player, the boards, and whether the import ran. */
    private class Load(private val plugin: XStatsPlugin, private val db: Database, private val feed: ChangeFeed?, private val serverName: String) :
        (co.xenastudios.core.common.db.SqlSession) -> Outcome<Boolean, DbError> {
        override fun invoke(session: co.xenastudios.core.common.db.SqlSession): Outcome<Boolean, DbError> = Q.run(session, db.sql) { q ->
            val id = if (plugin.server.proxy) 0 else q.dictionary("servers", serverName)
            val store = Store(id, serverName)
            plugin.engine.open(q, store, feed)
            if (plugin.server.proxy) {
                true
            } else {
                plugin.boards = store.boards(q)
                store.flag(q, "imported")
            }
        }
    }

    /** The change feed's news: another server saved, a player went private (or was forgotten), or news was missed. */
    private class News(private val plugin: XStatsPlugin, private val kind: Int) : (co.xenastudios.core.common.db.Change) -> Unit, () -> Unit {
        fun subscribe(feed: ChangeFeed) {
            when (kind) {
                0 -> feed.subscribe("saved", this)
                1 -> feed.subscribe("player", this)
                else -> feed.onResync(this)
            }
        }

        override fun invoke(change: co.xenastudios.core.common.db.Change) {
            if (kind == 0) {
                plugin.engine.networkChanged()
                return
            }
            val player = PlayerRef.parseOrNull(change.payload.trim()) ?: return
            plugin.onDatabase { q -> plugin.engine.refreshHidden(q, player) }
        }

        override fun invoke() {
            plugin.engine.networkChanged()
        }
    }

    /** Turns the plugin off, as the server stops: the API, then one last save (without placeholders: other plugins may be gone), then the database. */
    public fun stop(lastReadings: List<Event.Sample> = emptyList()): Pending<Unit> = stopNow(lastReadings)

    @Suppress("UNCHECKED_CAST")
    private fun reloader(): ModuleReloader =
        ModuleReloader(modules, reloadFiles = Reloading(0) as () -> List<String>, wanted = Reloading(1) as () -> Map<String, () -> Module>)

    // /xstats reload: the files again, then everything built from them; and the modules then. And
    // /xstats debug's report and forget's player data, made when the command first asks.
    private inner class Reloading(private val kind: Int) : () -> Any {
        override fun invoke(): Any = when (kind) {
            0 -> apply(files.load())
            1 -> wantedModules()
            2 -> AdminParts.debug(this@XStatsPlugin)
            else -> AdminParts.playerData(this@XStatsPlugin)
        }
    }

    /** The modules the config turns on: the website while `web.enabled` is on, once the database is open. */
    internal fun wantedModules(): Map<String, () -> Module> {
        if (database == null || makeExtras == null || !config[XStatsConfig.web.enabled]) return emptyMap()
        return mapOf(webKey() to WebFactory(this))
    }

    /**
     * Starts the save (a server's one repeating task), or the proxy's refresh, at what the config
     * says, again if that changed. A plain task, not a module: it's always on once the database is.
     */
    internal fun syncCollector() {
        val every = if (server.proxy) config[XStatsConfig.proxy.refreshSeconds] else config[XStatsConfig.collecting.saveEveryMinutes]
        locked(saveLock) {
            if (!running || database == null || (collector != null && every == collectorEvery)) return
            collector?.cancel()
            val period = if (server.proxy) every.seconds else every.minutes
            collectorEvery = every
            collector = platform.scheduler.run(Where.Async, delay = period, period = period, action = this)
        }
    }

    // Bumped when the website fails to start (the port was taken), so a reload tries again (webKey).
    @Volatile
    internal var webFailures = 0

    internal fun webFailed() {
        webFailures++
    }

    /** Uses what [Files] read: the stats and commands rebuilt, periods read again if they changed. On the global thread. */
    public fun apply(loaded: Loaded): List<String> {
        config = loaded.config
        texts.translations = loaded.translations
        val warnings = ArrayList<String>()
        rebuildStats(warnings)
        val before = engine.settings
        val settings = settings(config, warnings)
        engine.settings = settings
        if (!config[XStatsConfig.leaderboards.excludeBanned]) engine.bannedPlayers(emptySet())
        dispatcher = CommandDispatcher(commands.tree(config), platform.clock, log)
        permissionRegistrar?.sync(permissions.permissions + admin.permissions.permissions)
        warnings += commands.warnings
        warnings += registrar?.sync(dispatcher).orEmpty()
        warnings.forEach(log::warn)
        problems = loaded.problems + warnings
        updates?.apply(updateSettings)
        if (database != null) {
            syncCollector()
            // A new time zone or first day of the week: this day's, week's and month's numbers are read again for it.
            val newCalendar = before.calendar != settings.calendar
            onDatabase { q ->
                if (newCalendar) engine.reload(q) else engine.recomputeBoards()
                afterSave()
            }
        }
        return problems
    }

    private fun settings(config: Config, warnings: MutableList<String>? = null): Engine.Settings {
        val p = XStatsConfig.periods
        val text = config[p.timezone].trim()
        val zone = if (text.isEmpty() || text.equals("server", ignoreCase = true)) zones.server else zones.of(text) ?: zones.server.also {
            warnings?.add("config.yml: periods.timezone \"$text\" isn't a time zone, such as Europe/London. Using the server's own: ${it.id}.")
        }
        return Engine.Settings(
            Calendar(zone, config[p.weekStarts]),
            config[XStatsConfig.collecting.afkAware],
            config[XStatsConfig.collecting.saveEveryMinutes].minutes.inWholeMilliseconds,
            config[p.keepDailyDays],
            config[XStatsConfig.network.enabled],
        )
    }

    // The config's stats, then the registered ones, as the catalog.
    internal fun rebuildStats(warnings: MutableList<String>? = null) {
        val list = locked(registered) { registered.toList() }
        val problems = warnings ?: ArrayList()
        val notes = ArrayList<String>()
        val stats = Definitions.build(config[XStatsConfig.stats], list, catalog, server.vanilla, problems, notes)
        catalog.set(stats)
        // A problem goes with the config's (or, outside a load, to the log); a note is only information.
        for (note in notes) log.info(note)
        if (warnings == null) for (problem in problems) log.warn(problem)
    }

    /** Runs [work] on the database's writer, after what's queued, with its errors logged. Does nothing before the database is open. */
    public fun onDatabase(work: (Q) -> Unit) {
        val db = database ?: return
        db.session(OnWriter(this, db, work))
    }

    private class OnWriter(private val plugin: XStatsPlugin, private val db: Database, private val work: (Q) -> Unit) :
        (co.xenastudios.core.common.db.SqlSession) -> Outcome<Unit, DbError> {
        override fun invoke(session: co.xenastudios.core.common.db.SqlSession): Outcome<Unit, DbError> {
            val result = try {
                Q.run(session, db.sql, work)
            } catch (e: Exception) {
                plugin.log.error("Something went wrong on xStats' database thread", e)
                Outcome.Ok(Unit)
            }
            if (result is Outcome.Err) plugin.log.warn("xStats couldn't use ${db.description}: ${result.error.detail}")
            // Never an error back to the writer, which would try this again: what failed waits for the next save.
            return Outcome.Ok(Unit)
        }
    }

    internal fun now(): Long = platform.clock.now().toEpochMilliseconds()

    // ---- saving ----

    /**
     * Starts a save now, unless one is running, and runs [done] after it with whether it reached
     * the database. Each online player is read on their own thread, [XStatsConfig.Collecting.readsPerTick]
     * a tick; then, once every reading is in, the save runs on the database's writer. Any thread.
     */
    public fun save(done: ((Boolean) -> Unit)? = null): Boolean = saveNow(done)

    /** After each save, on the database thread: the website's pages, then boards in the world. */
    internal fun afterSave() {
        try {
            madeExtras?.pagesChanged(this)
        } catch (e: Exception) {
            log.warn("Couldn't make the website's pages: $e")
        }
        updateBoards()
    }

    /** Sets every board's text from the leaderboards just worked out, each on its own place's thread. */
    internal fun updateBoards() {
        val rows = boards
        // No boards, and none since the start: nothing to show or stop listening for.
        if (rows.isEmpty() && !hadBoards) return
        val host = server.boards ?: return
        host.listen(rows)
        hadBoards = rows.isNotEmpty()
        for (row in rows) host.show(row, Boards.text(this, row))
    }

    @Volatile
    private var hadBoards = false

    // ---- players ----

    /** [player] joined, on their thread: a session starts. Before other plugins' join listeners, so their first changes find the player known. */
    public fun joined(player: PlayerRef, name: String, firstPlayed: Long, yaw: Float, pitch: Float) {
        engine.queue(Event.Joined(player, name, now(), firstPlayed, yaw, pitch))
    }

    /** [player] is leaving: their last reading, made on their thread. */
    public fun quit(player: PlayerRef) {
        server.read(player, catalog.snapshot, quit = true, placeholders = true)?.let(engine::queue)
    }

    // Players already online as the database opens (a reload of the server).
    private fun rejoin() {
        if (server.proxy) return
        for (player in server.online()) {
            platform.scheduler.run(Where.On(player.uuid), action = Rejoin(this, player))
        }
    }

    private class Rejoin(private val plugin: XStatsPlugin, private val player: PlayerRef) : (Task) -> Unit {
        override fun invoke(task: Task) {
            val sample = plugin.server.read(player, plugin.catalog.snapshot, quit = false, placeholders = false) ?: return
            plugin.joined(player, sample.name, 0, sample.yaw, sample.pitch)
        }
    }

    /** A reply that goes to the console: the first start's import. */
    internal fun logReply(): (MessageKey, List<Pair<String, Any>>) -> Unit = { message, placeholders ->
        texts.render(texts.language(null), message, placeholders)?.plain()?.let { log.info(it) }
    }

    /** Whether anything listens to [type], after bridging to the platform's events the first time it's asked. */
    internal fun <E : ApiEvent> hasListeners(type: EventType<E>): Boolean {
        val bridge = beforeFirstEvent
        if (bridge != null) {
            val run = locked(this) { if (beforeFirstEvent === bridge) true.also { beforeFirstEvent = null } else false }
            if (run) bridge()
        }
        return bus.hasListeners(type)
    }

    // ---- the API ----

    override fun stats(): List<StatInfo> = statsNow()

    override fun stat(id: String): StatInfo? = catalog.snapshot[id]?.info

    override fun value(player: PlayerRef, stat: String, period: Period): Long = valueNow(player, stat, period)

    override fun rank(player: PlayerRef, stat: String, period: Period): Int = rankNow(player, stat, period)

    override fun top(stat: String, period: Period, count: Int): List<LeaderboardEntry> = topNow(stat, period, count)

    override fun profile(player: PlayerRef): Pending<StatsProfile?> = profileNow(player)

    override fun isPrivate(player: PlayerRef): Boolean = engine.player(player)?.hidden ?: false

    override fun registerStat(owner: String, id: String, name: String, unit: StatUnit, latest: Boolean, decimals: Int): CustomStat =
        registerStatNow(owner, id, name, unit, latest, decimals)

    override fun unregisterStats(owner: String) {
        val removed = locked(registered) { registered.removeAll { it.plugin == owner } }
        if (removed) rebuildStats()
    }

    override fun registerProfileSection(owner: String, id: String, title: String, section: ProfileSection) {
        sections.add(owner, id, title, section)
    }

    override fun unregisterProfileSection(owner: String, id: String) {
        sections.remove(owner, id)
    }

    // ---- what the scheduler and the engine call: one class with the plugin, not one each ----

    /**
     * The save, xStats' one repeating task on a server, every `collecting.save-every-minutes`; on the
     * proxy, a check every `proxy.refresh-seconds` whether a server saved, which reads the network's
     * numbers again only if one did. Not for other plugins.
     */
    override fun invoke(task: Task) {
        if (server.proxy && !engine.dirty) return
        save()
    }

    /** Someone went private or public: the website's pages again, on the database thread. Not for other plugins. */
    override fun invoke() {
        try {
            madeExtras?.pagesChanged(this)
        } catch (e: Exception) {
            log.warn("Couldn't make the website's pages: $e")
        }
    }

    /** A board with a new leader, at a save: the API's event, only when something listens. Not for other plugins. */
    override fun invoke(board: Board, previous: PlayerRecord?) {
        if (!hasListeners(LeaderboardChangeEvent.TYPE)) return
        val leader = board.leader ?: return
        bus.fire(
            LeaderboardChangeEvent.TYPE,
            LeaderboardChangeEvent(board.stat.id, board.period, leader.id, leader.name ?: leader.id.toString(), previous?.id, board.values[0]),
        )
    }

    public companion object {
        public const val PLUGIN_ID: String = "xstats"
        public const val NAME: String = "xStats"

        /** xStats' version. The adapters' builds check theirs is the same. */
        public const val VERSION: String = "2.0.0"

        /**
         * The plugins [Integrations] keeps stats for, by name: a constant, so an adapter can ask
         * whether one is installed without loading the hooks' code. FamilyTest checks it's theirs.
         */
        public const val FAMILY: String = "xVote,xMoney,xBans,xTeleport,xRewind,xMarket,zChat"

        private val SERVER_NAME = Regex("[A-Za-z0-9_-]{1,64}")
    }
}

/** The website's module, made when it's turned on. */
internal class WebFactory(private val plugin: XStatsPlugin) : () -> Module {
    override fun invoke(): Module = plugin.extras!!.webModule(plugin)
}
