package co.xenastudios.xbans

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
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
import co.xenastudios.core.common.command.CommandSource
import co.xenastudios.core.common.command.ModuleReloader
import co.xenastudios.core.common.command.OnlinePlayers
import co.xenastudios.core.common.concurrentMap
import co.xenastudios.core.common.config.Config
import co.xenastudios.core.common.db.ChangeFeed
import co.xenastudios.core.common.db.Database
import co.xenastudios.core.common.db.DbError
import co.xenastudios.core.common.db.migrate
import co.xenastudios.core.common.event.SimpleEventBus
import co.xenastudios.core.common.family.PlayerCache
import co.xenastudios.core.common.i18n.LanguageSource
import co.xenastudios.core.common.i18n.Translations
import co.xenastudios.core.common.locked
import co.xenastudios.core.common.module.Module
import co.xenastudios.core.common.module.ModuleSet
import co.xenastudios.core.common.permission.PermissionRegistrar
import co.xenastudios.core.common.platform.Platform
import co.xenastudios.core.common.proxy.ProxyChannel
import co.xenastudios.core.common.scheduler.Task
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.text.Component
import co.xenastudios.core.common.update.UpdateChecker
import co.xenastudios.core.common.update.UpdateSettings
import co.xenastudios.xbans.api.Alt
import co.xenastudios.xbans.api.HistoryPage
import co.xenastudios.xbans.api.PunishRequest
import co.xenastudios.xbans.api.PunishResult
import co.xenastudios.xbans.api.Punishment
import co.xenastudios.xbans.api.PunishmentType
import co.xenastudios.xbans.api.XBans
import co.xenastudios.xbans.api.XBansApi
import co.xenastudios.xbans.config.XBansConfig
import co.xenastudios.xbans.model.Active
import co.xenastudios.xbans.model.Entry
import co.xenastudios.xbans.store.Backend
import co.xenastudios.xbans.store.IpSetup
import co.xenastudios.xbans.store.Tables
import co.xenastudios.xbans.store.alts
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.seconds

/** What the plugin's files said when they were last read: the config, the lang files, and the problems found. */
public class Loaded(
    public val config: Config,
    public val translations: Translations,
    /** Each problem, in English, already logged. */
    public val problems: List<String> = emptyList(),
)

/** xBans' files: on the JVM, `JvmFiles`. [load] reads `config.yml` and `lang/`. */
public fun interface Files {
    public fun load(): Loaded
}

/** Opens the database a config names (on the JVM, `JvmDatabase`), off the server's threads, or says why it couldn't, in English. */
public interface Opener {
    public fun open(config: Config): Outcome<Database, String>

    /** What [open] would open, for telling whether a reload asks for another database. */
    public fun describe(config: Config): String
}

/**
 * xBans, the same on every server and on the proxies: each adapter makes one with its [Platform]
 * and its [BansServer], reads the files and calls [start] as the plugin enables; the database is
 * opened off the server's threads. Until its bans are read, nobody joins ([ready]): letting
 * everyone in while the database is down would let banned players in too. Everything the config
 * says applies on `/xbans reload`, the database included (1.x's rule): a new one's bans are read
 * before it's used, and one that can't be opened leaves the old one in use.
 */
