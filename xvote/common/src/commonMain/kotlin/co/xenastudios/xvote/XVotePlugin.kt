package co.xenastudios.xvote

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
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
import co.xenastudios.core.common.command.ModuleReloader
import co.xenastudios.core.common.command.OnlinePlayers
import co.xenastudios.core.common.concurrentMap
import co.xenastudios.core.common.config.Config
import co.xenastudios.core.common.db.ChangeFeed
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
import co.xenastudios.core.common.proxy.ProxyChannel
import co.xenastudios.core.common.scheduler.Task
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.update.UpdateChecker
import co.xenastudios.core.common.update.UpdateSettings
import co.xenastudios.xvote.api.PartyProgress
import co.xenastudios.xvote.api.Period
import co.xenastudios.xvote.api.RewardType
import co.xenastudios.xvote.api.TopVoter
import co.xenastudios.xvote.api.VoteResult
import co.xenastudios.xvote.api.VoteSource
import co.xenastudios.xvote.api.VoterStats
import co.xenastudios.xvote.api.XVote
import co.xenastudios.xvote.api.XVoteApi
import co.xenastudios.xvote.config.XVoteConfig
import co.xenastudios.xvote.store.Reads
import co.xenastudios.xvote.store.Tables
import co.xenastudios.xvote.vote.IncomingVote
import co.xenastudios.xvote.vote.Online
import co.xenastudios.xvote.vote.Periods
import co.xenastudios.xvote.vote.Voters
import co.xenastudios.xvote.vote.Zones
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** What the plugin's files said when they were last read: the config, the lang files, and the problems found. */
public class Loaded(
    public val config: Config,
    public val translations: Translations,
    /** Each problem, in English, already logged. */
    public val problems: List<String> = emptyList(),
)

/** xVote's files: on the JVM, `JvmFiles`. [load] reads `config.yml` and `lang/`. */
public interface Files {
    public fun load(): Loaded

    /** Sets the receiver's port, address and v1 setting in `config.yml` (NuVotifier's, as it's taken over): `null`, or why it couldn't. */
    public fun useReceiver(port: Int, host: String?, v1: Boolean): String? = null
}

/** Opens the database a config names (on the JVM, `JvmDatabase`), off the server's threads, or says why it couldn't, in English. */
public fun interface Opener {
    public fun open(config: Config): Outcome<Database, String>
}

/**
 * xVote, the same on every server and on the proxies: each adapter makes one with its [Platform]
 * and its [VoteServer], reads the files and calls [start] as the plugin enables; the database is
 * opened, migrated and every voter read off the server's threads ([open]), then the receiver
 * starts. Everything the config says applies on `/xvote reload`, except where the votes are kept
 * (`storage` and `network`), which applies on restart, as in 1.x.
 */
