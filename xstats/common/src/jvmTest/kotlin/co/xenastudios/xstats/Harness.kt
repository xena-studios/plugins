package co.xenastudios.xstats

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.command.CommandSource
import co.xenastudios.core.common.command.OnlinePlayers
import co.xenastudios.core.common.config.Config
import co.xenastudios.core.common.config.Configs
import co.xenastudios.core.common.db.Database
import co.xenastudios.core.common.db.DatabaseSettings
import co.xenastudios.core.common.i18n.LanguageSource
import co.xenastudios.core.common.i18n.Translations
import co.xenastudios.core.common.platform.Capability
import co.xenastudios.core.common.platform.Clock
import co.xenastudios.core.common.platform.Platform
import co.xenastudios.core.common.platform.Threading
import co.xenastudios.core.common.scheduler.Scheduler
import co.xenastudios.core.common.scheduler.Task
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.text.Component
import co.xenastudios.core.jvm.db.Drivers
import co.xenastudios.core.jvm.db.JdbcDatabase
import co.xenastudios.core.testing.FakeCommandSource
import co.xenastudios.core.testing.MemoryStorage
import co.xenastudios.core.testing.RecordingLog
import co.xenastudios.core.testing.TestYaml
import co.xenastudios.core.testing.VirtualScheduler
import co.xenastudios.xstats.config.XStatsConfig
import co.xenastudios.xstats.data.Event
import co.xenastudios.xstats.data.Zones
import co.xenastudios.xstats.stat.Catalog
import co.xenastudios.xstats.stat.StatDef
import co.xenastudios.xstats.stat.Vanilla
import co.xenastudios.xstats.stat.VanillaReader
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * The fake platform made safe for the database's own threads: the virtual scheduler behind one lock,
 * so a callback from a JDBC thread can schedule work while the test runs what's due. Time moves only
 * when a test says ([advance]); [until] runs what's due until what's awaited is done, for at most 15
 * seconds, so no test can hang.
 */
class TestPlatform(threading: Threading = Threading.MAIN_THREAD, start: Instant = Instant.parse("2026-01-05T12:00:00Z")) : Platform {
    private val inner = VirtualScheduler(Threading.PROXY)
    private val lock = Any()
    private val began = start
    override val name: String = "Test"
    override val threading: Threading = threading
    override val capabilities: Set<Capability> = setOf(Capability.SQL, Capability.SHARED_API)
    override val development: Boolean = false
    override val storage: MemoryStorage = MemoryStorage()
    override val log: RecordingLog = RecordingLog()

    fun spawn(uuid: kotlin.uuid.Uuid) {
        synchronized(lock) { inner.spawn(uuid, co.xenastudios.core.api.Location(co.xenastudios.core.api.WorldRef.of("world"), 0.0, 64.0, 0.0)) }
    }

    fun despawn(uuid: kotlin.uuid.Uuid) {
        synchronized(lock) { inner.despawn(uuid) }
    }

    override val scheduler: Scheduler = object : Scheduler {
        override fun run(where: Where, delay: Duration, period: Duration?, retired: (() -> Unit)?, action: (Task) -> Unit): Task =
            synchronized(lock) { inner.run(where, delay, period, retired, action) }

        override fun owns(where: Where): Boolean = true
    }

    override val clock: Clock = object : Clock {
        override fun now(): Instant = began + synchronized(lock) { inner.time }
        override fun elapsed(): Duration = synchronized(lock) { inner.time }
    }

    fun pump() {
        synchronized(lock) { inner.runPending() }
    }

    fun advance(duration: Duration) {
        synchronized(lock) { inner.advance(duration) }
    }

    /** Runs what's due until [done] says so, for at most 15 seconds. */
    fun until(what: String = "it", done: () -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (true) {
            pump()
            if (done()) return
            check(System.nanoTime() < deadline) { "$what didn't happen within 15 seconds; log: ${log.lines}" }
            Thread.sleep(2)
        }
    }

    fun <T> await(pending: CompletableFuture<T>): T {
        until("a result") { pending.isDone }
        return pending.get()
    }
}

/**
 * A server's players, as xStats' adapter would see them: each with Minecraft's own counters (by
 * term, as `/scoreboard` names them), where they look, what they were sent, and their permissions.
 */
