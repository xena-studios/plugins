package co.xenastudios.xlimbo

import co.xenastudios.core.api.event.EventListener
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.command.CommandResult
import co.xenastudios.core.common.config.Config
import co.xenastudios.core.common.config.Configs
import co.xenastudios.core.common.i18n.Translations
import co.xenastudios.core.common.platform.Threading
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.testing.FakeCommandSource
import co.xenastudios.core.testing.FakePlatform
import co.xenastudios.core.testing.TestYaml
import co.xenastudios.core.testing.settled
import co.xenastudios.xlimbo.api.EnterReason
import co.xenastudios.xlimbo.api.ReturnCause
import co.xenastudios.xlimbo.api.ReturnRoute
import co.xenastudios.xlimbo.api.XLimbo
import co.xenastudios.xlimbo.api.event.EnteredLimboEvent
import co.xenastudios.xlimbo.api.event.MainServerStatusEvent
import co.xenastudios.xlimbo.api.event.PreEnterLimboEvent
import co.xenastudios.xlimbo.api.event.PreReturnEvent
import co.xenastudios.xlimbo.api.event.ReturnedEvent
import co.xenastudios.xlimbo.config.XLimboConfig
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** xLimbo on the fake platform (Paper's one thread, virtual time): /join, the queue, server-check, the teleport route, the API and the hooks. */
class XLimboTest {
    private val platform = FakePlatform(Threading.MAIN_THREAD)
    private val server = FakeLimboServer(platform.scheduler)
    private var config: Config = XLimboConfig.defaults

    @kotlin.concurrent.Volatile
    private var mainUp = false
    private val plugin = XLimboPlugin(platform, server, { loaded() }, server, server, { _, _ -> mainUp })
    private val alex = FakeCommandSource.player("Alex", "xlimbo.join")
    private val steve = FakeCommandSource.player("Steve", "xlimbo.join")
    private val sam = FakeCommandSource.player("Sam", "xlimbo.join")
    private val staff = FakeCommandSource.player("Staff", "xlimbo.join", "xlimbo.list", "xlimbo.send", "xlimbo.back", "xlimbo.admin")
    private val console = FakeCommandSource.console()

    private fun loaded() = Loaded(config, Translations.of(XLimboMessages.catalog, emptyMap()))

    init {
        server.plugin = plugin
        platform.scheduler.runAs(Where.Global) { plugin.start(loaded()) }
        platform.scheduler.runPending()
    }

    @AfterTest
    fun stop() {
        platform.scheduler.runAs(Where.Global) { plugin.stop() }
        platform.scheduler.runPending()
    }

    private fun run(source: FakeCommandSource, line: String): CommandResult {
        source.clear()
        val result = platform.scheduler.runAs(Where.Global) { plugin.dispatcher.dispatch(source, line) }
        plugin.texts.feedback.message(result, source)?.let(source::reply)
        platform.scheduler.tick(1)
        return result
    }

    private fun reconfigure(edit: String) {
        config = (Configs.load(XLimboConfig, edit, TestYaml) as Outcome.Ok).value.config
        platform.scheduler.runAs(Where.Global) { plugin.apply(loaded()) }
        platform.scheduler.tick(1)
    }

    @Test
    fun theDefaultsTurnOnEveryProtectionButTheBuildGuard() {
        assertEquals(XLimbo.api(), plugin)
        val on = server.listening.keys
        assertTrue(Switch.ARRIVALS in on && Switch.HIDE_OTHERS in on && Switch.FLOOR in on, on.toString())
        assertTrue("protect.cleanup.300" in plugin.modules.active, plugin.modules.active.toString())
        assertFalse(Switch.BUILD_GUARD in on)
        assertTrue(server.syncs >= 1, "the world is synced as it starts")
        reconfigure("protections:\n  floor: false\n  build-guard:\n    enabled: true\n  entities:\n    cleanup-interval-seconds: 0\nplayer:\n  hide-others: false\n")
        assertFalse(Switch.FLOOR in server.listening || Switch.HIDE_OTHERS in server.listening)
        assertTrue(plugin.modules.active.none { it.startsWith("protect.cleanup") }, plugin.modules.active.toString())
        assertTrue(Switch.BUILD_GUARD in server.listening)
        assertEquals(ReturnRoute.PROXY, plugin.route())
    }

    @Test
    fun anEmptyLimboSchedulesNothingAndAnArrivalStartsItsTimersAgain() {
        // Every timer on: the action bar (default), auto-join's queue, the sweep every second.
        reconfigure("auto-join:\n  enabled: true\n  delay-seconds: 60\nprotections:\n  entities:\n    cleanup-interval-seconds: 1\n")
        platform.scheduler.advance(10.seconds)
        assertEquals(0, platform.scheduler.pending, "nothing runs while nobody is in limbo")
        assertEquals(0, server.sweeps)
        server.join(alex)
        platform.scheduler.advance(3.seconds)
        assertTrue(server.bars[alex.player!!].orEmpty().isNotEmpty(), "the action bar runs while someone's here")
        assertTrue(server.sweeps >= 2, "the sweep runs while someone's here: ${server.sweeps}")
        // A player who isn't in limbo (the teleport route's main world) doesn't keep it going.
        server.join(steve, intoLimbo = false)
        server.quit(alex)
        platform.scheduler.advance(3.seconds)
        val sweeps = server.sweeps
        assertEquals(0, platform.scheduler.pending, "each timer stopped itself once limbo was empty")
        platform.scheduler.advance(30.seconds)
        assertEquals(sweeps, server.sweeps)
        // Back again: everything starts, and auto-join's delay still counts from the arrival.
        server.join(sam)
        platform.scheduler.advance(59.seconds)
        assertTrue(server.connected.isEmpty())
        platform.scheduler.advance(2.seconds)
        assertEquals(listOf(sam.player!! to "main"), server.connected)
    }

    @Test
    fun aReloadWithPlayersInLimboKeepsItsTimersRunning() {
        server.join(alex)
        reconfigure("action-bar:\n  interval-ticks: 20\n")
        server.bars.clear()
        platform.scheduler.advance(3.seconds)
        assertTrue(server.bars[alex.player!!].orEmpty().size >= 2, server.bars.toString())
    }

    @Test
    fun arrivingPlayersAreWelcomedAndJoinSendsThemThroughTheProxyWithACooldown() {
        server.join(alex)
        // The welcome comes a moment later, in the client's language.
        assertTrue(server.messages(alex).isEmpty())
        platform.scheduler.advance(1.seconds)
        assertTrue(server.messages(alex).any { "waiting here" in it }, server.messages(alex).toString())
        assertTrue(alex.player in server.equipped)
        run(alex, "join")
        assertEquals(listOf(alex.player!! to "main"), server.connected)
        assertTrue(alex.replies.any { "Connecting you to main" in it }, alex.replies.toString())
        run(alex, "server")
        assertEquals(1, server.connected.size, "the alias shares the cooldown")
        assertTrue(alex.replies.any { "Wait 3s" in it }, alex.replies.toString())
        platform.scheduler.advance(3.seconds)
        run(alex, "lobby")
        assertEquals(2, server.connected.size)
        assertTrue(run(console, "join") is CommandResult.PlayersOnly)
    }

    @Test
    fun aHoldOrACancelledEventKeepsAPlayerWithoutUsingUpTheirCooldown() {
        server.join(alex)
        var hold = true
        plugin.registerHold("Login") { hold }
        run(alex, "join")
        assertTrue(server.connected.isEmpty())
        hold = false
        val cancel = EventListener<PreReturnEvent> { it.isCancelled = true }
        val subscription = plugin.events.subscribe(PreReturnEvent.TYPE, listener = cancel)
        run(alex, "join")
        assertTrue(server.connected.isEmpty())
        subscription.cancel()
        run(alex, "join")
        assertEquals(1, server.connected.size, "no cooldown was used by the refused tries")
        plugin.unregisterAll("Login")
    }

    @Test
    fun autoJoinSendsPlayersBackAfterItsDelayAFewASecond() {
        reconfigure("auto-join:\n  enabled: true\n  delay-seconds: 10\nqueue:\n  per-second: 1\n")
        server.join(alex)
        server.join(steve)
        server.join(sam)
        // The queue's tick starts with the first arrival: the first goes back at 10 s, one more each second.
        platform.scheduler.advance(9500.milliseconds)
        assertTrue(server.connected.isEmpty())
        platform.scheduler.advance(1.seconds)
        assertEquals(1, server.connected.size)
        assertTrue(server.messages(alex).any { "Sending you back" in it })
        // Two still waiting, each seeing their place among them.
        assertEquals(2, plugin.queueSize())
        platform.scheduler.advance(2.seconds)
        assertTrue(server.bars.values.flatten().any { "#1 of" in it }, server.bars.toString())
        platform.scheduler.advance(3.seconds)
        assertEquals(3, server.connected.size)
        assertEquals(setOf("main"), server.connected.map { it.second }.toSet())
    }

    @Test
    fun aPlayerTheMainServerKeepsRefusingIsTriedThreeTimesInFiveMinutes() {
        reconfigure("auto-join:\n  enabled: true\n  delay-seconds: 1\n")
        server.join(alex)
        repeat(5) {
            platform.scheduler.advance(20.seconds)
            // Refused: the proxy sends them straight back.
            if (alex.player !in server.inLimbo) server.join(alex)
        }
        assertEquals(3, server.connected.size, "tried three times, then left alone")
        val entered = ArrayList<EnteredLimboEvent>()
        plugin.events.subscribe(EnteredLimboEvent.TYPE, listener = EventListener { entered += it })
        // /xlimbo forget's part: the tries are gone, so the next arrival isn't a refusal.
        assertEquals(1L, platform.scheduler.runAs(Where.Global) { plugin.forget(alex.player!!) })
        server.quit(alex)
        server.join(alex)
        assertEquals(EnterReason.UNKNOWN, entered.last().reason)
    }

    @Test
    fun serverCheckSendsEveryoneBackWhenTheMainServerComesUp() {
        val changes = ArrayList<Boolean>()
        plugin.events.subscribe(MainServerStatusEvent.TYPE, listener = EventListener { changes += it.up })
        reconfigure("server-check:\n  enabled: true\n  interval-seconds: 1\nqueue:\n  per-second: 0\n")
        server.join(alex)
        server.join(steve)
        platform.scheduler.advance(3.seconds)
        assertFalse(plugin.isMainServerUp())
        assertTrue(server.connected.isEmpty())
        assertTrue(changes.isEmpty(), "the first answer isn't a change")
        val entered = ArrayList<EnteredLimboEvent>()
        plugin.events.subscribe(EnteredLimboEvent.TYPE, listener = EventListener { entered += it })
        server.join(sam)
        assertEquals(EnterReason.MAIN_SERVER_DOWN, entered.single().reason)
        mainUp = true
        platform.scheduler.advance(3.seconds)
        assertEquals(listOf(true), changes)
        assertTrue(plugin.isMainServerUp())
        assertEquals(3, server.connected.size, "everyone at once with per-second 0")
        assertTrue(server.messages(alex).any { "back up" in it })
    }

    @Test
    fun vanishedPlayersArentCountedOrListedForThoseWhoCantSeeThem() {
        reconfigure("auto-join:\n  enabled: true\n  delay-seconds: 1\nqueue:\n  per-second: 1\n")
        server.staff += staff.player!!
        server.vanished += staff.player!!
        // Held, so the line stays put while the bars are read.
        plugin.registerHold("Test") { true }
        server.join(staff)
        server.join(alex)
        server.join(steve)
        platform.scheduler.advance(3.seconds)
        plugin.unregisterAll("Test")
        run(staff, "xlimbo list")
        assertTrue(staff.replies.any { "In limbo (3)" in it }, staff.replies.toString())
        run(alex, "xlimbo")
        assertTrue(alex.replies.any { "2 in limbo" in it }, alex.replies.toString())
        assertEquals(2, plugin.visibleCount())
        assertEquals(3, plugin.playersInLimbo().size)
        // Nobody sees the vanished player counted in the line, and they see their own place among the rest.
        plugin.registerHold("Test") { true }
        val line = platform.scheduler.runAs(Where.Global) {
            for (p in listOf(staff, alex, steve)) plugin.returns!!.enqueue(p.player!!)
            plugin.returns!!.shown
        }
        assertEquals(2, line.total(alex.player!!, vanished = false))
        assertEquals(setOf(1, 2), setOf(line.places[alex.player!!], line.places[steve.player!!]))
        assertTrue(line.places.getValue(staff.player!!) in 1..3)
        assertEquals(3, line.total(staff.player!!, vanished = true))
        assertEquals(3, plugin.queueSize(), "the API counts everyone")
        plugin.unregisterAll("Test")
    }

    @Test
    fun priorityPutsSomePlayersAheadInTheLine() {
        reconfigure("auto-join:\n  enabled: true\n  delay-seconds: 1\nqueue:\n  per-second: 1\n")
        plugin.registerPriority("Ranks") { if (it == sam.player) 10 else 0 }
        server.join(alex)
        server.join(steve)
        server.join(sam)
        platform.scheduler.advance(2.seconds)
        assertEquals(sam.player, server.connected.first().first)
    }

    @Test
    fun theTeleportRouteSendsPlayersIntoLimboAndBackOnThisServer() {
        reconfigure("send-back:\n  via: teleport\n  destination: spawn\n")
        assertEquals(ReturnRoute.TELEPORT, plugin.route())
        server.join(alex, intoLimbo = false)
        server.join(staff, intoLimbo = false)
        run(alex, "join")
        assertTrue(alex.replies.any { "not in limbo" in it }, alex.replies.toString())
        val pre = ArrayList<PreEnterLimboEvent>()
        plugin.events.subscribe(PreEnterLimboEvent.TYPE, listener = EventListener { pre += it })
        run(staff, "xlimbo send Alex")
        assertTrue(staff.replies.any { "Sent Alex to limbo" in it }, staff.replies.toString())
        assertTrue(alex.player in server.inLimbo)
        assertEquals(1, pre.size)
        platform.scheduler.advance(1.seconds)
        assertTrue(server.messages(alex).any { "in limbo for now" in it })
        run(staff, "xlimbo send Alex")
        assertTrue(staff.replies.any { "already in limbo" in it })
        val returned = ArrayList<ReturnedEvent>()
        plugin.events.subscribe(ReturnedEvent.TYPE, listener = EventListener { returned += it })
        run(alex, "join")
        assertEquals(listOf(alex.player!! to "spawn"), server.teleported)
        assertTrue(server.connected.isEmpty())
        assertEquals(ReturnRoute.TELEPORT, returned.single().route)
        // A teleport that can't happen says so.
        run(staff, "xlimbo send Alex")
        server.teleportWorks = false
        run(staff, "xlimbo back Alex")
        assertTrue(server.messages(alex).any { "couldn't be sent back" in it }, server.messages(alex).toString())
    }

    @Test
    fun staffSendPlayersBackOneOrAll() {
        server.join(alex)
        server.join(steve)
        run(staff, "xlimbo back Alex")
        assertTrue(staff.replies.any { "Sending Alex back" in it }, staff.replies.toString())
        assertEquals(listOf(alex.player!! to "main"), server.connected)
        run(staff, "xlimbo back all")
        assertTrue(staff.replies.any { "everyone in limbo back" in it })
        assertTrue(server.connected.any { it.first == steve.player })
        assertTrue(run(alex, "xlimbo back Steve") is CommandResult.NoPermission)
        val causes = ArrayList<ReturnCause>()
        plugin.events.subscribe(PreReturnEvent.TYPE, listener = EventListener { causes += it.cause })
        assertTrue(plugin.sendBack(steve.player!!).settled())
        assertEquals(listOf(ReturnCause.PLUGIN), causes)
    }

    @Test
    fun aReloadKeepsWhoHasBeenTriedAndRestartsTheCheckOnlyWhenItChanges() {
        reconfigure("auto-join:\n  enabled: true\n  delay-seconds: 1\n")
        val first = plugin.returns
        reconfigure("auto-join:\n  enabled: true\n  delay-seconds: 1\naction-bar:\n  enabled: false\n")
        assertTrue(first === plugin.returns, "the queue kept running")
        reconfigure("server-check:\n  enabled: true\n")
        assertFalse(first === plugin.returns)
        reconfigure("join-command:\n  enabled: false\n")
        assertTrue(run(alex, "join") is CommandResult.Unknown)
    }
}