public class XVotePlugin(
    public val platform: Platform,
    /** What the adapter does to players. */
    public val server: VoteServer,
    private val files: Files,
    languages: LanguageSource,
    /** Who's online, by name, as each command source can see them. */
    internal val players: OnlinePlayers,
    /** The family's player cache: names of players who aren't online. */
    internal val cache: PlayerCache? = null,
    private val opener: Opener,
    internal val registrar: CommandRegistrar? = null,
    internal val permissionRegistrar: PermissionRegistrar? = null,
    /** What `/xvote debug`'s report says about the server. */
    internal val about: () -> Map<String, String> = { emptyMap() },
    /** Where `/xvote debug` uploads its report; `null` to write a file. */
    internal val paste: PasteService? = null,
    /** The Votifier receiver, on the JVM; `null` where there's none (tests, Pumpkin). */
    public val receiver: VoteReceiver? = null,
    /** What `/xvote import` reads; `null` without importers. */
    internal val imports: VoteImports? = null,
    /** The proxy layer's channel, `xena:xvote`, behind a proxy and on it; `null` without one. */
    internal val channel: ProxyChannel? = null,
    /** Money for rewards; `null` for none. */
    public var economy: Economy? = null,
    /** Time zones, for `counting.time-zone`. */
    private val zones: Zones = Zones.UTC_ONLY,
    override val version: String = VERSION,
) : XVoteApi {

    /** Where the plugin's console lines go; the last 50 warnings and errors are kept for `/xvote debug`. */
    public val log: RecentErrors = RecentErrors(platform.log, platform.clock)

    /** The messages, in each reader's language. */
    public val texts: Texts = Texts(languages, server)

    /** The API's events. On Bukkit the adapter bridges them to Bukkit events. */
    public val bus: SimpleEventBus = SimpleEventBus(log)

    override val events: EventBus get() = bus

    public val modules: ModuleSet = ModuleSet(platform)

    @Volatile
    public var config: Config = XVoteConfig.defaults
        private set

    @Volatile
    internal var problems: List<String> = emptyList()
        private set

    @Volatile
    public var rewards: Rewards = Rewards.NONE
        private set

    public val voters: Voters = Voters(Periods(zones.server, XVoteConfig.WeekDay.MONDAY))

    /** Who's online here (on a proxy, on the network). */
    public val online: Online = Online()

    /** What's open, or `null` before the database is ready. */
    @Volatile
    public var backend: Backend? = null
        private set

    /** The vote party's count, as last read or stored. */
    @Volatile
    internal var party: Reads.PartyState = Reads.PartyState(0, 0)

    @Volatile
    internal var madeVotes: Votes? = null
        private set

    /**
     * Where votes go, made with the first vote, join or news from another server, not as the server
     * starts (decisions/2026-11-25-a-lighter-core.md); `null` before the database is open.
     */
    internal fun votes(): Votes? {
        madeVotes?.let { return it }
        val backend = backend ?: return null
        return locked(this) { madeVotes ?: Votes(this, backend).also { madeVotes = it } }
    }

    /** Why there's no database, if opening it failed. */
    @Volatile
    public var failure: String? = null
        internal set

    /** Settled `true` once the database is open and every voter is in memory, `false` if it couldn't be opened. */
    public val ready: Pending<Boolean> = newPending()

    @Volatile
    public var running: Boolean = false
        internal set

    /** Reward types other plugins added, by id in lower case. */
    internal val rewardTypes = concurrentMap<String, Payout.Registered>()

    /** What the commands do, made the first time one runs. */
    @Volatile
    internal var madeActions: Actions? = null

    @Volatile
    private var madePayout: Payout? = null
    internal fun payout(): Payout = madePayout ?: locked(this) { madePayout ?: Payout(this).also { madePayout = it } }

    /** `/xvote reload`, `debug` and `forget`, or on a proxy `/xvoteproxy`'s. */
    @Suppress("UNCHECKED_CAST")
    public val admin: AdminCommands = AdminCommands(
        PLUGIN_ID, NAME, platform, reloader(),
        // Neither part loads until its command runs (AdminCommands).
        debug = Reloading(2) as () -> co.xenastudios.core.common.admin.Debug,
        playerData = Reloading(3) as () -> co.xenastudios.core.common.admin.PlayerDataRegistry,
        text = texts.admin,
    )

    private val commands = Commands(this)

    /** What runs the commands; built again on reload, from the new config. */
    @Volatile
    public var dispatcher: CommandDispatcher = CommandDispatcher(commands.tree(), platform.clock, log)
        private set

    public val permissions: XVotePermissions get() = commands.permissions

    /** The platform's update checker, which the adapter starts; every reload gives it the config's settings again. */
    @Volatile
    public var updates: UpdateChecker? = null

    public val updateSettings: UpdateSettings get() = XVoteConfig.updates.settings(config)

    // ---- starting and stopping ----

    /** Turns the plugin on with what [Files] read: permissions and commands. The database comes with [open]. */
    public fun start(loaded: Loaded) {
        running = true
        apply(loaded)
        channel?.let { Network.listen(this, it) }
    }

    /** Opens the database the config names, off the server's threads, then reads every voter and starts the receiver. Any thread. */
    public fun open() {
        platform.scheduler.run(Where.Async, action = Opening(this, config))
    }

    /** Opening the database: its tables, the change feed (on a network), every voter. One class for its steps rather than a lambda each. */
    private class Opening(private val plugin: XVotePlugin, private val config: Config) : (Task) -> Unit, (Any?, Throwable?) -> Unit {
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
                    // The feed first, so it starts from before the load: a vote counted while the
                    // voters load is read again from the feed's news, which changes nothing.
                    if (!config[XVoteConfig.network.enabled]) return invoke(Outcome.Ok(null), null)
                    val settings = XVoteConfig.network.read(config)
                    when {
                        plugin.networkOnAnyDatabase -> ChangeFeed.openOnAny(db, settings, plugin.platform.scheduler, plugin.platform.clock, plugin.log, kotlin.uuid.Uuid.random().toHexString().substring(0, 16)).onSettled(this)
                        else -> ChangeFeed.open(db, settings, plugin.platform.scheduler, plugin.platform.clock, plugin.log).onSettled(this)
                    }
                }
                1 -> {
                    if (value !is Outcome.Ok<*>) return fail("Couldn't start network mode: ${(value as? Outcome.Err<*>)?.error ?: error}", true)
                    feed = (value.value as? ChangeFeed)?.takeIf { it.isOn }
                    plugin.loadVoters(db).onSettled(this)
                }
                else -> {
                    if (value !is Outcome.Ok<*>) return fail("Couldn't read the voters from ${db.description}: ${(value as? Outcome.Err<*>)?.error ?: error}", true)
                    if (!plugin.running) {
                        feed?.close()
                        db.close()
                        return
                    }
                    feed?.let {
                        for (kind in 0..3) News(plugin, kind).subscribe(it)
                        it.start()
                    }
                    plugin.backend = Backend(db, feed)
                    plugin.failure = null
                    plugin.log.info("Loaded ${plugin.voters.size} voters from ${db.description}" + if (feed != null) ", sharing votes with the network." else ".")
                    if (plugin.server.proxy && feed == null) {
                        plugin.log.warn("xVote on the proxy stores what it receives in ${db.description}, which the servers don't share, so no server pays those votes. " +
                            "Give the proxy and every server the same MySQL or MariaDB database in storage, with network.enabled: true.")
                    }
                    XVote.register(plugin)
                    plugin.ready.resolve(true)
                    // Players online already (a reload of the server): treat them as joining now.
                    for (player in plugin.server.online()) plugin.server.name(player)?.let { plugin.votes()?.joined(player, it) }
                    plugin.applyReceiver()
                }
            }
        }

        private fun fail(why: String, opened: Boolean) {
            feed?.close()
            if (opened) db?.close()
            plugin.failure = why
            plugin.log.error(why)
            plugin.log.error("xVote can't count votes without its database. Fix storage in config.yml and restart.")
            plugin.ready.resolve(false)
        }
    }

    /** The change feed's news, each topic handed to the votes (made then), and its resync: one class, told apart by [kind]. */
    private class News(private val plugin: XVotePlugin, private val kind: Int) : (co.xenastudios.core.common.db.Change) -> Unit, () -> Unit {
        fun subscribe(feed: ChangeFeed) {
            when (kind) {
                0 -> feed.subscribe("vote", this)
                1 -> feed.subscribe("player", this)
                2 -> feed.subscribe("party", this)
                else -> feed.onResync(this)
            }
        }

        override fun invoke(change: co.xenastudios.core.common.db.Change) {
            val votes = plugin.votes() ?: return
            val payload = change.payload.trim()
            when (kind) {
                0 -> payload.toLongOrNull()?.let(votes::heard)
                1 -> PlayerRef.parseOrNull(payload)?.let(votes::refresh)
                else -> votes.partyNews(payload)
            }
        }

        override fun invoke() {
            plugin.votes()?.resync()
        }
    }

    /** Reads every voter and the party's count into memory, on a reader of [db]. */
    internal fun loadVoters(db: Database? = backend?.database): Pending<Outcome<Int, co.xenastudios.core.common.db.DbError>> {
        val done = newPending<Outcome<Int, co.xenastudios.core.common.db.DbError>>()
        val database = db ?: return done.also { it.resolve(Outcome.Err(co.xenastudios.core.common.db.DbError(co.xenastudios.core.common.db.DbError.Kind.CLOSED, "the database isn't open"))) }
        val load = VoterLoad(this, done)
        database.read(ReadAll(database, voters.periods, now(), maxOf(Votes.SITE_WINDOW_MS, rewards.longestCooldown))).onSettled(load)
        return done
    }

    /** One read of every voter, and what's done with it: a class, not lambdas. */
    private class VoterLoad(private val plugin: XVotePlugin, private val done: Pending<Outcome<Int, co.xenastudios.core.common.db.DbError>>) : (Any?, Throwable?) -> Unit {
        @Suppress("UNCHECKED_CAST")
        override fun invoke(value: Any?, error: Throwable?) {
            val read = value as? Outcome<Pair<Map<PlayerRef, co.xenastudios.xvote.vote.Voter>, Reads.PartyState>, co.xenastudios.core.common.db.DbError>
            when (read) {
                is Outcome.Ok -> {
                    plugin.voters.putAll(read.value.first.values)
                    plugin.party = read.value.second
                    done.resolve(Outcome.Ok(read.value.first.size))
                }
                is Outcome.Err -> done.resolve(read)
                null -> done.resolve(Outcome.Err(co.xenastudios.core.common.db.DbError(co.xenastudios.core.common.db.DbError.Kind.UNAVAILABLE, error.toString())))
            }
        }
    }

    private class ReadAll(private val db: Database, private val periods: Periods, private val now: Long, private val window: Long) :
        (co.xenastudios.core.common.db.SqlSession) -> Outcome<Pair<Map<PlayerRef, co.xenastudios.xvote.vote.Voter>, Reads.PartyState>, co.xenastudios.core.common.db.DbError> {
        override fun invoke(session: co.xenastudios.core.common.db.SqlSession): Outcome<Pair<Map<PlayerRef, co.xenastudios.xvote.vote.Voter>, Reads.PartyState>, co.xenastudios.core.common.db.DbError> =
            try {
                val tx = co.xenastudios.xvote.store.Tx(session, db.sql)
                Outcome.Ok(Reads.loadAll(tx, periods, now, window) to Reads.party(tx, false))
            } catch (e: co.xenastudios.xvote.store.TxFailed) {
                Outcome.Err(e.error)
            }
    }

    /** Tests only: network mode on an SQLite file two plugins share, standing in for a network's MySQL. */
    internal var networkOnAnyDatabase: Boolean = false

    /** Turns the plugin off, as the server stops: the API, the receiver, the feed, then the database, once every unpaid vote taken is given back. */
    public fun stop(): Pending<Unit> {
        running = false
        XVote.register(null)
        updates?.close()
        updates = null
        modules.clear()
        registrar?.clear()
        permissionRegistrar?.sync(emptyList())
        channel?.close()
        return Calls.stop(this)
    }

    @Suppress("UNCHECKED_CAST")
    private fun reloader(): ModuleReloader =
        ModuleReloader(modules, reloadFiles = Reloading(0) as () -> List<String>, wanted = Reloading(1) as () -> Map<String, () -> Module>)

    // /xvote reload: the files again, then everything built from them; and the modules then. And
    // /xvote debug's report and forget's player data, made when the command first asks. One class,
    // told apart by kind, not one each.
    private inner class Reloading(private val kind: Int) : () -> Any {
        override fun invoke(): Any = when (kind) {
            0 -> apply(files.load())
            1 -> wantedModules()
            2 -> AdminParts.debug(this@XVotePlugin)
            else -> AdminParts.playerData(this@XVotePlugin)
        }
    }

    /** The modules the config turns on: the repeating reminder, only while `reminders.every-minutes` is above 0. */
    private fun wantedModules(): Map<String, () -> Module> {
        val minutes = config[XVoteConfig.reminders.everyMinutes]
        if (minutes <= 0 || server.proxy) return emptyMap()
        return mapOf("reminders:$minutes" to RemindersFactory(this, minutes))
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

    /** Uses what [Files] read: the rewards, periods and commands rebuilt, the receiver moved if it changed. On the global thread. */
    public fun apply(loaded: Loaded): List<String> {
        config = loaded.config
        texts.translations = loaded.translations
        val warnings = ArrayList<String>()
        rewards = Rewards.of(config, server, Later(into = warnings), Later(log = log))
        // The items are read on the next tick, not as the plugin enables (Rewards.RewardEntry).
        if (!server.proxy) platform.scheduler.run(Where.Global, delay = co.xenastudios.core.common.scheduler.TICK, action = Later(rewards = rewards))
        val zoneText = config[XVoteConfig.counting.timeZone].trim()
        val zone = if (zoneText.isEmpty() || zoneText.equals("server", ignoreCase = true)) zones.server else zones.of(zoneText) ?: zones.server.also {
            warnings += "config.yml: counting.time-zone (\"$zoneText\") isn't a time zone, such as Europe/London; using the server's."
        }
        val periods = Periods(zone, config[XVoteConfig.counting.weekStarts])
        if (!periods.sameAs(voters.periods) || periods.weekStart != voters.periods.weekStart) voters.periods(periods)
        dispatcher = CommandDispatcher(commands.tree(), platform.clock, log)
        permissionRegistrar?.sync(permissions.permissions + admin.permissions.permissions)
        warnings += registrar?.sync(dispatcher).orEmpty()
        warnings.forEach(log::warn)
        problems = loaded.problems + warnings
        updates?.apply(updateSettings)
        madePayout?.reloaded()
        if (backend != null) {
            madeVotes?.forgetUnknown(now())
            applyReceiver()
        }
        return problems
    }

    /** Reads the files again and applies them, as `/xvote reload` does without its reply. On the global thread. */
    internal fun reloadFiles() {
        apply(files.load())
        modules.sync(wantedModules())
    }

    /** The receiver made to match the config, off the server's threads: one change at a time, in the receiver. */
    internal fun applyReceiver() {
        if (receiver == null) return
        val r = XVoteConfig.receiver
        val c = config
        val wanted = if (c[r.enabled]) {
            ReceiverSettings(c[r.host].trim(), c[r.port], c[r.v1], c[r.maxConnections], c[r.perIpConnections], c[r.timeoutSeconds], c[r.perIpPerMinute])
        } else {
            null
        }
        platform.scheduler.run(Where.Async, action = Later(plugin = this, wanted = wanted))
    }

    internal fun now(): Long = platform.clock.now().toEpochMilliseconds()

    // ---- players ----

    /** [player] joined (on a proxy, logged in): their votes held by name become theirs, and everything unpaid is paid; then a reminder. On their thread. */
    public fun joined(player: PlayerRef, name: String) {
        Calls.joined(this, player, name)
    }

    /** [player] left. */
    public fun quit(player: PlayerRef, name: String) {
        madeVotes?.quit(player, name) ?: online.remove(player, name)
    }

    /** The proxy only: [player] is now on [toServer]. */
    public fun switched(player: PlayerRef, toServer: String) {
        online.seat(player, toServer)
    }

    /** The sites [player] can vote on now. */
    internal fun available(player: PlayerRef?, now: Long): List<Rewards.SiteEntry> = Calls.available(this, player, now)

    /** Reminds [player] if they can vote. On their thread. */
    internal fun remind(player: PlayerRef) {
        Calls.remind(this, player)
    }

    /** Items that didn't fit: kept in the database for `/vote claim`. */
    internal fun hold(player: PlayerRef, items: List<ByteArray>, count: Int) {
        Calls.hold(this, player, items, count)
    }

    // ---- the API ----

    // Each in Calls, loaded with the first plugin to call one, not at startup.
    override fun stats(player: PlayerRef): VoterStats = Calls.stats(this, player)

    override fun top(period: Period, limit: Int): List<TopVoter> = Calls.top(this, period, limit)

    override fun party(): PartyProgress = Calls.party(this)

    override fun registerRewardType(owner: String, id: String, type: RewardType) {
        Calls.register(this, owner, id, type)
    }

    override fun unregisterRewardType(id: String) {
        rewardTypes.remove(id.lowercase())
    }

    override fun submitVote(site: String, username: String, address: String): Pending<VoteResult> = Calls.submit(this, site, username, address)

    /** Where every vote arriving goes: the receiver, `/vote test`, the API. [VoteResult.FAILED] before the database is open. */
    public fun accept(vote: IncomingVote): Pending<VoteResult> = votes()?.accept(vote) ?: pendingOf(VoteResult.FAILED).also {
        log.warn("A vote from ${vote.site} for ${vote.username} arrived before the database was open, so it's dropped.")
    }

    public companion object {
        public const val PLUGIN_ID: String = "xvote"
        public const val NAME: String = "xVote"

        /** xVote's version. The adapters' builds check theirs is the same. */
        public const val VERSION: String = "2.0.0"
    }
}