class FakeStatsServer(override val proxy: Boolean = false, private val platform: () -> TestPlatform? = { null }) :
    StatsServer, OnlinePlayers, LanguageSource, VanillaReader {
    class Player(val ref: PlayerRef, val name: String, val permissions: MutableSet<String>) {
        val received: MutableList<String> = Collections.synchronizedList(ArrayList())
        val counters: MutableMap<String, Long> = ConcurrentHashMap()
        val placeholders: MutableMap<String, String> = ConcurrentHashMap()
        var yaw: Float = 0f
        var pitch: Float = 0f
        var locale: String? = null
    }

    val players: MutableMap<PlayerRef, Player> = ConcurrentHashMap()
    val banned: MutableSet<PlayerRef> = ConcurrentHashMap.newKeySet()

    /** Terms this "server" doesn't have, which read 0. */
    val unknown: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun join(name: String, vararg permissions: String): Player {
        val ref = ref(name)
        platform()?.spawn(ref.uuid)
        return players.getOrPut(ref) { Player(ref, name, permissions.toMutableSet()) }
    }

    fun leave(name: String) {
        val ref = ref(name)
        players.remove(ref)
        platform()?.despawn(ref.uuid)
    }

    fun player(name: String): Player = players.getValue(ref(name))

    fun source(name: String): CommandSource {
        val player = player(name)
        return object : CommandSource {
            override val name: String = player.name
            override val player: PlayerRef = player.ref
            override fun hasPermission(node: String): Boolean = node in player.permissions || "*" in player.permissions || node in EVERYONE
            override fun reply(message: Component) {
                player.received += message.plain()
            }
        }
    }

    override fun online(): List<PlayerRef> = players.keys.toList()
    override fun isOnline(player: PlayerRef): Boolean = player in players
    override fun send(player: PlayerRef, message: Component) {
        players[player]?.received?.add(message.plain())
    }

    override fun hasPermission(player: PlayerRef, node: String): Boolean = players[player]?.permissions?.let { node in it || "*" in it } ?: false
    override fun banned(): Set<PlayerRef> = banned.toSet()
    override val vanilla: VanillaReader? get() = if (proxy) null else this

    private class Prepared(val terms: List<Vanilla.Term>, override val missing: List<Vanilla.Term>) : VanillaReader.Prepared

    override fun prepare(terms: List<Vanilla.Term>): VanillaReader.Prepared = Prepared(terms, terms.filter { it.toString() in unknown })

    override fun read(player: PlayerRef, stats: Catalog.Snapshot, quit: Boolean, placeholders: Boolean): Event.Sample? {
        val p = players[player] ?: return null
        val raw = LongArray(stats.width) { Event.Sample.NOT_READ }
        for (stat in stats.stats) {
            when (val source = stat.source) {
                is StatDef.Vanillas -> raw[stat.index] = (source.prepared as Prepared).terms.sumOf { term ->
                    if (term.toString() in unknown) 0L else Vanilla.stored(term, p.counters[term.toString()] ?: 0L)
                }
                is StatDef.Placeholder -> if (placeholders) p.placeholders[source.placeholder]?.let { co.xenastudios.xstats.stat.Numbers.parse(it, stat.decimals) }?.let { raw[stat.index] = it }
                else -> {}
            }
        }
        return Event.Sample(player, p.name, platform()?.clock?.now()?.toEpochMilliseconds() ?: 0, raw, p.yaw, p.pitch,
            "xstats.leaderboard.exclude" in p.permissions, quit)
    }

    override fun find(name: String, viewer: CommandSource): PlayerRef? = players.values.firstOrNull { it.name.equals(name, true) }?.ref
    override fun names(viewer: CommandSource): List<String> = players.values.map { it.name }
    override fun clientLocale(player: PlayerRef): String? = players[player]?.locale

    companion object {
        /** What every player has, as the platform's defaults give them. */
        val EVERYONE: Set<String> = setOf("xstats.private")

        fun ref(name: String): PlayerRef = FakeCommandSource.player(name).player!!
    }
}

