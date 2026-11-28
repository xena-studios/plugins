package co.xenastudios.xrewind

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.newPending
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
import co.xenastudios.core.common.world.BlockChange
import co.xenastudios.core.common.world.ItemData
import co.xenastudios.core.common.world.WorldHooks
import co.xenastudios.core.jvm.db.Drivers
import co.xenastudios.core.jvm.db.JdbcDatabase
import co.xenastudios.core.testing.FakeCommandSource
import co.xenastudios.core.testing.FakeWorld
import co.xenastudios.core.testing.MemoryStorage
import co.xenastudios.core.testing.RecordingLog
import co.xenastudios.core.testing.TestYaml
import co.xenastudios.core.testing.VirtualScheduler
import co.xenastudios.xrewind.config.XRewindConfig
import co.xenastudios.xrewind.query.Center
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * The fake platform made safe for the database's own threads: the virtual scheduler behind one
 * lock, so a callback from a JDBC thread can schedule work while the test runs what's due. Time
 * moves only when a test says ([advance]); [until] runs what's due until what's awaited is done,
 * for at most 15 seconds, so no test can hang.
 */
class TestPlatform(threading: Threading = Threading.MAIN_THREAD, val memory: MemoryStorage = MemoryStorage()) : Platform {
    private val inner = VirtualScheduler(Threading.PROXY)
    private val lock = Any()
    override val name: String = "Test"
    override val threading: Threading = threading
    override val capabilities: Set<Capability> = setOf(Capability.SQL, Capability.SHARED_API)
    override val development: Boolean = false
    override val storage: MemoryStorage get() = memory
    override val log: RecordingLog = RecordingLog()

    /** When the test's clock starts: well after 2026 began, so stored times are positive. */
    var start: Instant = Instant.parse("2026-06-01T00:00:00Z")

    override val scheduler: Scheduler = object : Scheduler {
        override fun run(where: Where, delay: Duration, period: Duration?, retired: (() -> Unit)?, action: (Task) -> Unit): Task =
            synchronized(lock) { inner.run(if (where is Where.At) Where.Global else where, delay, period, retired, action) }

        override fun owns(where: Where): Boolean = true
    }

    override val clock: Clock = object : Clock {
        override fun now(): Instant = start + synchronized(lock) { inner.time }
        override fun elapsed(): Duration = synchronized(lock) { inner.time }
    }

    fun pump() {
        synchronized(lock) { inner.runPending() }
    }

    fun advance(duration: Duration) {
        synchronized(lock) { inner.advance(duration) }
    }

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
 * A server's world as xRewind's adapter would see it: blocks by place (air where nothing was
 * set), what containers hold (items by type) and signs' bytes, players, and the world hooks as
 * the core's [FakeWorld]. Items' bytes are `item:<type>`; a chunk in [held] doesn't load until a
 * test lets it.
 */
class FakeRewindServer(log: co.xenastudios.core.common.platform.Log) : RewindServer, OnlinePlayers, LanguageSource {
    val world = FakeWorld(log, co.xenastudios.core.api.event.EventPriority.MONITOR)
    val blocks: MutableMap<Spot, String> = ConcurrentHashMap()
    val contents: MutableMap<Spot, ByteArray> = ConcurrentHashMap()
    val items: MutableMap<Spot, MutableMap<String, Int>> = ConcurrentHashMap()
    val players: MutableMap<PlayerRef, String> = ConcurrentHashMap()
    val positions: MutableMap<PlayerRef, Center> = ConcurrentHashMap()
    val received: MutableMap<PlayerRef, MutableList<String>> = ConcurrentHashMap()
    val previews: MutableMap<PlayerRef, List<Shown>> = ConcurrentHashMap()
    var events: ServerEvents? = null
    var inspecting: Inspecting? = null

    /** Chunks ("world|x|z") that won't load until [let] is called with them. */
    val held: MutableMap<String, CompletableFuture<Boolean>> = ConcurrentHashMap()
    val holding: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun let(chunk: String) {
        holding.remove(chunk)
        held.remove(chunk)?.complete(true)
    }

    fun join(name: String, at: Center = Center("world", 0, 64, 0)): PlayerRef {
        val ref = FakeCommandSource.player(name).player!!
        players[ref] = name
        positions[ref] = at
        return ref
    }

    fun source(ref: PlayerRef, vararg permissions: String): CommandSource = object : CommandSource {
        override val name: String = players.getValue(ref)
        override val player: PlayerRef = ref
        override fun hasPermission(node: String): Boolean = permissions.isEmpty() || node in permissions
        override fun reply(message: Component) {
            received.getOrPut(ref) { java.util.Collections.synchronizedList(ArrayList()) } += message.plain()
        }
    }

    fun state(world: String, x: Int, y: Int, z: Int): String = blocks[Spot(world, x, y, z)] ?: "minecraft:air"

    override fun online(): List<PlayerRef> = players.keys.toList()
    override fun isOnline(player: PlayerRef): Boolean = player in players
    override fun name(player: PlayerRef): String? = players[player]
    override fun send(player: PlayerRef, message: Component) {
        received.getOrPut(player) { java.util.Collections.synchronizedList(ArrayList()) } += message.plain()
    }

    override fun actionBar(player: PlayerRef, message: Component) {}
    override fun position(player: PlayerRef): Center? = positions[player]
    override fun hooks(): WorldHooks = world

    override fun listen(kinds: Set<Heard>, events: ServerEvents): AutoCloseable {
        this.events = events
        return AutoCloseable { this.events = null }
    }