/**
 * What [XVotePlugin.apply] hands on, as one class rather than a lambda each: a warning, into [into]
 * as the config loads or to [log] later; reading [rewards]' items a tick after a load; or moving
 * [plugin]'s receiver to [wanted], off the server's threads. Taking `Any?`, it's each of their types.
 */
private class Later(
    private val into: MutableList<String>? = null,
    private val log: co.xenastudios.core.common.platform.Log? = null,
    private val rewards: Rewards? = null,
    private val plugin: XVotePlugin? = null,
    private val wanted: ReceiverSettings? = null,
) : (Any?) -> Unit {
    override fun invoke(value: Any?) {
        when {
            plugin != null -> if (plugin.running) plugin.receiver?.apply(wanted)
            rewards != null -> rewards.check()
            into != null -> into += "config.yml: $value"
            else -> log?.warn(value.toString())
        }
    }
}

/** Reminds everyone online who can vote, every [minutes]: xVote's only repeating task, and only while `reminders.every-minutes` is above 0. */
internal class RemindersFactory(private val plugin: XVotePlugin, private val minutes: Int) : () -> Module {
    override fun invoke(): Module = Reminders(plugin, minutes)
}

internal class Reminders(private val plugin: XVotePlugin, private val minutes: Int) : Module, (Task) -> Unit {
    private var task: Task? = null

    override fun enable(context: ModuleContext) {
        val period = minutes.minutes
        task = plugin.platform.scheduler.run(Where.Global, delay = period, period = period, action = this)
    }

    override fun invoke(task: Task) {
        for (player in plugin.server.online()) plugin.platform.scheduler.run(Where.On(player.uuid)) { _ -> plugin.remind(player) }
    }

    override fun disable() {
        task?.cancel()
        task = null
    }
}