/** One xStats on [server], its database the SQLite file in [folder] (shared by two harnesses for a network). */
class Harness(
    val folder: Path = java.nio.file.Files.createTempDirectory("xstats"),
    proxy: Boolean = false,
    config: String = "",
    network: Boolean = false,
    extras: (Harness) -> Extras? = { null },
    /** `/xstats migrate`'s target, by the type asked for; `null` for a server that can't open one. */
    private val target: ((DatabaseSettings.Type) -> Database)? = null,
) {
    val platform: TestPlatform = TestPlatform(if (proxy) Threading.PROXY else Threading.MAIN_THREAD)
    val server: FakeStatsServer = FakeStatsServer(proxy) { platform }
    private var text = config
    /** What `/xstats migrate` set `storage.type` to, in order. */
    val storageWrites: MutableList<co.xenastudios.core.common.family.StorageType> = Collections.synchronizedList(ArrayList())

    private val files = object : co.xenastudios.xstats.Files {
        override fun load(): Loaded = this@Harness.load()

        override fun useStorage(type: co.xenastudios.core.common.family.StorageType): String? {
            storageWrites += type
            return null
        }
    }

    fun load(): Loaded {
        val loaded = Configs.load(XStatsConfig, text, TestYaml)
        return Loaded((loaded as Outcome.Ok).value.config, Translations.of(XStatsMessages.catalog, emptyMap()))
    }

    private val opener = object : Opener {
        override fun open(config: Config): Outcome<Database, String> =
            when (val opened = JdbcDatabase.open(DatabaseSettings(DatabaseSettings.Type.SQLITE, config[XStatsConfig.storage.tablePrefix]), folder.resolve("stats.db"), Drivers.serverOnly(javaClass.classLoader), platform.log, "Test")) {
                is Outcome.Ok -> Outcome.Ok<Database>(opened.value)
                is Outcome.Err -> Outcome.Err(opened.error.detail)
            }

        override fun openAs(config: Config, type: DatabaseSettings.Type): Outcome<Database, String> {
            val make = target ?: return Outcome.Err("no target in this test")
            return Outcome.Ok(make(type))
        }
    }

    val extras: Extras? = extras(this)

    val plugin: XStatsPlugin = XStatsPlugin(platform, server, files, server, server, null, opener, makeExtras = this.extras?.let { made -> { made } }, zones = Zones.UTC_ONLY)
        .also { it.networkOnAnyDatabase = network }

    val console: FakeCommandSource = FakeCommandSource.console()

    fun start(): Harness {
        plugin.start(files.load())
        plugin.open()
        platform.until("the stats to load") { plugin.ready.isDone }
        check(plugin.ready.get()) { "the database didn't open: ${platform.log.lines}" }
        return this
    }

    fun reload(config: String) {
        text = config
        plugin.apply(files.load())
        settle()
    }

    /** Waits for what's queued on the database's writer. */
    fun settle() {
        val db = plugin.database ?: return
        platform.await(db.session { Outcome.Ok(Unit) })
    }

    /** A player joins, as the adapter tells the plugin. */
    fun join(name: String, vararg permissions: String): FakeStatsServer.Player {
        val player = server.join(name, *permissions)
        plugin.joined(player.ref, name, 0, player.yaw, player.pitch)
        return player
    }

    fun leave(name: String) {
        plugin.quit(FakeStatsServer.ref(name))
        server.leave(name)
    }

    /** A save, as the repeating task starts it: every online player read, then written. */
    fun save(): Boolean {
        val done = CompletableFuture<Boolean>()
        check(plugin.save { done.complete(it) }) { "a save was already running" }
        // The readings are a tick or two apart; the save waits for them.
        platform.until("the save") {
            platform.advance(kotlin.time.Duration.parse("50ms"))
            done.isDone
        }
        return done.get()
    }

    fun run(line: String, source: CommandSource = console, done: (() -> Boolean)? = null) {
        val before = replies(source).size
        plugin.dispatcher.dispatch(source, line).let { result -> plugin.texts.feedback.message(result, source)?.let(source::reply) }
        platform.until("/$line to finish") {
            platform.advance(kotlin.time.Duration.parse("50ms"))
            done?.invoke() ?: (replies(source).size > before)
        }
    }

    fun replies(source: CommandSource): List<String> = when (source) {
        is FakeCommandSource -> source.replies
        else -> server.players[source.player]?.received ?: emptyList()
    }

    fun stop() {
        val stopped = plugin.stop()
        platform.until("the database to close") { stopped.isDone }
    }
}