public class XBansPlugin(
    public val platform: Platform,
    /** What the adapter does to players. */
    public val server: BansServer,
    private val files: Files,
    languages: LanguageSource,
    /** Who's online, by name, as each command source can see them. */
    internal val players: OnlinePlayers,
    /** The family's player cache: names of players who aren't online, for every command naming a player. */
    internal val cache: PlayerCache? = null,
    private val opener: Opener,
    internal val registrar: CommandRegistrar? = null,
    internal val permissionRegistrar: PermissionRegistrar? = null,
    /** What `/xbans debug`'s report says about the server. */
    internal val about: () -> Map<String, String> = { emptyMap() },
    /** Where `/xbans debug` uploads its report; `null` to write a file. */
    internal val paste: PasteService? = null,
    /** The importers the adapter offers, for `/xbans import`. */
    public val imports: Imports? = null,
    /** The proxy layer's channel, `xena:xbans`, behind a proxy and on it; `null` without one. */
    internal val channel: ProxyChannel? = null,
    override val version: String = VERSION,
) : XBansApi {

    /** Where the plugin's console lines go; the last 50 warnings and errors are kept for `/xbans debug`. */
    public val log: RecentErrors = RecentErrors(platform.log, platform.clock)

    /** The messages, in each reader's language. */
    public val texts: Texts = Texts(languages, server)

    /** The API's events. On Bukkit the adapter bridges them to Bukkit events. */
    public val bus: SimpleEventBus = SimpleEventBus(log)

    override val events: EventBus get() = bus

    public val modules: ModuleSet = ModuleSet(platform)

    @Volatile
    public var config: Config = XBansConfig.defaults
        private set

    @Volatile
    internal var problems: List<String> = emptyList()
        private set

    /** What's open, or `null` before the first database is ready. */
    @Volatile
    public var backend: Backend? = null
        internal set

    /** Why there's no database, while opening it fails. */
    @Volatile
    public var failure: String? = null
        internal set

    /** Settled `true` once the first database is open and its bans are in memory. */
    public val ready: Pending<Boolean> = newPending()

    @Volatile
    public var running: Boolean = false
        internal set

    public val sessions: Sessions = Sessions()

    /** The proxy only: the server each player is on, by the proxy's name for it. */
    private val seats = concurrentMap<PlayerRef, String>()

    // Ids the proxy applied from a server's message, so the feed's copy isn't applied twice.
    private val heard = LinkedHashSet<Long>()

    @Volatile
    private var madePunisher: Punisher? = null
    public val punisher: Punisher get() = madePunisher ?: locked(this) { madePunisher ?: Punisher(this).also { madePunisher = it } }

    @Volatile
    private var madeNetwork: Network? = null
    internal val network: Network get() = madeNetwork ?: locked(this) { madeNetwork ?: Network(this).also { madeNetwork = it } }

    /** `/xbans reload`, `debug` and `forget`, or on a proxy `/xbansproxy`'s. */
    public val admin: AdminCommands = AdminCommands(
        PLUGIN_ID, NAME, platform, reloader(),
        // One class, not two lambdas, and neither part loads until its command runs (AdminCommands).
        debug = AdminPart(this, true),
        playerData = AdminPart(this, false),
        text = texts.admin,
    )

    private val commands = Commands(this)

    /** What runs the commands; built again on reload, from the new config. */
    @Volatile
    public var dispatcher: CommandDispatcher = CommandDispatcher(commands.tree(config), platform.clock, log)
        private set

    @Volatile
    public var permissions: XBansPermissions = commands.permissions
        private set

    /** The platform's update checker, which the adapter starts; every reload gives it the config's settings again. */
    @Volatile
    public var updates: UpdateChecker? = null

    public val updateSettings: UpdateSettings get() = XBansConfig.updates.settings(config)

    // ---- starting and stopping ----

    /** Turns the plugin on with what [Files] read: permissions and commands. The database comes with [open]. */
    public fun start(loaded: Loaded) {
        running = true
        apply(loaded)
        channel?.let { listen(it) }
    }

    /** Opens the database the config names, off the server's threads, then reads what's in force. Any thread. */
    public fun open() {
        val wanted = opener.describe(config)
        val ticket = locked(this) { ++generation }
        this.wanted = wanted
        platform.scheduler.run(Where.Async, action = OpenTask(config, ticket))
    }

    // Opening, or trying again while there's no database, off the server's threads: a class, not a lambda.
    internal fun retryTask(config: Config, ticket: Int): (Task) -> Unit = OpenTask(config, ticket, retry = true)

    private inner class OpenTask(private val config: Config, private val ticket: Int, private val retry: Boolean = false) : (Task) -> Unit {
        override fun invoke(task: Task) {
            if (!retry || backend == null) openNow(config, ticket)
        }
    }

    /** Tests only: network mode on an SQLite file two plugins share, standing in for a network's MySQL. */
    internal var networkOnAnyDatabase: Boolean = false

    @Volatile
    internal var wanted: String? = null
    private var generation = 0
    @Volatile
    internal var lastProblem: String? = null

    internal fun current(ticket: Int): Boolean = locked(this) { ticket == generation } && running

    private fun openNow(config: Config, ticket: Int) {
        if (!current(ticket)) return
        val db = when (val opened = opener.open(config)) {
            is Outcome.Ok -> opened.value
            is Outcome.Err -> return failedToOpen(opened.error, config, ticket, null)
        }
        Opening(db, config, ticket).start()
    }

    /**
     * Opening a database: its tables, how IPs are keyed, the change feed (on a network), what's in
     * force. One class for its steps rather than a lambda each (each would be a class of its own,
     * made as the server starts: decisions/2026-11-25-a-lighter-core.md). On the database's threads.
     */
    private inner class Opening(private val db: Database, private val config: Config, private val ticket: Int) :
        (Any?, Throwable?) -> Unit, (Task) -> Unit {
        private var step = 0
        private var keys: co.xenastudios.xbans.ip.IpKeys? = null
        private var feed: ChangeFeed? = null
        private var fresh: Backend? = null

        fun start() {
            db.migrate(Tables.MIGRATIONS).onSettled(this)
        }

        override fun invoke(value: Any?, error: Throwable?) {
            when (step++) {
                0 -> {
                    if (value !is Outcome.Ok<*>) return fail("couldn't bring ${db.description}'s tables up to date: ${(value as? Outcome.Err<*>)?.error ?: error}")
                    db.session(IpStep(this@XBansPlugin, db, config[XBansConfig.ips.hash])).onSettled(this)
                }
                1 -> {
                    @Suppress("UNCHECKED_CAST")
                    when (val outcome = (value as? Outcome.Ok<*>)?.value as? Outcome<co.xenastudios.xbans.ip.IpKeys, String>) {
                        is Outcome.Ok -> keys = outcome.value
                        is Outcome.Err -> return fail(outcome.error)
                        null -> return fail("couldn't read how IPs are kept: ${(value as? Outcome.Err<*>)?.error ?: error}")
                    }
                    // The feed first, so it starts from before the load: a change made while the bans
                    // load is in both, and memory ignores the second copy. Off a network, none.
                    val settings = XBansConfig.network.read(config)
                    when {
                        !settings.enabled -> invoke(Outcome.Ok(null), null)
                        networkOnAnyDatabase -> ChangeFeed.openOnAny(db, settings, platform.scheduler, platform.clock, log, kotlin.uuid.Uuid.random().toHexString().substring(0, 16)).onSettled(this)
                        else -> ChangeFeed.open(db, settings, platform.scheduler, platform.clock, log).onSettled(this)
                    }
                }
                2 -> {
                    if (value !is Outcome.Ok<*>) return fail("couldn't start network mode: ${(value as? Outcome.Err<*>)?.error ?: error}")
                    feed = (value.value as? ChangeFeed)?.takeIf { it.isOn }
                    val backend = Backend(db, feed, Active(), keys!!)
                    fresh = backend
                    db.read(InForce(backend, now())).onSettled(this)
                }
                else -> {
                    if (value !is Outcome.Ok<*>) return fail("couldn't read the bans in force: ${(value as? Outcome.Err<*>)?.error ?: error}")
                    val backend = fresh!!
                    @Suppress("UNCHECKED_CAST")
                    for (entry in value.value as List<Entry>) backend.active.add(entry)
                    opened(backend)
                }
            }
        }

        private fun fail(why: String) {
            feed?.close()
            db.close()
            failedToOpen("Couldn't open ${db.description}: $why", config, ticket, db)
        }

        private fun opened(fresh: Backend) {
            if (!current(ticket)) {
                fresh.feed?.close()
                db.close()
                return
            }
            fresh.feed?.let {
                network.subscribe(fresh)
                it.start()
            }
            val old = backend
            backend = fresh
            failure = null
            rekeySessions(fresh)
            if (old != null) {
                old.feed?.close()
                old.database.close()
            }
            if (lastProblem != null) {
                log.info("Opened ${db.description} after all.")
                lastProblem = null
            }
            log.info("Loaded ${fresh.active.size} bans and mutes in force from ${db.description}, schema 1" +
                if (fresh.shared) ", sharing changes with the network." else ".")
            XBans.register(this@XBansPlugin)
            ready.resolve(true)
            // Players who are online already (a reload, a database swapped) and banned on the new one.
            platform.scheduler.run(Where.Global, action = this)
        }

        override fun invoke(task: Task) {
            val fresh = fresh ?: return
            val now = now()
            for (player in server.online()) {
                val ban = fresh.active.ban(player, sessions.ip(player), loginServer(player), now) ?: continue
                sessions.quietQuit(player)
                server.kick(player, punisher.screen(player, ban), true)
            }
        }
    }

    /** How IPs are keyed, on the writer: a class, not a lambda. */
    private class IpStep(private val plugin: XBansPlugin, private val db: Database, private val hash: Boolean) :
        (co.xenastudios.core.common.db.SqlSession) -> Outcome<Outcome<co.xenastudios.xbans.ip.IpKeys, String>, DbError> {
        override fun invoke(session: co.xenastudios.core.common.db.SqlSession): Outcome<Outcome<co.xenastudios.xbans.ip.IpKeys, String>, DbError> =
            Outcome.Ok(IpSetup(plugin.platform.storage, plugin.log, db.sql).keys(session, hash))
    }

    /** What's in force, on a reader: a class, not a lambda. */
    private class InForce(private val backend: Backend, private val now: Long) :
        (co.xenastudios.core.common.db.SqlSession) -> Outcome<List<Entry>, DbError> {
        override fun invoke(session: co.xenastudios.core.common.db.SqlSession): Outcome<List<Entry>, DbError> = backend.inForce(session, now)
    }

    private fun failedToOpen(problem: String, config: Config, ticket: Int, db: Database?) = Calls.failedToOpen(this, problem, config, ticket)

    // Every online player's IP keyed again, as a newly opened database keys them.
    private fun rekeySessions(backend: Backend) {
        val ips = config[XBansConfig.ips.enabled]
        val online = HashMap<PlayerRef, String>()
        for (player in server.online()) {
            val key = if (ips) backend.keys.keyOf(server.address(player)) else null
            if (key != null) online[player] = key
        }
        sessions.rekey(online)
        madeActions?.cursors?.clear()
    }

    /** Turns the plugin off, as the server stops: the API, the commands, the feed, then the database, once what's queued is saved. */
    public fun stop(): Pending<Unit> = Calls.stop(this)

    @Suppress("UNCHECKED_CAST")
    private fun reloader(): ModuleReloader =
        ModuleReloader(modules, reloadFiles = Reloading(0) as () -> List<String>, wanted = Reloading(1) as () -> Map<String, () -> Module>)

    // /xbans reload: the files again, then everything built from them; and the modules then.
    private inner class Reloading(private val kind: Int) : () -> Any {
        override fun invoke(): Any = if (kind == 0) apply(files.load()) else emptyMap<String, () -> Module>()
    }

    /**
     * What the adapter does just before the first event could be heard: Bukkit's bridges the API's
     * events to Bukkit events there, so their classes don't load as the server starts.
     */
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

    /** Uses what [Files] read: the commands rebuilt, and another database opened if the config names one. On the global thread. */
    public fun apply(loaded: Loaded): List<String> {
        config = loaded.config
        texts.translations = loaded.translations
        texts.look(config[XBansConfig.lists.dateFormat], config[XBansConfig.screens.appealLink])
        dispatcher = CommandDispatcher(commands.tree(config), platform.clock, log)
        permissions = commands.permissions
        permissionRegistrar?.sync(permissions.permissions + admin.permissions.permissions)
        val warnings = ArrayList<String>()
        warnings += registrar?.sync(dispatcher).orEmpty() + commands.warnings
        warnings.forEach(log::warn)
        problems = loaded.problems + warnings
        updates?.apply(updateSettings)
        if (running && backend != null && opener.describe(config) != wanted) open()
        return problems
    }

    internal fun now(): Long = platform.clock.now().toEpochMilliseconds()

    /** This server's name, for scopes. */
    internal val serverName: String get() = config[XBansConfig.scopes.serverName]

    /** Whether xBans tells players here of punishments: always on a server; on a proxy only when its servers don't share the database. */
    internal val tellsPlayers: Boolean get() = !server.proxy || backend?.shared != true

    /** Whether [entry] reaches [player] where they are: this server's scope, or on the proxy the server they're on. */
    internal fun appliesHere(entry: Entry, player: PlayerRef): Boolean =
        if (!server.proxy) entry.appliesOn(serverName) else entry.punishment.isGlobal || seats[player]?.let(entry::appliesOn) == true

    // The scope a ban is checked against where [player] is: this server's, or on the proxy the one they're on.
    internal fun loginServer(player: PlayerRef): String? = if (server.proxy) seats[player] else serverName

    // ---- players ----

    /**
     * The ban that keeps [player], connecting from [address], out: on a server, from it; on the
     * proxy with [toServer] `null`, from the network (bans on every server), or with a server's name,
     * from that one. From memory: a couple of map lookups. `null` if they may join; on a server
     * where the bans aren't read yet, see [ready].
     */
    public fun banOnJoin(player: PlayerRef, address: String?, toServer: String? = if (server.proxy) null else serverName): Entry? {
        val backend = backend ?: return null
        val ip = if (config[XBansConfig.ips.enabled]) backend.keys.keyOf(address) else null
        return backend.active.ban(player, ip, toServer, now())
    }

    /**
     * What [player] is shown when they're turned away: [ban]'s screen, or the "still starting" one
     * while the bans aren't read. Fires [LoginDeniedEvent] and tells staff with
     * `xbans.notify.banned-join`. Any thread.
     */
    public fun denied(player: PlayerRef, name: String, ban: Entry?): Component = Calls.denied(this, player, name, ban)

    /**
     * [player] joined (or, on the proxy, logged in): their IP keyed and remembered, their join
     * recorded with their weight and exemption, alts reported. Returns the ban that should kick
     * them now (one made while they connected), or `null`. On their thread.
     */
    public fun joined(player: PlayerRef, name: String, address: String?): Entry? = Calls.joined(this, player, name, address)

    @Volatile
    internal var warnedShared = false

    /** [player] left; `true` if their quit line is to be left out (they were kicked for a ban). */
    public fun quit(player: PlayerRef): Boolean {
        sessions.left(player)
        seats.remove(player)
        return sessions.takeQuiet(player)
    }

    /** The proxy only: [player] is now on [toServer]. */
    public fun switched(player: PlayerRef, toServer: String) {
        seats[player] = toServer
    }

    /** The mute on online [player] here now, by account or IP, or `null`. Any thread, from memory. */
    public fun muteOf(player: PlayerRef): Entry? {
        val backend = backend ?: return null
        return backend.active.mute(player, sessions.ip(player), if (server.proxy) seats[player] else serverName, now())
    }

    /** What a muted player is told when they try to chat: "You're muted", for [mute]. */
    public fun mutedMessage(player: PlayerRef, mute: Entry): Component? = Calls.mutedMessage(this, player, mute)

    /**
     * Whether a muted [player]'s command [line] (`/msg Bob hi`) is one they can't use, by what it is,
     * not how it was typed: an alias (`/emsg`) or a namespace (`/essentials:msg`) of a blocked
     * command is blocked too. Only a muted player's commands are looked up. On the command's thread.
     */
    public fun blocksCommand(line: String): Boolean = Calls.blocksCommand(this, line)

    @Volatile
    internal var blockedFrom: List<String>? = null

    @Volatile
    internal var blockedNames: Set<String> = emptySet()

    // ---- the proxy layer ----

    // A server tells the proxy of a ban or kick it just made, so the proxy acts before the feed's
    // next check: the player is taken off the network at once. Behind a proxy only.
    internal fun tellProxy(entry: Entry) = Calls.tellProxy(this, entry)

    private fun listen(channel: ProxyChannel) = Calls.listen(this, channel)

    /** Whether [id] is heard for the first time; the proxy then skips the feed's copy. */
    internal fun markHeard(id: Long): Boolean = locked(heard) {
        if (!heard.add(id)) return@locked false
        if (heard.size > 500) heard.remove(heard.first())
        true
    }

    internal fun wasHeard(id: Long): Boolean = locked(heard) { id in heard }

    // ---- for the commands ----

    @Volatile
    private var madeActions: Actions? = null

    /** What the commands do, made the first time one runs. */
    internal fun actions(): Actions = madeActions ?: locked(this) { madeActions ?: Actions(this).also { madeActions = it } }

    // ---- the API ----

    // Each in Calls, loaded with the first plugin to call one, not at startup.
    override fun activeBan(player: PlayerRef): Punishment? = Calls.activeOf(this, player, false)

    override fun activeMute(player: PlayerRef): Punishment? = Calls.activeOf(this, player, true)

    override fun punish(request: PunishRequest): Pending<PunishResult> = Calls.punish(this, request)

    override fun pardon(player: PlayerRef, type: PunishmentType, staff: PlayerRef?, staffName: String, reason: String): Pending<PunishResult> =
        Calls.pardon(this, player, type, staff, staffName, reason)

    /** What a refusal says, in English, for the API. */
    internal fun refusal(refused: Done.Refused): String = Calls.refusal(this, refused)

    override fun history(player: PlayerRef, page: Int, perPage: Int): Pending<HistoryPage> = actions().historyPage(Backend.Filter.target(player, null), page, perPage)

    override fun staffHistory(staff: PlayerRef?, page: Int, perPage: Int): Pending<HistoryPage> = actions().historyPage(Backend.Filter.staff(staff, null), page, perPage)

    override fun alts(player: PlayerRef): Pending<List<Alt>> = Calls.alts(this, player)

    override fun isStaff(player: PlayerRef): Boolean = Calls.isStaff(this, player)

    override fun onlineStaff(): List<PlayerRef> = server.online().filter(::isStaff)

    override fun notifyStaff(message: String, silent: Boolean) {
        actions().notifyStaff(message, silent)
    }

    /** Who [source] is, as punishments record them: their uuid and name, or the console's. */
    internal fun staffOf(source: CommandSource): Pair<PlayerRef?, String> {
        val player = source.player ?: return null to "Console"
        return player to (server.name(player) ?: source.name)
    }

    public companion object {
        public const val PLUGIN_ID: String = "xbans"
        public const val NAME: String = "xBans"

        /** xBans' version. The adapters' builds check theirs is the same. */
        public const val VERSION: String = "2.0.0"

        public const val NOTIFY_BANNED_JOIN: String = "xbans.notify.banned-join"
        public const val NOTIFY_ALTS: String = "xbans.notify.alts"

        /** How long a login waits for the bans to load just after a start. */
        public const val WAIT_SECONDS: Long = 10

        /** Players online on one IP at which the console is told a proxy may not be forwarding addresses. */
        internal const val SHARED_IP_WARNING = 8

        internal const val TOPIC_ENFORCE = "enforce"

        internal val RETRY = 15.seconds
    }
}

/** `/xbans debug`'s report or `forget`'s player data, made when the command first asks. */
private class AdminPart<T>(private val plugin: XBansPlugin, private val debug: Boolean) : () -> T {
    @Suppress("UNCHECKED_CAST")
    override fun invoke(): T = (if (debug) AdminParts.debug(plugin, plugin.about, plugin.paste) else AdminParts.playerData(plugin, plugin.cache)) as T
}
