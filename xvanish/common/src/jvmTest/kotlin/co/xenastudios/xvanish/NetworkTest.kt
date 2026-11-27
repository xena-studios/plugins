package co.xenastudios.xvanish

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.config.Configs
import co.xenastudios.core.common.db.Database
import co.xenastudios.core.common.db.DatabaseSettings
import co.xenastudios.core.common.db.DbError
import co.xenastudios.core.common.db.Dialect
import co.xenastudios.core.common.db.SqlSession
import co.xenastudios.core.common.db.Statement
import co.xenastudios.core.common.db.Sql
import co.xenastudios.core.common.i18n.Translations
import co.xenastudios.core.common.platform.Capability
import co.xenastudios.core.common.platform.Clock
import co.xenastudios.core.common.platform.Platform
import co.xenastudios.core.common.platform.Threading
import co.xenastudios.core.common.platform.readText
import co.xenastudios.core.common.platform.writeText
import co.xenastudios.core.common.scheduler.Scheduler
import co.xenastudios.core.common.scheduler.Task
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.jvm.db.Drivers
import co.xenastudios.core.jvm.db.JdbcDatabase
import co.xenastudios.core.testing.FakeCommandSource
import co.xenastudios.core.testing.MemoryStorage
import co.xenastudios.core.testing.RecordingLog
import co.xenastudios.core.testing.TestYaml
import co.xenastudios.core.testing.VirtualScheduler
import co.xenastudios.xvanish.config.XVanishConfig
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Network mode (decisions/2026-11-27-xvanish-1.md): two servers, a and b, each an xVanish on its
 * own fake platform (Folia's regions, virtual time), sharing one SQLite file through the change
 * feed as a network shares MySQL. Every database callback is handed to its platform's async
 * queue, so each test runs one thread at a time, as the server's threads would see it.
 */
class NetworkTest {
    private val folder: Path = Files.createTempDirectory("xvanish-network")
    private val servers = ArrayList<Server>()

    @AfterTest
    fun stop() {
        for (s in servers) s.stop()
        folder.toFile().deleteRecursively()
    }

    /** The fake platform behind one lock, so the database's threads can queue work while a test pumps it. */
    class LockedPlatform(val storage0: MemoryStorage) : Platform {
        val inner = VirtualScheduler(Threading.REGIONS)
        private val lock = Any()
        override val name = "Test"
        override val threading = Threading.REGIONS
        override val capabilities = setOf(Capability.SQL, Capability.SHARED_API)
        override val development = false
        override val storage get() = storage0
        override val log = RecordingLog()
        private val start = Instant.parse("2026-01-01T00:00:00Z")

        override val scheduler: Scheduler = object : Scheduler {
            override fun run(where: Where, delay: Duration, period: Duration?, retired: (() -> Unit)?, action: (Task) -> Unit): Task =
                synchronized(lock) { inner.run(where, delay, period, retired, action) }

            override fun owns(where: Where): Boolean = synchronized(lock) { inner.owns(where) }
        }

        override val clock: Clock = object : Clock {
            override fun now(): Instant = start + synchronized(lock) { inner.time }
            override fun elapsed(): Duration = synchronized(lock) { inner.time }
        }

        fun <T> locked(block: () -> T): T = synchronized(lock) { block() }

        fun advance(duration: Duration) = locked { inner.advance(duration) }

        /** Where the database settles: this platform's async queue, run as the test pumps it. */
        val callbacks = Executor { r -> scheduler.run(Where.Async) { r.run() } }
    }

    /**
     * The shared database, which a test can take away: while it's down, reads fail as an outage's
     * do, and writes wait, as the core's writer keeps them, until it's back.
     */
    class Flaky(private val inner: Database, private val callbacks: Executor) : Database {
        @Volatile
        var down = false
        private val held = ArrayList<Pair<List<Statement>, CompletableFuture<Outcome<Unit, DbError>>>>()

        override val dialect: Dialect get() = inner.dialect
        override val sql: Sql get() = inner.sql
        override val description: String get() = inner.description
        override val available: Boolean get() = !down

        private fun <T> hop(p: Pending<T>): Pending<T> = p.thenApplyAsync({ it }, callbacks)

        private fun <T> unreachable(): Pending<Outcome<T, DbError>> =
            CompletableFuture<Outcome<T, DbError>>().also { callbacks.execute { it.complete(Outcome.Err(DbError(DbError.Kind.UNAVAILABLE, "the test took it away"))) } }

        override fun write(statements: List<Statement>): Pending<Outcome<Unit, DbError>> = synchronized(held) {
            if (down) CompletableFuture<Outcome<Unit, DbError>>().also { held += statements to it } else hop(inner.write(statements))
        }

        override fun <T> transaction(work: (SqlSession) -> Outcome<T, DbError>) = if (down) unreachable() else hop(inner.transaction(work))
        override fun <T> session(work: (SqlSession) -> Outcome<T, DbError>) = if (down) unreachable() else hop(inner.session(work))
        override fun <T> read(work: (SqlSession) -> Outcome<T, DbError>) = if (down) unreachable() else hop(inner.read(work))
        override fun close(): Pending<Unit> = hop(inner.close())

        fun up() = synchronized(held) {
            down = false
            for ((statements, waiting) in held) hop(inner.write(statements)).thenAccept { waiting.complete(it) }
            held.clear()
        }
    }

    /** One server: its platform, players, plugin and its handle on the shared database. */
    inner class Server(val label: String, config: String = "", val storage: MemoryStorage = MemoryStorage(), network: Boolean = true) {
        val platform = LockedPlatform(storage)
        val server = FakeVanishServer(platform.inner)
        private val loaded = Loaded((Configs.load(XVanishConfig, NETWORK + config, TestYaml) as Outcome.Ok).value.config, Translations.of(XVanishMessages.catalog, emptyMap()))
        val plugin = XVanishPlugin(platform, server, { loaded }, server, server).also { it.networkOnAnyDatabase = true }
        var database: Flaky? = null

        init {
            servers += this
            platform.locked { platform.inner.runAs(Where.Global) { plugin.start(loaded) } }
            if (network) {
                val opened = JdbcDatabase.open(DatabaseSettings(DatabaseSettings.Type.SQLITE, "xvanish_"), folder.resolve("network.db"), Drivers.serverOnly(javaClass.classLoader), platform.log, "Test")
                val db = Flaky((opened as Outcome.Ok).value, platform.callbacks)
                database = db
                plugin.openNetwork(Outcome.Ok(db))
                until("$label's network to open") { plugin.network != null }
            }
            pump()
        }

        fun pump() = platform.advance(Duration.ZERO)

        fun until(what: String, done: () -> Boolean) {
            val deadline = System.nanoTime() + 15_000_000_000L
            while (true) {
                pump()
                if (done()) return
                check(System.nanoTime() < deadline) { "$what didn't happen within 15 seconds; $label's log: ${platform.log.lines}" }
                Thread.sleep(2)
            }
        }

        fun join(source: FakeCommandSource): Boolean = platform.locked { server.join(source).also { platform.inner.tick(2) } }

        fun quit(source: FakeCommandSource): Boolean = platform.locked { server.quit(source).also { platform.inner.tick(2) } }

        fun run(source: FakeCommandSource, line: String) {
            source.clear()
            platform.locked {
                val result = platform.inner.runAs(source.player?.let { Where.on(it) } ?: Where.Global) { plugin.dispatcher.dispatch(source, line) }
                plugin.texts.feedback.message(result, source)?.let(source::reply)
                platform.inner.tick(2)
            }
        }

        fun file(): String = storage.readText(Remembered.FILE).let { (it as Outcome.Ok).value.orEmpty() }

        fun stop() {
            val stopped = platform.locked { platform.inner.runAs(Where.Global) { plugin.stop() } }
            until("$label to stop") { stopped.isDone }
        }
    }

    // Each server's feed checks every second of its own virtual time.
    private fun everyoneUntil(what: String, done: () -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (!done()) {
            for (s in servers) s.platform.advance(1.seconds)
            check(System.nanoTime() < deadline) { "$what didn't happen within 15 seconds; logs: ${servers.map { it.platform.log.lines }}" }
            Thread.sleep(5)
        }
    }

    // A few of the feed's checks, for a test that something doesn't happen.
    private fun everyoneFor(checks: Int) {
        repeat(checks) {
            for (s in servers) s.platform.advance(1.seconds)
            Thread.sleep(20)
        }
        for (s in servers) s.pump()
    }

    private fun staff() = FakeCommandSource.player("Staff", "xvanish.vanish", "xvanish.list", "xvanish.see", "xvanish.level.2")
    private val alex = FakeCommandSource.player("Alex")
    private val mod = FakeCommandSource.player("Mod", "xvanish.see")
    private val who: PlayerRef = staff().player!!

    @Test
    fun aVanishOnOneServerReachesTheOther() {
        val a = Server("a")
        val b = Server("b")
        val staffOnA = staff()
        a.join(staffOnA)
        b.join(alex)
        b.join(mod)
        a.run(staffOnA, "vanish")
        everyoneUntil("b to hear Staff vanished") { b.plugin.isVanished(who) }
        // Seen from b, where they aren't: hidden from players, and from staff below their level.
        assertFalse(b.plugin.canSee(alex.player!!, who))
        assertFalse(b.plugin.canSee(mod.player!!, who), "Mod sees level 1; Staff vanished at level 2")
        assertTrue(who.toString() in b.file(), "b's vanished.txt follows the network: ${b.file()}")
        // /vanish list on b covers the network.
        val lister = FakeCommandSource.player("Lister", "xvanish.vanish", "xvanish.list")
        b.join(lister)
        b.run(lister, "vanish list")
        assertEquals(2, lister.replies.size, "${lister.replies}")
        assertTrue(lister.replies[0].endsWith("Vanished (1):") && lister.replies[1] == " Staff not on this server", "${lister.replies}")
        val staffOnB = staff()
        // Shown again on a: b hears, and its file forgets them.
        a.run(staffOnA, "vanish")
        everyoneUntil("b to hear Staff showed") { !b.plugin.isVanished(who) }
        assertFalse(who.toString() in b.file(), "b's vanished.txt forgot them: ${b.file()}")
        assertTrue(b.plugin.canSee(alex.player!!, who))
        // So a later join on b isn't vanished by a stale file.
        assertFalse(b.join(staffOnB))
    }

    @Test
    fun aVanishedPlayerSwitchingServersStaysHidden() {
        val a = Server("a")
        val b = Server("b")
        val staffOnA = staff()
        a.join(staffOnA)
        b.join(alex)
        b.join(mod)
        a.run(staffOnA, "vanish")
        everyoneUntil("b to hear Staff vanished") { b.plugin.isVanished(who) }
        // The join on b reads memory only: the database is away as they arrive.
        b.database!!.down = true
        val staffOnB = staff()
        assertTrue(b.join(staffOnB), "joined b vanished")
        a.quit(staffOnA)
        b.database!!.up()
        everyoneFor(3)
        assertTrue(b.plugin.isVanished(who))
        assertFalse(b.server.sees(alex, staffOnB), "Alex on b doesn't see them")
        assertFalse(b.server.sees(mod, staffOnB), "Mod sees level 1 only")
        assertTrue(a.plugin.isVanished(who), "a still has them vanished on the network")
    }

    @Test
    fun aSwitchWithRememberOffDoesntShowThem() {
        val a = Server("a", "vanish:\n  remember: false\n")
        val b = Server("b", "vanish:\n  remember: false\n")
        val staffOnA = staff()
        a.join(staffOnA)
        b.join(alex)
        a.run(staffOnA, "vanish")
        everyoneUntil("b to hear Staff vanished") { b.plugin.isVanished(who) }
        // The proxy connects them to b before it lets go of a.
        val staffOnB = staff()
        assertTrue(b.join(staffOnB))
        a.quit(staffOnA)
        everyoneFor((Network.GRACE.inWholeSeconds + 3).toInt())
        assertTrue(b.plugin.isVanished(who), "a's quit didn't show them on b")
        assertFalse(b.server.sees(alex, staffOnB))
        // Leaving the network: shown everywhere once the grace is over, as remember: false says.
        b.quit(staffOnB)
        everyoneUntil("a to hear Staff left the network") { !a.plugin.isVanished(who) }
        assertFalse(a.join(staff()), "a relog isn't vanished with remember: false")
    }

    @Test
    fun theDatabaseAwayFailsSafe() {
        val a = Server("a")
        val bStorage = MemoryStorage()
        val b = Server("b", storage = bStorage)
        val staffOnA = staff()
        a.join(staffOnA)
        a.run(staffOnA, "vanish")
        everyoneUntil("b to hear Staff vanished") { b.plugin.isVanished(who) }
        a.database!!.down = true
        b.database!!.down = true
        // Shown on a while the database is away: b hasn't heard, so they stay vanished there.
        a.run(staffOnA, "vanish")
        everyoneFor(3)
        assertTrue(b.plugin.isVanished(who), "b keeps them vanished")
        assertTrue(b.join(staff()), "a join on b is vanished")
        // A restart of b that can't reach the database at all: vanished.txt still has them.
        b.stop()
        servers -= b
        val again = Server("b again", storage = bStorage, network = false)
        again.plugin.openNetwork(Outcome.Err("Couldn't open MySQL for network mode: the test took it away."))
        assertTrue(again.join(staff()), "vanished from b's file")
        // Back: a's write lands, and the network shows them.
        a.database!!.up()
        val c = Server("c")
        everyoneUntil("c to read Staff shown") { c.plugin.network != null && !c.plugin.isVanished(who) }
        assertTrue(a.platform.log.lines.none { "Couldn't tell the network" in it.message }, "the write waited, not failed: ${a.platform.log.lines}")
    }

    @Test
    fun aServerThatWasDownForgetsWhoShowedMeanwhile() {
        val aStorage = MemoryStorage()
        val a = Server("a", storage = aStorage)
        val b = Server("b")
        val staffOnA = staff()
        a.join(staffOnA)
        a.run(staffOnA, "vanish")
        everyoneUntil("b to hear Staff vanished") { b.plugin.isVanished(who) }
        a.quit(staffOnA)
        a.stop()
        servers -= a
        assertTrue(who.toString() in aStorage.readText(Remembered.FILE).let { (it as Outcome.Ok).value.orEmpty() })
        // On b while a is down, and shown there.
        val staffOnB = staff()
        assertTrue(b.join(staffOnB))
        b.run(staffOnB, "vanish")
        assertFalse(b.plugin.isVanished(who))
        b.until("b's write") { b.plugin.network!!.let { !it.isVanished(who) } }
        everyoneFor(2)
        // a again: the table says they showed, so its old vanished.txt is out of date.
        val back = Server("a again", storage = aStorage)
        assertFalse(back.plugin.isVanished(who))
        assertFalse(who.toString() in back.file(), back.file())
        assertFalse(back.join(staff()), "not vanished by a's old file")
    }

    @Test
    fun aFileFromBeforeNetworkModeReachesTheNetwork() {
        val aStorage = MemoryStorage()
        aStorage.writeText(Remembered.FILE, "$who Staff\n")
        Server("a", storage = aStorage)
        val b = Server("b")
        everyoneUntil("b to hear of a's vanished.txt") { b.plugin.isVanished(who) }
    }

    @Test
    fun forgetOnOneServerReachesEveryFile() {
        val a = Server("a")
        val b = Server("b")
        val staffOnA = staff()
        a.join(staffOnA)
        a.run(staffOnA, "vanish")
        everyoneUntil("b to hear Staff vanished") { who.toString() in b.file() }
        a.quit(staffOnA)
        val forgot = b.plugin.network!!.forget(who)
        b.until("the forget") { forgot.isDone }
        everyoneUntil("a to forget Staff") { !a.plugin.isVanished(who) && who.toString() !in a.file() }
        assertFalse(a.join(staff()))
    }

    private companion object {
        const val NETWORK = "network:\n  enabled: true\n  check-every: 1\n"
    }
}
