package co.xenastudios.core.common.scheduler

import co.xenastudios.core.api.Location
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.WorldRef
import co.xenastudios.core.common.platform.Threading
import co.xenastudios.core.testing.FakePlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

class SchedulerTest {

    private val world = WorldRef.of("world")
    private val spawn = Location(world, 0.0, 64.0, 0.0)
    private val farAway = Location(world, 10_000.0, 64.0, 10_000.0)
    private val steve = Uuid.parse("8667ba71-b85a-4004-af54-457a9734eed7")

    @Test
    fun zeroDelayIsTheNextTickNeverRightAway() {
        val platform = FakePlatform()
        var ran = false
        platform.scheduler.global { ran = true }
        assertFalse(ran)
        platform.scheduler.advance(49.milliseconds)
        assertFalse(ran)
        platform.scheduler.advance(1.milliseconds)
        assertTrue(ran)
    }

    @Test
    fun delaysOnServerThreadsRoundUpToWholeTicks() {
        val platform = FakePlatform()
        val times = mutableListOf<Long>()
        platform.scheduler.later(Where.Global, 120.milliseconds) { times += platform.scheduler.time.inWholeMilliseconds }
        platform.scheduler.later(Where.Async, 120.milliseconds) { times += platform.scheduler.time.inWholeMilliseconds }
        platform.scheduler.advance(1.seconds)
        assertEquals(listOf(120L, 150L), times)
    }

    @Test
    fun repeatsUntilCancelled() {
        val platform = FakePlatform()
        var runs = 0
        val task = platform.scheduler.every(Where.Global, 1.seconds) { runs++ }
        platform.scheduler.advance(3.seconds)
        assertEquals(3, runs)
        task.cancel()
        assertTrue(task.isCancelled)
        platform.scheduler.advance(3.seconds)
        assertEquals(3, runs)
        assertEquals(0, platform.scheduler.pending)
    }

    @Test
    fun aTaskCanCancelItself() {
        val platform = FakePlatform()
        var runs = 0
        platform.scheduler.every(Where.Async, 100.milliseconds, delay = 0.milliseconds) { task ->
            if (++runs == 3) task.cancel()
        }
        platform.scheduler.advance(10.seconds)
        assertEquals(3, runs)
        assertEquals(0, platform.scheduler.pending)
    }

    @Test
    fun runsInOrderOfWhenDueThenWhenScheduled() {
        val platform = FakePlatform()
        val order = mutableListOf<String>()
        platform.scheduler.later(Where.Global, 100.milliseconds) { order += "b" }
        platform.scheduler.global { order += "a" }
        platform.scheduler.later(Where.Global, 100.milliseconds) { order += "c" }
        platform.scheduler.tick(2)
        assertEquals(listOf("a", "b", "c"), order)
    }

    @Test
    fun refusesNegativeDelaysAndEmptyPeriods() {
        val platform = FakePlatform()
        assertFailsWith<IllegalArgumentException> { platform.scheduler.later(Where.Global, (-1).milliseconds) {} }
        assertFailsWith<IllegalArgumentException> { platform.scheduler.every(Where.Global, 0.milliseconds) {} }
    }

    @Test
    fun onFoliaEachRegionAndTheGlobalThreadOwnOnlyTheirOwn() {
        val platform = FakePlatform(Threading.REGIONS)
        val scheduler = platform.scheduler
        scheduler.spawn(steve, spawn)
        val seen = mutableMapOf<String, Boolean>()
        scheduler.global {
            seen["global owns global"] = scheduler.owns(Where.Global)
            seen["global owns spawn"] = scheduler.owns(Where.At(spawn))
        }
        scheduler.at(spawn) {
            seen["spawn owns spawn"] = scheduler.owns(Where.At(spawn))
            seen["spawn owns its neighbour"] = scheduler.owns(Where.At(Location(world, 100.0, 64.0, 100.0)))
            seen["spawn owns far away"] = scheduler.owns(Where.At(farAway))
            seen["spawn owns steve there"] = scheduler.owns(Where.On(steve))
            seen["spawn owns global"] = scheduler.owns(Where.Global)
        }
        scheduler.on(steve) { seen["steve owns spawn"] = scheduler.owns(Where.At(spawn)) }
        scheduler.async { seen["async owns spawn"] = scheduler.owns(Where.At(spawn)) }
        scheduler.tick()
        assertEquals(
            mapOf(
                "global owns global" to true,
                "global owns spawn" to false,
                "spawn owns spawn" to true,
                "spawn owns its neighbour" to true,
                "spawn owns far away" to false,
                "spawn owns steve there" to true,
                "spawn owns global" to false,
                "steve owns spawn" to true,
                "async owns spawn" to false,
            ),
            seen,
        )
    }

