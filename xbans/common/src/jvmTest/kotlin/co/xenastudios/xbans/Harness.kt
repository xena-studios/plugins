package co.xenastudios.xbans

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.pendingOf
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
import co.xenastudios.xbans.config.XBansConfig
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * The fake platform made safe for the database's own threads: the virtual scheduler behind one
 * lock, so a callback from a JDBC thread can schedule work while the test runs what's due. Time
 * moves only when a test says ([advance]), so expiry and retries are exact; [await] runs what's
 * due until what's awaited is done, for at most 15 seconds, so no test can hang.
 */
class TestPlatform(threading: Threading = Threading.MAIN_THREAD, val storage0: MemoryStorage = MemoryStorage()) : Platform {
    // No ticks: work for the server's thread runs when it's due, as a test pumps it, not a tick later.
    private val inner = VirtualScheduler(Threading.PROXY)
    private val lock = Any()
    override val name: String = "Test"
    override val threading: Threading = threading
    override val capabilities: Set<Capability> = setOf(Capability.SQL, Capability.SHARED_API)
    override val development: Boolean = false
    override val storage: MemoryStorage get() = storage0
    override val log: RecordingLog = RecordingLog()
    private val start = Instant.parse("2026-01-01T00:00:00Z")

    override val scheduler: Scheduler = object : Scheduler {
        override fun run(where: Where, delay: Duration, period: Duration?, retired: (() -> Unit)?, action: (Task) -> Unit): Task =
            synchronized(lock) { inner.run(where, delay, period, retired, action) }

        override fun owns(where: Where): Boolean = true
    }

    override val clock: Clock = object : Clock {
        override fun now(): Instant = start + synchronized(lock) { inner.time }
        override fun elapsed(): Duration = synchronized(lock) { inner.time }
    }

    /** Runs what's due now. */
    fun pump() {
        synchronized(lock) { inner.runPending() }
    }

    /** Moves time on, running what comes due. */
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

    /** [pending]'s value, running what's due meanwhile. */
    fun <T> await(pending: CompletableFuture<T>): T {
        until("a result") { pending.isDone }
        return pending.get()
    }
}

/** A server's players, as xBans' adapter would see them: what each was sent, who was kicked, what the console read. */
class FakeBansServer(override val proxy: Boolean = false) : BansServer, OnlinePlayers, LanguageSource {
    class Player(val ref: PlayerRef, val name: String, val address: String?, val permissions: MutableSet<String>, var weight: Int = 0, var locale: String? = null) {
        val received: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())
    }

    val players: MutableMap<PlayerRef, Player> = java.util.concurrent.ConcurrentHashMap()
    val console: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())
    val kicked: MutableList<Pair<String, String>> = java.util.Collections.synchronizedList(ArrayList())
    val dispatched: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())
    /** Players who never joined, as the server's lookup finds them (an offline-mode server's uuids). */
    val known: MutableMap<String, PlayerRef> = java.util.concurrent.ConcurrentHashMap()

    fun join(name: String, address: String? = "203.0.113.9", vararg permissions: String): Player {
        val ref = ref(name)
        return Player(ref, name, address, permissions.toMutableSet()).also { players[ref] = it }
    }

    fun leave(name: String) {
        players.remove(ref(name))
    }

    fun source(name: String): CommandSource {
        val player = players.getValue(ref(name))
        return object : CommandSource {
            override val name: String = player.name
            override val player: PlayerRef = player.ref
            override fun hasPermission(node: String): Boolean = node in player.permissions || "*" in player.permissions
            override fun reply(message: Component) {
                player.received += message.plain()
            }
        }
    }

    override fun online(): List<PlayerRef> = players.keys.toList()
    override fun isOnline(player: PlayerRef): Boolean = player in players
    override fun name(player: PlayerRef): String? = players[player]?.name
    override fun address(player: PlayerRef): String? = players[player]?.address
    override fun send(player: PlayerRef, message: Component) {
        players[player]?.received?.add(message.plain())
    }

    override fun console(message: Component) {
        console += message.plain()
    }

    override fun kick(player: PlayerRef, screen: Component, quiet: Boolean) {
        val gone = players.remove(player) ?: return
        kicked += gone.name to screen.plain()
    }

    override fun hasPermission(player: PlayerRef, node: String): Boolean = players[player]?.permissions?.let { node in it || "*" in it } ?: false
    override fun weight(player: PlayerRef): Int = players[player]?.weight ?: 0
    override fun dispatch(line: String) {
        dispatched += line
    }

    override fun commandNames(label: String): List<String>? = ALIASES[label]
    override fun lookup(name: String): Pending<Pair<PlayerRef, String>?> = pendingOf(known[name.lowercase()]?.let { it to name })
    override fun utcOffsetSeconds(epochMillis: Long): Int = 0

    override fun find(name: String, viewer: CommandSource): PlayerRef? = players.values.firstOrNull { it.name.equals(name, true) }?.ref
    override fun names(viewer: CommandSource): List<String> = players.values.map { it.name }
    override fun clientLocale(player: PlayerRef): String? = players[player]?.locale

    companion object {
        /** EssentialsX's /msg with its aliases, as a command map would answer. */
        val ALIASES: Map<String, List<String>> = mapOf("emsg" to listOf("msg", "essentials:msg", "emsg"), "essentials:msg" to listOf("msg", "emsg"))

        fun ref(name: String): PlayerRef = FakeCommandSource.player(name).player!!
    }
}

