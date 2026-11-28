package co.xenastudios.xplots

import co.xenastudios.core.api.Location
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
import co.xenastudios.core.common.menu.Icon
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
import co.xenastudios.core.testing.FakeMenus
import co.xenastudios.core.testing.MemoryStorage
import co.xenastudios.core.testing.RecordingLog
import co.xenastudios.core.testing.TestYaml
import co.xenastudios.core.testing.VirtualScheduler
import co.xenastudios.xplots.api.PlotBox
import co.xenastudios.xplots.api.RegionBackend
import co.xenastudios.xplots.config.XPlotsConfig
import co.xenastudios.xplots.model.Box
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * The fake platform made safe for the database's own threads: the virtual scheduler behind one
 * lock, every place and player run as global work. Time moves only when a test says ([advance]);
 * [until] runs what's due until what's awaited is done, for at most 15 seconds, so no test can hang.
 */
class TestPlatform : Platform {
    private val inner = VirtualScheduler(Threading.PROXY)
    private val lock = Any()
    override val name: String = "Test"
    override val threading: Threading = Threading.MAIN_THREAD
    override val capabilities: Set<Capability> = setOf(Capability.SQL, Capability.SHARED_API)
    override val development: Boolean = false
    override val storage: MemoryStorage = MemoryStorage()
    override val log: RecordingLog = RecordingLog()

    var start: Instant = Instant.parse("2026-06-01T00:00:00Z")

