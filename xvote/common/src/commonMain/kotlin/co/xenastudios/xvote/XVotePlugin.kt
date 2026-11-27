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
    internal var votes: Votes? = null
        private set

    public val backend: Backend? get() = votes?.backend

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
    public val admin: AdminCommands = AdminCommands(
        PLUGIN_ID, NAME, platform, reloader(),
        // Neither part loads until its command runs (AdminCommands).
        debug = AdminPart(this, true),
        playerData = AdminPart(this, false),
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
        private var votes: Votes? = null

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
                    val settings = XVoteConfig.network.read(config)
                    when {
                        !settings.enabled -> invoke(Outcome.Ok(null), null)
                        plugin.networkOnAnyDatabase -> ChangeFeed.openOnAny(db, settings, plugin.platform.scheduler, plugin.platform.clock, plugin.log, kotlin.uuid.Uuid.random().toHexString().substring(0, 16)).onSettled(this)
                        else -> ChangeFeed.open(db, settings, plugin.platform.scheduler, plugin.platform.clock, plugin.log).onSettled(this)
                    }
                }
                1 -> {
                    if (value !is Outcome.Ok<*>) return fail("Couldn't start network mode: ${(value as? Outcome.Err<*>)?.error ?: error}", true)
                    feed = (value.value as? ChangeFeed)?.takeIf { it.isOn }
                    val made = Votes(plugin, Backend(db, feed))
                    votes = made
                    made.load(plugin.now()).onSettled(this)
                }
                else -> {
                    val made = votes!!
                    if (value !is Outcome.Ok<*>) return fail("Couldn't read the voters from ${db.description}: ${(value as? Outcome.Err<*>)?.error ?: error}", true)
                    if (!plugin.running) {
                        feed?.close()
                        db.close()
                        return
                    }
                    feed?.let {
                        made.subscribe(it)
                        it.start()
                    }
                    plugin.votes = made
                    plugin.failure = null
                    plugin.log.info("Loaded ${plugin.voters.size} voters from ${db.description}" + if (feed != null) ", sharing votes with the network." else ".")
                    if (plugin.server.proxy && feed == null) {
                        plugin.log.warn("xVote on the proxy stores what it receives in ${db.description}, which the servers don't share, so no server pays those votes. " +
                            "Give the proxy and every server the same MySQL or MariaDB database in storage, with network.enabled: true.")
                    }
                    XVote.register(plugin)
                    plugin.ready.resolve(true)
                    // Players online already (a reload of the server): treat them as joining now.
                    for (player in plugin.server.online()) plugin.server.name(player)?.let { made.joined(player, it) }
                    made.forgetUnknown(plugin.now())
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
        val done = newPending<Unit>()
        val receiver = receiver
        val votes = votes
        // Off the server's threads: closing the receiver waits for its thread.
        platform.scheduler.run(Where.Async) { _ ->
            receiver?.close()
            if (votes == null) {
                done.resolve(Unit)
                return@run
            }
            votes.backend.feed?.close()
            votes.shutdown().onSettled { _, _ -> votes.backend.database.close().onSettled { _, _ -> done.resolve(Unit) } }
        }
        return done
    }

    @Suppress("UNCHECKED_CAST")
    private fun reloader(): ModuleReloader =
        ModuleReloader(modules, reloadFiles = Reloading(0) as () -> List<String>, wanted = Reloading(1) as () -> Map<String, () -> Module>)

    // /xvote reload: the files again, then everything built from them; and the modules then.
    private inner class Reloading(private val kind: Int) : () -> Any {
        override fun invoke(): Any = if (kind == 0) apply(files.load()) else wantedModules()
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
        rewards = Rewards.of(config, server) { warnings += "config.yml: $it" }
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
        if (votes != null) {
            votes?.forgetUnknown(now())
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
        val receiver = receiver ?: return
        val r = XVoteConfig.receiver
        val c = config
        val wanted = if (c[r.enabled]) {
            ReceiverSettings(c[r.host].trim(), c[r.port], c[r.v1], c[r.maxConnections], c[r.perIpConnections], c[r.timeoutSeconds], c[r.perIpPerMinute])
        } else {
            null
        }
        platform.scheduler.run(Where.Async) { _ -> if (running) receiver.apply(wanted) }
    }

    internal fun now(): Long = platform.clock.now().toEpochMilliseconds()

    // ---- players ----

    /** [player] joined (on a proxy, logged in): their votes held by name become theirs, and everything unpaid is paid; then a reminder. On their thread. */
    public fun joined(player: PlayerRef, name: String) {
        val votes = votes
        if (votes == null) {
            // Before the voters are read: the start treats everyone online as joining.
            online.add(player, name)
            return
        }
        votes.joined(player, name)
        if (!server.proxy && config[XVoteConfig.reminders.onJoin]) {
            val delay = config[XVoteConfig.reminders.joinDelaySeconds].coerceAtLeast(0)
            platform.scheduler.run(Where.On(player.uuid), delay = delay.seconds.coerceAtLeast(co.xenastudios.core.common.scheduler.TICK)) { _ -> remind(player) }
        }
    }

    /** [player] left. */
    public fun quit(player: PlayerRef, name: String) {
        votes?.quit(player, name) ?: online.remove(player, name)
    }

    /** The proxy only: [player] is now on [toServer]. */
    public fun switched(player: PlayerRef, toServer: String) {
        online.seat(player, toServer)
    }

    /** The sites [player] can vote on now. */
    internal fun available(player: PlayerRef?, now: Long): List<Rewards.SiteEntry> {
        val voter = player?.let { voters[it] }
        return rewards.sites.filter { voter == null || voter.lastOn(it.service) + it.cooldownMs <= now }
    }

    /** Reminds [player] if they can vote. On their thread. */
    internal fun remind(player: PlayerRef) {
        if (server.name(player) == null || rewards.sites.isEmpty()) return
        val open = available(player, now()).size
        if (open > 0) texts.send(player, XVoteMessages.reminder, "available" to open)
    }

    /** Items that didn't fit: kept in the database for `/vote claim`. */
    internal fun hold(player: PlayerRef, items: List<ByteArray>, count: Int) {
        val votes = votes ?: return
        val db = votes.backend.database
        db.transaction { session -> co.xenastudios.xvote.store.Tx.run(session, db.sql) { tx -> co.xenastudios.xvote.store.Ledger.hold(tx, player, items, now()) } }
            .onSettled { done, error ->
                if (done is Outcome.Ok) {
                    texts.send(player, XVoteMessages.claimHeld, "count" to count)
                } else {
                    log.warn("Couldn't keep ${server.name(player) ?: player}'s reward items that didn't fit: ${(done as? Outcome.Err)?.error?.detail ?: error}")
                }
            }
    }

    // ---- the API ----

    override fun stats(player: PlayerRef): VoterStats {
        val voter = voters[player] ?: return VoterStats(player, "", 0, 0, 0, 0, 0, 0, emptyMap())
        val now = now()
        val periods = voters.periods
        return VoterStats(player, voter.name, voter.total, voter.today(periods, now), voter.week(periods, now), voter.month(periods, now),
            voter.streakNow(periods, now), voter.bestStreak, voter.lastBySite)
    }

    override fun top(period: Period, limit: Int): List<TopVoter> {
        val ranked = voters.top(period, now())
        val count = minOf(maxOf(1, limit), ranked.size)
        return List(count) { i -> ranked[i].let { TopVoter(it.rank, it.voter.id, it.voter.name, it.votes) } }
    }

    override fun party(): PartyProgress {
        val votes = votes?.party?.votes ?: 0
        return PartyProgress(minOf(Int.MAX_VALUE.toLong(), votes).toInt(), if (config[XVoteConfig.party.enabled]) config[XVoteConfig.party.votes] else 0)
    }

    override fun registerRewardType(owner: String, id: String, type: RewardType) {
        require(id.length in 1..64 && id.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' }) {
            "A reward type's id is 1 to 64 letters, digits, - and _; got \"$id\"."
        }
        rewardTypes[id.lowercase()] = Payout.Registered(owner, type)
    }

    override fun unregisterRewardType(id: String) {
        rewardTypes.remove(id.lowercase())
    }

    override fun submitVote(site: String, username: String, address: String): Pending<VoteResult> {
        val now = now()
        return accept(IncomingVote(site, username, address, now.toString(), VoteSource.API, now))
    }

    /** Where every vote arriving goes: the receiver, `/vote test`, the API. [VoteResult.FAILED] before the database is open. */
    public fun accept(vote: IncomingVote): Pending<VoteResult> = votes?.accept(vote) ?: pendingOf(VoteResult.FAILED).also {
        log.warn("A vote from ${vote.site} for ${vote.username} arrived before the database was open, so it's dropped.")
    }

    public companion object {
        public const val PLUGIN_ID: String = "xvote"
        public const val NAME: String = "xVote"

        /** xVote's version. The adapters' builds check theirs is the same. */
        public const val VERSION: String = "2.0.0"
    }
}

/** `/xvote debug`'s report or `forget`'s player data, made when the command first asks. */
private class AdminPart<T>(private val plugin: XVotePlugin, private val debug: Boolean) : () -> T {
    @Suppress("UNCHECKED_CAST")
    override fun invoke(): T = (if (debug) AdminParts.debug(plugin) else AdminParts.playerData(plugin)) as T
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