    @Test
    fun anEntitysWorkFollowsIt() {
        val platform = FakePlatform(Threading.REGIONS)
        val scheduler = platform.scheduler
        scheduler.spawn(steve, spawn)
        scheduler.move(steve, farAway)
        var ownsFarAway = false
        scheduler.run(Where.on(PlayerRef.of(steve))) { ownsFarAway = scheduler.owns(Where.At(farAway)) }
        scheduler.tick()
        assertTrue(ownsFarAway)
    }

    @Test
    fun onBukkitTheServerThreadsAreOneThread() {
        val platform = FakePlatform(Threading.MAIN_THREAD)
        val scheduler = platform.scheduler
        scheduler.spawn(steve, spawn)
        val fromServer = mutableListOf<Boolean>()
        val fromAsync = mutableListOf<Boolean>()
        scheduler.at(farAway) {
            fromServer += scheduler.owns(Where.Global)
            fromServer += scheduler.owns(Where.At(spawn))
            fromServer += scheduler.owns(Where.On(steve))
            fromServer += scheduler.owns(Where.Async)
        }
        scheduler.async {
            fromAsync += scheduler.owns(Where.Global)
            fromAsync += scheduler.owns(Where.Async)
        }
        scheduler.tick()
        assertEquals(listOf(true, true, true, false), fromServer)
        assertEquals(listOf(false, true), fromAsync)
    }

    @Test
    fun onPumpkinEverythingIsOneThread() {
        val platform = FakePlatform(Threading.SINGLE)
        var owned = false
        platform.scheduler.async { owned = platform.scheduler.owns(Where.At(spawn)) && platform.scheduler.owns(Where.Global) }
        platform.scheduler.runPending()
        assertTrue(owned)
    }

    @Test
    fun workForAnEntityThatLeavesIsRetired() {
        val platform = FakePlatform()
        val scheduler = platform.scheduler
        scheduler.spawn(steve, spawn)
        var ran = false
        var retired = 0
        val task = scheduler.later(Where.On(steve), 1.seconds) { ran = true }
        scheduler.on(steve, retired = { retired++ }) { ran = true }
        scheduler.despawn(steve)
        scheduler.advance(2.seconds)
        assertFalse(ran)
        assertTrue(task.isCancelled)
        assertEquals(1, retired)
        // Work for an entity that's already gone is retired at once.
        val late = scheduler.on(steve, retired = { retired++ }) { ran = true }
        assertTrue(late.isCancelled)
        assertEquals(2, retired)
        assertEquals(0, scheduler.pending)
    }

    @Test
    fun nowRunsRightAwayOnlyWhereThisThreadOwnsIt() {
        val platform = FakePlatform()
        val scheduler = platform.scheduler
        var ranGlobal = false
        assertNull(scheduler.now(Where.Global) { ranGlobal = true })
        assertTrue(ranGlobal)
        var ranThere = false
        val task = scheduler.now(Where.At(spawn)) { ranThere = true }
        assertFalse(ranThere)
        assertFalse(task!!.isCancelled)
        scheduler.tick()
        assertTrue(ranThere)
    }

    @Test
    fun runAsActsFromAnotherThread() {
        val platform = FakePlatform()
        platform.scheduler.spawn(steve, spawn)
        assertTrue(platform.scheduler.runAs(Where.On(steve)) { platform.scheduler.owns(Where.At(spawn)) })
        assertFalse(platform.scheduler.owns(Where.At(spawn)))
    }

    @Test
    fun developmentBuildsThrowOnTheWrongThreadAndReleaseBuildsDont() {
        val dev = FakePlatform()
        dev.checkThread(Where.Global) { "Reloading" }
        val error = assertFailsWith<WrongThreadException> { dev.checkThread(Where.At(spawn)) { "Breaking a block" } }
        assertTrue(error.message!!.startsWith("Breaking a block must run on the thread that owns At("), error.message)

        val release = FakePlatform(development = false)
        var described = false
        release.checkThread(Where.At(spawn)) { "Breaking a block".also { described = true } }
        assertFalse(described)
    }
}
