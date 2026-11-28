package co.xenastudios.xvote

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.pendingOf
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.command.CommandSource
import co.xenastudios.core.common.command.OnlinePlayers
import co.xenastudios.core.common.config.Config
import co.xenastudios.core.common.config.Configs
import co.xenastudios.core.common.config.Sound
import co.xenastudios.core.common.db.Database
import co.xenastudios.core.common.db.DatabaseSettings
import co.xenastudios.core.common.i18n.LanguageSource
import co.xenastudios.core.common.i18n.Translations
import co.xenastudios.core.common.platform.Capability
import co.xenastudios.core.common.platform.Clock
import co.xenastudios.core.common.platform.Platform
import co.xenastudios.core.common.platform.Threading
import co.xenastudios.core.common.proxy.ProxyChannel
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
import co.xenastudios.xvote.config.XVoteConfig
import co.xenastudios.xvote.vote.Zones
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
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

    /** A player is here: work for them runs. */
    fun spawn(uuid: kotlin.uuid.Uuid) {
        synchronized(lock) { inner.spawn(uuid, co.xenastudios.core.api.Location(co.xenastudios.core.api.WorldRef.of("world"), 0.0, 64.0, 0.0)) }
    }

    /** A player left: their waiting work is retired, as a real platform's is. */
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

/** A server's players, as xVote's adapter would see them: what each was sent and given, what the console read and ran. */
class FakeVoteServer(override val proxy: Boolean = false, private val platform: () -> TestPlatform? = { null }) : VoteServer, OnlinePlayers, LanguageSource {
    class Player(val ref: PlayerRef, val name: String, val permissions: MutableSet<String>, var locale: String? = null) {
        // Written from the database's threads while a test reads: each read sees a snapshot.
        val received: MutableList<String> = CopyOnWriteArrayList()
        val items: MutableList<String> = CopyOnWriteArrayList()

        /** Free slots: items past it don't fit. */
        var room: Int = 1000
    }

    val players: MutableMap<PlayerRef, Player> = ConcurrentHashMap()
    val console: MutableList<String> = CopyOnWriteArrayList()
    val dispatched: MutableList<String> = CopyOnWriteArrayList()
    val sounds: MutableList<String> = CopyOnWriteArrayList()
    val known: MutableMap<String, PlayerRef> = ConcurrentHashMap()

    fun join(name: String, vararg permissions: String): Player {
        val ref = ref(name)
        platform()?.spawn(ref.uuid)
        // The family's player cache remembers everyone who joined.
        known[name.lowercase()] = ref
        return Player(ref, name, permissions.toMutableSet()).also { players[ref] = it }
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
    override fun name(player: PlayerRef): String? = players[player]?.name
    override fun send(player: PlayerRef, message: Component) {
        players[player]?.received?.add(message.plain())
    }

    override fun console(message: Component) {
        console += message.plain()
    }

    override fun hasPermission(player: PlayerRef, node: String): Boolean = players[player]?.permissions?.let { node in it || "*" in it } ?: false
    override fun dispatch(line: String) {
        dispatched += line
    }

    override fun known(name: String): Pair<PlayerRef, String>? = known[name.lowercase()]?.let { it to name }

    override fun item(written: String): Outcome<Any, String> =
        if (written.startsWith("air")) Outcome.Err("is air") else Outcome.Ok(written)

    override fun give(player: PlayerRef, items: List<Any>): Pair<List<ByteArray>, Int> {
        val p = players[player] ?: return items.map { it.toString().encodeToByteArray() } to items.size
        val left = ArrayList<ByteArray>()
        for (item in items) {
            if (p.room > 0) {
                p.room--
                p.items += item.toString()
            } else {
                left += item.toString().encodeToByteArray()
            }
        }
        return left to left.size
    }

    override fun handOut(player: PlayerRef, held: List<ByteArray>): Triple<Int, List<ByteArray>, Int> {
        val (left, count) = give(player, held.map { it.decodeToString() })
        return Triple(held.size - count, left, count)
    }

    override fun playSound(player: PlayerRef, sound: Sound) {
        sounds += sound.name
    }

    override fun find(name: String, viewer: CommandSource): PlayerRef? = players.values.firstOrNull { it.name.equals(name, true) }?.ref
    override fun names(viewer: CommandSource): List<String> = players.values.map { it.name }
    override fun clientLocale(player: PlayerRef): String? = players[player]?.locale

    companion object {
        /** What every player has, as the platform's defaults give them. */
        val EVERYONE: Set<String> = setOf("xvote.use")

        fun ref(name: String): PlayerRef = FakeCommandSource.player(name).player!!
    }
}

/** A log any thread can write to, as the receiver's threads do: each line's message. */
class ListLog : co.xenastudios.core.common.platform.Log {
    // Read while the receiver's threads write: a test reads a copy, never the list being added to.
    private val kept: MutableList<String> = CopyOnWriteArrayList()
    val lines: List<String> get() = ArrayList(kept)

