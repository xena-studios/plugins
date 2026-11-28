package co.xenastudios.core.neoforge

import co.xenastudios.core.api.Location
import co.xenastudios.core.api.WorldRef
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.testing.RecordingLog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

class TickSchedulerTest {
    private val log = RecordingLog()
    private val entities = mutableSetOf<Uuid>()
    private var serverThread: Thread = Thread.currentThread()
    private val scheduler = TickScheduler(log, { Thread.currentThread() === serverThread }, { it in entities }, "test async")
    private val ran = mutableListOf<String>()

    @AfterTest
    fun close() {
        scheduler.close(1.seconds)
    }

    @Test
    fun `a delay of zero is the next tick, never now`() {
        scheduler.run(Where.Global) { ran += "next" }
        assertEquals(emptyList(), ran)
        scheduler.tick()
        assertEquals(listOf("next"), ran)
    }

    @Test
    fun `delays round up to whole ticks`() {
        assertEquals(1, TickScheduler.ticks(Duration.ZERO))
        assertEquals(1, TickScheduler.ticks(50.milliseconds))
        assertEquals(2, TickScheduler.ticks(51.milliseconds))
        assertEquals(20, TickScheduler.ticks(1.seconds))
        scheduler.run(Where.Global, delay = 120.milliseconds) { ran += "third" }
        repeat(2) { scheduler.tick() }
        assertEquals(emptyList(), ran)
        scheduler.tick()
        assertEquals(listOf("third"), ran)
    }

    @Test
    fun `work due on one tick runs in the order it was scheduled`() {
        scheduler.run(Where.Global, delay = 100.milliseconds) { ran += "b" }
        scheduler.run(Where.At(Location(WorldRef.of("minecraft:overworld"), 0.0, 0.0, 0.0))) { ran += "a" }
        scheduler.run(Where.Global, delay = 100.milliseconds) { ran += "c" }
        repeat(2) { scheduler.tick() }
        assertEquals(listOf("a", "b", "c"), ran)
    }

    @Test
    fun `a repeating task runs every period until it's cancelled`() {
        var count = 0
        scheduler.run(Where.Global, period = 100.milliseconds) { task -> if (++count == 3) task.cancel() }
        repeat(20) { scheduler.tick() }
        assertEquals(3, count)
        assertEquals(0, scheduler.pending)
    }

    @Test
    fun `a cancelled task never runs`() {
        val task = scheduler.run(Where.Global) { ran += "no" }
        task.cancel()
        scheduler.tick()
        assertEquals(emptyList(), ran)
        assertTrue(task.isCancelled)
    }

    @Test
    fun `work on an entity that's gone is retired instead`() {
        val here = Uuid.random()
        val gone = Uuid.random()
        entities += here
        scheduler.run(Where.On(here)) { ran += "here" }
        val task = scheduler.run(Where.On(gone), retired = { ran += "retired" }) { ran += "gone" }
        scheduler.tick()
        assertEquals(listOf("here", "retired"), ran)
        assertTrue(task.isCancelled)
    }

    @Test
    fun `a task that throws is logged and the tick goes on`() {
        scheduler.run(Where.Global) { error("boom") }
        scheduler.run(Where.Global) { ran += "after" }
        scheduler.tick()
        assertEquals(listOf("after"), ran)
        assertEquals(1, log.errors.size)
    }

    @Test
    fun `the server thread owns places and entities, async threads own async work`() {
        assertTrue(scheduler.owns(Where.Global))
        assertTrue(scheduler.owns(Where.On(Uuid.random())))
        assertFalse(scheduler.owns(Where.Async))
        val owned = CountDownLatch(1)
        var async = false
        var global = true
        scheduler.run(Where.Async) {
            async = scheduler.owns(Where.Async)
            global = scheduler.owns(Where.Global)
            owned.countDown()
        }
        assertTrue(owned.await(5, TimeUnit.SECONDS))
        assertTrue(async)
        assertFalse(global)
    }

    @Test
    fun `scheduling from another thread runs on the next tick`() {
        val thread = Thread { scheduler.run(Where.Global) { ran += "from elsewhere" } }
        thread.start()
        thread.join()
        scheduler.tick()
        assertEquals(listOf("from elsewhere"), ran)
    }

    @Test
    fun `async work waits its delay`() {
        val done = CountDownLatch(1)
        val start = System.nanoTime()
        scheduler.run(Where.Async, delay = 100.milliseconds) { done.countDown() }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertTrue(System.nanoTime() - start >= 95_000_000)
    }

    @Test
    fun `once closed, nothing runs and nothing new is taken`() {
        scheduler.run(Where.Global) { ran += "dropped" }
        scheduler.close(1.seconds)
        val late = scheduler.run(Where.Global) { ran += "late" }
        val lateAsync = scheduler.run(Where.Async) { ran += "late async" }
        scheduler.tick()
        assertEquals(emptyList(), ran)
        assertTrue(late.isCancelled)
        assertTrue(lateAsync.isCancelled)
    }
}