/** One xBans on [server], its database the SQLite file in [folder] (shared by two harnesses for a network). */
class Harness(
    val folder: Path = Files.createTempDirectory("xbans"),
    val server: FakeBansServer = FakeBansServer(),
    config: String = "",
    network: Boolean = false,
    val platform: TestPlatform = TestPlatform(if (server.proxy) Threading.PROXY else Threading.MAIN_THREAD),
) {
    private var text = config
    private val files = Files {
        val loaded = Configs.load(XBansConfig, text, TestYaml)
        Loaded((loaded as Outcome.Ok).value.config, Translations.of(XBansMessages.catalog, emptyMap()))
    }

    private val opener = object : Opener {
        override fun describe(config: Config): String = folder.toString() + config[XBansConfig.ips.hash]
        override fun open(config: Config): Outcome<Database, String> =
            when (val opened = JdbcDatabase.open(DatabaseSettings(DatabaseSettings.Type.SQLITE, config[XBansConfig.storage.tablePrefix]), folder.resolve("punishments.db"), Drivers.serverOnly(javaClass.classLoader), platform.log, "Test")) {
                is Outcome.Ok -> Outcome.Ok(opened.value)
                is Outcome.Err -> Outcome.Err(opened.error.detail)
            }
    }

    val plugin: XBansPlugin = XBansPlugin(platform, server, files, server, server, null, opener).also {
        it.networkOnAnyDatabase = network
    }

    val console: FakeCommandSource = FakeCommandSource.console()

    fun start(): Harness {
        plugin.start(files.load())
        plugin.open()
        platform.until("the bans to load") { plugin.ready.isDone }
        return this
    }

    /** Edits the config and reloads, as `/xbans reload` would. */
    fun reload(config: String) {
        text = config
        plugin.apply(files.load())
    }

    /** Runs a command line as [source], then waits until [done] (by default: something was replied, or broadcast). */
    fun run(line: String, source: CommandSource = console, done: (() -> Boolean)? = null) {
        val before = replies(source).size + server.console.size
        plugin.dispatcher.dispatch(source, line).let { result ->
            plugin.texts.feedback.message(result, source)?.let(source::reply)
        }
        platform.until("/$line to finish") { done?.invoke() ?: (replies(source).size + server.console.size > before) }
    }

    fun replies(source: CommandSource): List<String> = when (source) {
        is FakeCommandSource -> source.replies
        else -> server.players[source.player]?.received ?: emptyList()
    }

    val backend get() = plugin.backend!!

    fun stop() {
        val stopped = plugin.stop()
        platform.until("the database to close") { stopped.isDone }
    }
}