    override fun info(message: String) {
        kept += message
    }

    override fun warn(message: String, cause: Throwable?) {
        kept += message
    }

    override fun error(message: String, cause: Throwable?) {
        kept += message
    }
}

/** An economy that records every payment. */
class FakeEconomy : Economy {
    val paid: MutableList<Pair<PlayerRef, Double>> = CopyOnWriteArrayList()
    override val name: String = "Fake"
    override val ready: Boolean = true
    override fun give(player: PlayerRef, amount: Double): Pending<String?> {
        paid += player to amount
        return pendingOf(null)
    }
}

/** One xVote on [server], its database the SQLite file in [folder] (shared by two harnesses for a network). */
class Harness(
    val folder: Path = Files.createTempDirectory("xvote"),
    proxy: Boolean = false,
    config: String = "",
    network: Boolean = false,
    channel: ((Platform) -> ProxyChannel)? = null,
    val receiver: VoteReceiver? = null,
    imports: VoteImports? = null,
) {
    val platform: TestPlatform = TestPlatform(if (proxy) Threading.PROXY else Threading.MAIN_THREAD)
    val server: FakeVoteServer = FakeVoteServer(proxy) { platform }
    val economy: FakeEconomy = FakeEconomy()
    private var text = config
    private val files = object : co.xenastudios.xvote.Files {
        override fun load(): Loaded {
            val loaded = Configs.load(XVoteConfig, text, TestYaml)
            return Loaded((loaded as Outcome.Ok).value.config, Translations.of(XVoteMessages.catalog, emptyMap()))
        }
    }

    private val opener = Opener { config: Config ->
        when (val opened = JdbcDatabase.open(DatabaseSettings(DatabaseSettings.Type.SQLITE, config[XVoteConfig.storage.tablePrefix]), folder.resolve("votes.db"), Drivers.serverOnly(javaClass.classLoader), platform.log, "Test")) {
            is Outcome.Ok -> Outcome.Ok<Database>(opened.value)
            is Outcome.Err -> Outcome.Err(opened.error.detail)
        }
    }

    val plugin: XVotePlugin = XVotePlugin(
        platform, server, files, server, server, null, opener, receiver = receiver, imports = imports, channel = channel?.invoke(platform),
        economy = economy, zones = Zones.UTC_ONLY,
    ).also { it.networkOnAnyDatabase = network }

    val console: FakeCommandSource = FakeCommandSource.console()

    fun start(): Harness {
        plugin.start(files.load())
        plugin.open()
        platform.until("the voters to load") { plugin.ready.isDone }
        check(plugin.ready.get()) { "the database didn't open: ${platform.log.lines}" }
        return this
    }

    fun reload(config: String) {
        text = config
        plugin.apply(files.load())
    }

    /** A player joins, as the adapter tells the plugin. */
    fun join(name: String, vararg permissions: String): FakeVoteServer.Player {
        val player = server.join(name, *permissions)
        plugin.joined(player.ref, name)
        return player
    }

    fun leave(name: String) {
        server.leave(name)
        plugin.quit(FakeVoteServer.ref(name), name)
    }

    /** A vote as a list sends one, now. */
    fun vote(name: String, site: String = "PlanetMinecraft.com", timestamp: String = plugin.now().toString()): co.xenastudios.xvote.api.VoteResult =
        platform.await(plugin.accept(co.xenastudios.xvote.vote.IncomingVote(site, name, "203.0.113.7", timestamp, co.xenastudios.xvote.api.VoteSource.VOTIFIER_V2, plugin.now())))

    fun run(line: String, source: CommandSource = console, done: (() -> Boolean)? = null) {
        val before = replies(source).size + server.console.size
        plugin.dispatcher.dispatch(source, line).let { result -> plugin.texts.feedback.message(result, source)?.let(source::reply) }
        platform.until("/$line to finish") { done?.invoke() ?: (replies(source).size + server.console.size > before) }
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
