package co.xenastudios.core.bukkit

import co.xenastudios.core.jvm.scheduler.AsyncExecutor
import co.xenastudios.core.api.Location
import co.xenastudios.core.api.WorldRef
import co.xenastudios.core.bukkit.modern.Ticks
import co.xenastudios.core.common.scheduler.Task
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.testing.RecordingLog
import org.bukkit.entity.Entity
import org.bukkit.scheduler.BukkitScheduler
import org.bukkit.scheduler.BukkitTask
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

class MainThreadSchedulerTest {

    /** What was handed to Bukkit's scheduler: run it with [tick]. */
    private class Scheduled(val runnable: Runnable, val delay: Long, val period: Long?) {
        var cancelled = false
    }

    private val scheduled = mutableListOf<Scheduled>()
    private val log = RecordingLog()
    private val present = mutableSetOf<Uuid>()
    private val async = AsyncExecutor("XSample", log)
    private lateinit var scheduler: MainThreadScheduler

    @BeforeTest
    fun setUp() {
        FakeServer.reset()
        FakeServer.scheduler = fake<BukkitScheduler>(
            "BukkitScheduler",
            mapOf(
                "runTaskLater" to { args -> handle(Scheduled(args[1] as Runnable, args[2] as Long, null)) },
                "runTaskTimer" to { args -> handle(Scheduled(args[1] as Runnable, args[2] as Long, args[3] as Long)) },
                "cancelTasks" to { _ -> scheduled.forEach { it.cancelled = true } },
            ),
        )
        val entity = fake<Entity>("Entity", emptyMap())
        scheduler = MainThreadScheduler(FakeServer.plugin(), { uuid -> if (uuid in present) entity else null }, async)
    }

    @AfterTest
    fun tearDown() {
        async.close()
    }

    private fun handle(task: Scheduled): BukkitTask {
        scheduled += task
        return fake("BukkitTask", mapOf("cancel" to { _ -> task.cancelled = true }))
    }

    // Runs what Bukkit would run on the next tick.
    private fun tick() {
        for (task in scheduled.toList()) if (!task.cancelled) task.runnable.run()
    }

    @Test
    fun delaysAreWholeTicksRoundedUpAndAtLeastOne() {
        assertEquals(1, Ticks.of(Duration.ZERO))
        assertEquals(1, Ticks.of(50.milliseconds))
        assertEquals(2, Ticks.of(51.milliseconds))
        assertEquals(20, Ticks.of(1.seconds))
        assertTrue(Ticks.of(Duration.INFINITE) > 0)

        scheduler.run(Where.Global, delay = 120.milliseconds) {}
        scheduler.run(Where.Global, delay = 1.seconds, period = 75.milliseconds) {}
        assertEquals(listOf(3L to null, 20L to 2L), scheduled.map { it.delay to it.period })
    }

    @Test
    fun globalPlaceAndEntityWorkRunOnTheMainThread() {
        val ran = mutableListOf<String>()
        val alive = Uuid.random()
        present += alive
        scheduler.run(Where.Global) { ran += "global" }
        scheduler.run(Where.At(Location(WorldRef.of("world"), 1.0, 2.0, 3.0))) { ran += "at" }
        scheduler.run(Where.On(alive)) { ran += "on" }
        tick()
        assertEquals(listOf("global", "at", "on"), ran)
        assertTrue(scheduler.owns(Where.Global))
        assertFalse(scheduler.owns(Where.Async))
    }

    @Test
    fun workForAnEntityThatsGoneIsRetiredOnce() {
        val mob = Uuid.random()
        present += mob
        val runs = AtomicInteger()
        var retired = 0
        val task = scheduler.run(Where.On(mob), period = 50.milliseconds, retired = { retired++ }) { runs.incrementAndGet() }
        tick()
        present -= mob
        tick()
        tick()
        assertEquals(1, runs.get())
        assertEquals(1, retired)
        assertTrue(task.isCancelled)
        assertTrue(scheduled.single().cancelled)
    }

    @Test
    fun aCancelledTaskNeverRunsAndCancelsBukkitsToo() {
        var ran = false
        val task = scheduler.run(Where.Global) { ran = true }
        task.cancel()
        tick()
        assertFalse(ran)
        assertTrue(scheduled.single().cancelled)
    }

    @Test
    fun aTaskCanCancelItself() {
        var runs = 0
        scheduler.run(Where.Global, period = 50.milliseconds) { task: Task -> if (++runs == 2) task.cancel() }
        repeat(4) { tick() }
        assertEquals(2, runs)
    }

    @Test
    fun asyncWorkRunsOffTheMainThreadAfterItsExactDelay() {
        val done = CountDownLatch(1)
        var owned = false
        var mainThread = true
        val start = TimeSource.Monotonic.markNow()
        var waited = Duration.ZERO
        scheduler.run(Where.Async, delay = 30.milliseconds) {
            waited = start.elapsedNow()
            owned = scheduler.owns(Where.Async) && !scheduler.owns(Where.Global)
            mainThread = Thread.currentThread() === FakeServer.primaryThread
            done.countDown()
        }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertTrue(waited >= 30.milliseconds, "waited $waited")
        assertTrue(owned)
        assertFalse(mainThread)
    }

    @Test
    fun repeatingAsyncWorkStopsWhenCancelled() {
        val runs = AtomicInteger()
        val third = CountDownLatch(3)
        val task = scheduler.run(Where.Async, period = 5.milliseconds) {
            runs.incrementAndGet()
            third.countDown()
        }
        assertTrue(third.await(5, TimeUnit.SECONDS))
        task.cancel()
        val atCancel = runs.get()
        // A run already under way when it was cancelled may still finish (it counts first,
        // whenever its thread gets to it); none starts after.
        Thread.sleep(100)
        assertTrue(runs.get() <= atCancel + 1, "ran ${runs.get() - atCancel} times after it was cancelled")
    }

    @Test
    fun asyncWorkThatThrowsIsLogged() {
        val done = CountDownLatch(1)
        scheduler.run(Where.Async) {
            try {
                throw IllegalStateException("boom")
            } finally {
                done.countDown()
            }
        }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        // Logged after the task's own finally block, on the worker: wait for it, not a fixed time.
        val deadline = System.nanoTime() + 15_000_000_000L
        while (log.errors.isEmpty() && System.nanoTime() < deadline) Thread.sleep(2)
        assertEquals(listOf("An async task failed"), log.errors)
    }

    @Test
    fun afterCloseNothingRuns() {
        scheduler.run(Where.Global) {}
        scheduler.close()
        assertTrue(scheduled.single().cancelled)
        assertTrue(scheduler.run(Where.Global) {}.isCancelled)
        assertTrue(scheduler.run(Where.Async) {}.isCancelled)
    }

    @Test
    fun aPeriodMustBePositiveAndADelayNotNegative() {
        assertFailsWith<IllegalArgumentException> { scheduler.run(Where.Global, period = Duration.ZERO) {} }
        assertFailsWith<IllegalArgumentException> { scheduler.run(Where.Global, delay = (-1).milliseconds) {} }
    }
}