    override val scheduler: Scheduler = object : Scheduler {
        override fun run(where: Where, delay: Duration, period: Duration?, retired: (() -> Unit)?, action: (Task) -> Unit): Task =
            synchronized(lock) { inner.run(if (where is Where.Async) Where.Async else Where.Global, delay, period, retired, action) }

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

/** A block's place, for the fake world. */
data class At(val world: String, val x: Int, val y: Int, val z: Int)

/** A sign in the fake world: what it's made of, where it faces, and its lines as plain text. */
class FakeSign(val material: String, val facing: Int) {
    @Volatile
    var lines: List<String> = emptyList()
}

/** A server's world as xPlots' adapter would see it: blocks by place (air where nothing was set), signs, players, items, menus. */
class FakePlotServer : PlotServer, OnlinePlayers, LanguageSource {
    val blocks: MutableMap<At, String> = ConcurrentHashMap()
    val signs: MutableMap<At, FakeSign> = ConcurrentHashMap()
    val players: MutableMap<PlayerRef, String> = ConcurrentHashMap()
    val positions: MutableMap<PlayerRef, Location> = ConcurrentHashMap()
    val permissions: MutableMap<PlayerRef, MutableSet<String>> = ConcurrentHashMap()
    val received: MutableMap<PlayerRef, MutableList<String>> = ConcurrentHashMap()
    val bars: MutableMap<PlayerRef, MutableList<String>> = ConcurrentHashMap()
    val teleported: MutableMap<PlayerRef, Location> = ConcurrentHashMap()
    val inventory: MutableMap<PlayerRef, Int> = ConcurrentHashMap()
    val hidden: MutableSet<Pair<PlayerRef, PlayerRef>> = ConcurrentHashMap.newKeySet()
    val known: MutableMap<String, PlayerRef> = ConcurrentHashMap()
    val regionsMade: MutableMap<String, Box> = ConcurrentHashMap()
    var worldGuard = true
    override val menus: FakeMenus = FakeMenus()

    fun join(name: String, x: Double = 0.0, y: Double = 64.0, z: Double = 0.0, vararg perms: String): PlayerRef {
        val ref = FakeCommandSource.player(name).player!!
        players[ref] = name
        known[name.lowercase()] = ref
        positions[ref] = Location(co.xenastudios.core.api.WorldRef.of("world"), x, y, z)
        permissions[ref] = perms.toMutableSet()
        return ref
    }

    fun leave(player: PlayerRef) {
        players.remove(player)
    }

    fun source(ref: PlayerRef): CommandSource = object : CommandSource {
        override val name: String = players.getValue(ref)
        override val player: PlayerRef = ref
        override fun hasPermission(node: String): Boolean = this@FakePlotServer.hasPermission(ref, node)
        override fun reply(message: Component) = send(ref, message)
    }

    fun said(player: PlayerRef): List<String> = received[player]?.toList().orEmpty()

    override fun online(): List<PlayerRef> = players.keys.toList()
    override fun isOnline(player: PlayerRef): Boolean = player in players
    override fun name(player: PlayerRef): String? = players[player]
    override fun send(player: PlayerRef, message: Component) {
        received.getOrPut(player) { java.util.Collections.synchronizedList(ArrayList()) } += message.plain()
    }

    override fun actionBar(player: PlayerRef, message: Component) {
        bars.getOrPut(player) { java.util.Collections.synchronizedList(ArrayList()) } += message.plain()
    }

    override fun hasPermission(player: PlayerRef, node: String): Boolean = node == XPlotsPlugin.USE || node in permissions[player].orEmpty()
    override fun highestNumbered(player: PlayerRef, prefix: String): Int =
        permissions[player].orEmpty().filter { it.startsWith(prefix) }.mapNotNull { it.removePrefix(prefix).toIntOrNull() }.maxOrNull() ?: -1

    override fun position(player: PlayerRef): Location? = positions[player]
    override fun known(name: String): Known? = known[name.lowercase()]?.let { Known(it, players[it] ?: name) }
    override fun teleport(player: PlayerRef, to: Location) {
        teleported[player] = to
        positions[player] = to
    }

    override fun canSee(viewer: PlayerRef, target: PlayerRef): Boolean = true
    override fun hasWorld(world: String): Boolean = world == "world"
    override fun worlds(): List<String> = listOf("world")
    override fun isLoaded(world: String, chunkX: Int, chunkZ: Int): Boolean = true
    override fun load(world: String, chunkX: Int, chunkZ: Int): Pending<Boolean> = pendingOf(true)
    override fun release(world: String, chunkX: Int, chunkZ: Int) {}
    override fun stateAt(world: String, x: Int, y: Int, z: Int): String = blocks[At(world, x, y, z)] ?: "minecraft:air"
    override fun compile(state: String): Any? = if (state.startsWith("minecraft:gone")) null else state
    override fun matches(world: String, x: Int, y: Int, z: Int, compiled: Any): Boolean = stateAt(world, x, y, z) == compiled
    override fun holds(compiled: Any): Boolean = compiled == "minecraft:chest"
    override fun set(world: String, x: Int, y: Int, z: Int, compiled: Any, fresh: Boolean) {
        val at = At(world, x, y, z)
        if (compiled == "minecraft:air") blocks.remove(at) else blocks[at] = compiled as String
        signs.remove(at)
    }

    override fun clearOut(world: String, box: Box, front: Location) {
        for ((p, at) in positions) {
            if (at.world.name == world && box.contains(at.blockX, at.blockY, at.blockZ)) positions[p] = front
        }
    }

    override fun isFree(world: String, x: Int, y: Int, z: Int): Boolean = At(world, x, y, z) !in blocks && At(world, x, y, z) !in signs
    override fun isSign(world: String, x: Int, y: Int, z: Int): Boolean = At(world, x, y, z) in signs
    override fun placeSign(world: String, x: Int, y: Int, z: Int, material: String, facing: Int) {
        signs[At(world, x, y, z)] = FakeSign(material, facing)
    }

    override fun writeSign(world: String, x: Int, y: Int, z: Int, lines: List<Component>): Boolean {
        val sign = signs[At(world, x, y, z)] ?: return false
        sign.lines = lines.map { it.plain() }
        return true
    }

    override fun removeSign(world: String, x: Int, y: Int, z: Int) {
        signs.remove(At(world, x, y, z))
    }

    override fun dust(player: PlayerRef, x: Double, y: Double, z: Double, rgb: Int, size: Float, count: Int, spread: Double) {}
    val wands: MutableSet<PlayerRef> = ConcurrentHashMap.newKeySet()
    override fun giveWand(player: PlayerRef, name: Component, lore: List<Component>) {
        wands += player
    }

    override fun icon(id: String): Icon? = if (id == "air") null else Icon(if (':' in id) id else "minecraft:$id")
    override fun head(player: PlayerRef, name: String): String? = "head:$name"
    override fun countItems(player: PlayerRef, currency: ItemCurrency): Int = inventory[player] ?: 0
    override fun takeItems(player: PlayerRef, currency: ItemCurrency, amount: Int): Boolean {
        val has = inventory[player] ?: 0
        if (has < amount) return false
        inventory[player] = has - amount
        return true
    }

    override fun giveItems(player: PlayerRef, currency: ItemCurrency, amount: Int) {
        inventory[player] = (inventory[player] ?: 0) + amount
    }

    override fun regions(): RegionBackend? = if (!worldGuard) null else object : RegionBackend {
        override fun define(id: String, world: co.xenastudios.core.api.WorldRef, columns: PlotBox) {
            regionsMade[id] = Box(columns.minX, columns.minY, columns.minZ, columns.maxX, columns.maxY, columns.maxZ)
        }

        override fun remove(id: String, world: co.xenastudios.core.api.WorldRef) {
            regionsMade.remove(id)
        }
    }

    var selection: Pair<String, Box>? = null
    override fun worldEditSelection(player: PlayerRef): Pair<String, Box>? = selection

    override fun find(name: String, viewer: CommandSource): PlayerRef? = players.entries.firstOrNull { it.value.equals(name, true) }?.key
    override fun names(viewer: CommandSource): List<String> = players.values.toList()
    override fun clientLocale(player: PlayerRef): String? = null
}

/**
 * An economy with a ledger, as xMoney keeps one: balances by player, and every payment's source and
 * reason. [down] makes payments fail; [afterWithdraw] runs as a payment is taken, before xPlots hears
 * it was (a test's crash).
 */
class FakeEconomy(private val ledger: Boolean = true) : Economy {
    override val name: String = if (ledger) "xMoney" else "Vault (Test)"
    val balances: MutableMap<PlayerRef, Double> = ConcurrentHashMap()
    val payments: MutableList<Pair<PlayerRef, String>> = java.util.Collections.synchronizedList(ArrayList())
    var held: Pending<Boolean>? = null
    var hold = false

    override fun has(player: PlayerRef, amount: Double): Boolean = (balances[player] ?: 0.0) >= amount
    override fun withdraw(player: PlayerRef, amount: Double, reason: String): Pending<Boolean> {
        val has = balances[player] ?: 0.0
        if (has < amount) return pendingOf(false)
        balances[player] = has - amount
        payments += player to "xPlots: $reason"
        if (hold) return newPending<Boolean>().also { held = it }
        return pendingOf(true)
    }

    override fun deposit(player: PlayerRef, amount: Double, reason: String): Pending<Boolean> {
        balances[player] = (balances[player] ?: 0.0) + amount
        payments += player to "xPlots: $reason"
        return pendingOf(true)
    }

    override fun format(amount: Double): String = "$" + co.xenastudios.xplots.model.Settings.strip(amount)
    override fun charged(player: PlayerRef, marker: String): Pending<Boolean?> =
        pendingOf(if (!ledger) null else payments.any { it.first == player && it.second.startsWith("xPlots: ") && marker in it.second })
}

/** The family's plugins, faked: an economy, xRewind, xVanish and xTeleport's homes. */
class FakeHooks(var economy: Economy? = FakeEconomy()) : Hooks {
    var rewind: Rewind? = null
    val vanished: MutableSet<PlayerRef> = ConcurrentHashMap.newKeySet()

    @Volatile
    var homes: ((PlayerRef) -> List<PlotHome>)? = null

    override fun economy(): Economy? = economy
    override fun rewind(): Rewind? = rewind
    override fun vanished(viewer: PlayerRef, target: PlayerRef): Boolean = target in vanished
    override fun registerHomes(homes: (PlayerRef) -> List<PlotHome>): AutoCloseable {
        this.homes = homes
        return AutoCloseable { this.homes = null }
    }
}

/** One xPlots on a [FakePlotServer], its database the SQLite file in [folder]. */
class Harness(
    val folder: Path = Files.createTempDirectory("xplots"),
    config: String = "",
    val hooks: FakeHooks = FakeHooks(),
    val platform: TestPlatform = TestPlatform(),
    val server: FakePlotServer = FakePlotServer(),
) {
    private var text = config
    private val files = Files {
        val loaded = Configs.load(XPlotsConfig, text, TestYaml)
        Loaded((loaded as Outcome.Ok).value.config, Translations.of(XPlotsMessages.catalog, emptyMap()))
    }

    private val opener = object : Opener {
        override fun describe(config: Config): String = folder.toString()
        override fun open(config: Config): Outcome<Database, String> =
            when (val opened = JdbcDatabase.open(DatabaseSettings(DatabaseSettings.Type.SQLITE, config[XPlotsConfig.storage.tablePrefix]), folder.resolve("plots.db"), Drivers.serverOnly(javaClass.classLoader), platform.log, "Test")) {
                is Outcome.Ok -> Outcome.Ok(opened.value)
                is Outcome.Err -> Outcome.Err(opened.error.detail)
            }
    }

    val plugin: XPlotsPlugin = XPlotsPlugin(platform, server, files, server, server, null, opener, hooks, JvmDeflate)

    val console: FakeCommandSource = FakeCommandSource.console()

    val economy: FakeEconomy get() = hooks.economy as FakeEconomy

    fun start(): Harness {
        plugin.start(files.load())
        plugin.open()
        platform.until("the plots to be read") { plugin.opened.isDone }
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

    /** Makes a field: a 12×1×5 selection at y 64 as two 5×5 plots with a 2-block path, its blocks saved. */
    fun field(name: String = "box", x: Int = 200, z: Int = 200, size: Int = 5, path: Int = 2): co.xenastudios.xplots.model.Field {
        run("xplots select world $x 64 $z ${x + 11} 70 ${z + 4}")
        run("xplots field create $name size:$size path:$path")
        run("xplots confirm") { replies(console).any { it.startsWith("» Made field $name") || it.contains("Made field $name") } }
        flush()
        return plugin.plots.field(name)!!
    }

    /** Every change so far, written. */
    fun flush() {
        val db = plugin.database ?: return
        platform.await(db.session { Outcome.Ok(Unit) })
    }

    fun stop() {
        val stopped = plugin.stop()
        platform.until("the database to close") { stopped.isDone }
    }

    fun query(sql: String): List<List<Any?>> {
        java.sql.DriverManager.getConnection("jdbc:sqlite:" + folder.resolve("plots.db")).use { c ->
            c.createStatement().use { s ->
                s.executeQuery(sql).use { r ->
                    val out = ArrayList<List<Any?>>()
                    while (r.next()) out += (1..r.metaData.columnCount).map { r.getObject(it) }
                    return out
                }
            }
        }
    }
}
