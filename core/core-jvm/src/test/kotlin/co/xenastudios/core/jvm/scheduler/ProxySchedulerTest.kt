package co.xenastudios.core.jvm.scheduler

import co.xenastudios.core.api.Location
import co.xenastudios.core.api.WorldRef
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.testing.RecordingLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

class ProxySchedulerTest {
    private val log = RecordingLog()
    private val online = ConcurrentHashMap.newKeySet<Uuid>()
    private val scheduler = ProxyScheduler("test", log) { it in online }

    @AfterTest
    fun close() {
        scheduler.close()
    }

    private fun await(latch: CountDownLatch) = assertTrue(latch.await(5, TimeUnit.SECONDS), "timed out")

    @Test
    fun globalWorkRunsOnThePluginsOneThreadAndAsyncOffIt() {
        val done = CountDownLatch(2)
        val threads = ConcurrentHashMap.newKeySet<Thread>()
        var ownsGlobal = false
        var asyncOwnsGlobal = true
        repeat(20) { scheduler.run(Where.Global) { threads += Thread.currentThread() } }
        scheduler.run(Where.Global) {
            ownsGlobal = scheduler.owns(Where.Global) && !scheduler.owns(Where.Async)
            done.countDown()
        }
        scheduler.run(Where.Async) {
            asyncOwnsGlobal = scheduler.owns(Where.Global)
            done.countDown()
        }
        await(done)
        assertEquals(1, threads.size)
        assertTrue(ownsGlobal)
        assertFalse(asyncOwnsGlobal)
        assertFalse(scheduler.owns(Where.Global), "the test's thread isn't the plugin's")
    }

    @Test
    fun aPlayersWorkIsRetiredOnceTheyLeave() {
        val steve = Uuid.random()
        val retired = CountDownLatch(2)
        val ran = AtomicInteger()
        // Not on the proxy: retired at once.
        scheduler.run(Where.On(steve), retired = { retired.countDown() }) { ran.incrementAndGet() }
        online += steve
        val task = scheduler.run(Where.On(steve), delay = 50.milliseconds, period = 20.milliseconds, retired = { retired.countDown() }) {
            if (ran.incrementAndGet() == 3) online -= steve
        }
        await(retired)
        assertEquals(3, ran.get())
        assertTrue(task.isCancelled)
    }

    @Test
    fun aProxyHasNoPlaces() {
        assertFailsWith<IllegalArgumentException> { scheduler.run(Where.At(Location(WorldRef.of("world"), 0.0, 0.0, 0.0))) {} }
    }

    @Test
    fun aTaskThatThrowsIsLoggedAndARepeatingOneGoesOn() {
        val runs = CountDownLatch(3)
        scheduler.run(Where.Global, period = 10.milliseconds) {
            runs.countDown()
            error("boom")
        }
        await(runs)
        assertTrue(log.errors.isNotEmpty())
    }

    @Test
    fun aCancelledTaskDoesntRun() {
        val ran = AtomicInteger()
        val task = scheduler.run(Where.Global, delay = 100.milliseconds) { ran.incrementAndGet() }
        task.cancel()
        Thread.sleep(250)
        assertEquals(0, ran.get())
        assertTrue(task.isCancelled)
    }
}