    override fun inspector(inspecting: Inspecting): AutoCloseable {
        this.inspecting = inspecting
        return AutoCloseable { this.inspecting = null }
    }

    override fun worlds(): List<String> = listOf("world")
    override fun hasWorld(world: String): Boolean = world == "world"
    override fun known(name: String): String? = name.lowercase().let { if (':' in it) it else "minecraft:$it" }.takeIf { !it.endsWith(":nothing") }
    override fun holds(type: String): Boolean = type == "minecraft:chest" || type.endsWith("_sign")
    override fun contents(change: BlockChange): ByteArray? = contents[Spot(change.world.name, change.x, change.y, change.z)]
    override fun itemBytes(item: ItemData): ByteArray = "item:${item.type}".encodeToByteArray()
    override fun itemBytes(id: String): ByteArray = "item:$id".encodeToByteArray()
    override fun itemType(bytes: ByteArray): String? = bytes.decodeToString().removePrefix("item:").takeIf { bytes.decodeToString().startsWith("item:") }
    override fun readState(state: String): String? = if (state.startsWith("minecraft:gone")) null else state

    override fun load(world: String, chunkX: Int, chunkZ: Int): Pending<Boolean> {
        val key = "$world|$chunkX|$chunkZ"
        if (key in holding) return held.getOrPut(key) { CompletableFuture() }
        return pendingOf(true)
    }

    override fun release(world: String, chunkX: Int, chunkZ: Int) {}

    override fun set(at: Spot, state: String, contents: ByteArray?): Previous {
        val was = Previous(blocks[at] ?: "minecraft:air", this.contents[at])
        if (state == "minecraft:air") blocks.remove(at) else blocks[at] = state
        if (contents != null) this.contents[at] = contents else this.contents.remove(at)
        return was
    }

    override fun moveItems(at: Spot, item: ByteArray, delta: Int): Int {
        val type = itemType(item) ?: return 0
        val held = items.getOrPut(at) { ConcurrentHashMap() }
        val now = held[type] ?: 0
        val moved = if (delta < 0) -minOf(now, -delta) else delta
        held[type] = now + moved
        return moved
    }

    override fun stateAt(at: Spot): String = blocks[at] ?: "minecraft:air"

    override fun preview(player: PlayerRef, blocks: List<Shown>) {
        previews[player] = blocks
    }

    override fun find(name: String, viewer: CommandSource): PlayerRef? = players.entries.firstOrNull { it.value.equals(name, true) }?.key
    override fun names(viewer: CommandSource): List<String> = players.values.toList()
    override fun clientLocale(player: PlayerRef): String? = null
}

/** One xRewind on a [FakeRewindServer], its database the SQLite file in [folder]. */
class Harness(
    val folder: Path = Files.createTempDirectory("xrewind"),
    config: String = "",
    val platform: TestPlatform = TestPlatform(),
    val history: HistoryImport? = null,
    /** Another database than the folder's SQLite file: MySqlTest's MariaDB. */
    mysql: DatabaseSettings? = null,
) {
    val server = FakeRewindServer(platform.log)
    private var text = config
    private val files = Files {
        val loaded = Configs.load(XRewindConfig, text, TestYaml)
        Loaded((loaded as Outcome.Ok).value.config, Translations.of(XRewindMessages.catalog, emptyMap()))
    }

    private val opener = object : Opener {
        override fun describe(config: Config): String = folder.toString() + config[XRewindConfig.storage.tablePrefix]
        override fun open(config: Config): Outcome<Database, String> =
            when (val opened = JdbcDatabase.open(mysql ?: DatabaseSettings(DatabaseSettings.Type.SQLITE, config[XRewindConfig.storage.tablePrefix]), folder.resolve("history.db"), Drivers.serverOnly(javaClass.classLoader), platform.log, "Test")) {
                is Outcome.Ok -> Outcome.Ok(opened.value)
                is Outcome.Err -> Outcome.Err(opened.error.detail)
            }
    }

    val plugin: XRewindPlugin = XRewindPlugin(platform, server, files, server, server, null, opener, importer = history?.let { h -> { h } })

    val console: FakeCommandSource = FakeCommandSource.console()

    fun start(): Harness {
        plugin.start(files.load())
        plugin.open()
        platform.until("the database to open") { plugin.opened.isDone }
        return this
    }

    fun reload(config: String) {
        text = config
        plugin.apply(files.load())
    }

    /** Runs a command line as [source], then waits until [done] (by default: something was replied). */
    fun run(line: String, source: CommandSource = console, done: (() -> Boolean)? = null) {
        val before = replies(source).size
        plugin.dispatcher.dispatch(source, line).let { result ->
            plugin.texts.feedback.message(result, source)?.let(source::reply)
        }
        platform.until("/$line to finish") { done?.invoke() ?: (replies(source).size > before) }
    }

    fun replies(source: CommandSource): List<String> = when (source) {
        is FakeCommandSource -> source.replies
        else -> server.received[source.player]?.toList() ?: emptyList()
    }

    /** Everything logged so far, written. */
    fun flush() {
        platform.await(plugin.writer.flush())
    }

    fun stop() {
        val stopped = plugin.stop()
        platform.until("the database to close") { stopped.isDone }
    }

    companion object {
        fun at(x: Int, y: Int, z: Int): FakeWorld.Spot = FakeWorld.at("world", x, y, z)
    }
}
