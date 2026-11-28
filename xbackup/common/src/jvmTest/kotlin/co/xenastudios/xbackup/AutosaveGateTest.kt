package co.xenastudios.xbackup

import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Autosave is never left off, whatever order the pause's steps run in (1.x's AutosaveGateTest). */
class AutosaveGateTest {
    class FakeWorld {
        @Volatile
        var autoSave: Boolean = true
    }

    private object Switch : AutosaveGate.Switch<FakeWorld> {
        override fun isOn(world: FakeWorld): Boolean = world.autoSave

        override fun set(world: FakeWorld, on: Boolean) {
            world.autoSave = on
        }
    }

    @Test
    fun aMainThreadStepThatRunsAfterTheEndDoesNothing() {
        // The backup waited for the main thread (a server paused while empty, say), gave up and
        // ended the pause. The queued step runs later: it must not turn autosave off.
        val worlds = listOf(FakeWorld(), FakeWorld())
        val gate = AutosaveGate(Switch)
        gate.open()
        gate.close()
        assertNull(gate.turnOff(worlds))
        assertTrue(worlds.all { it.autoSave })
        gate.close()
        assertTrue(worlds.all { it.autoSave })
    }

    @Test
    fun everyEndTurnsBackOnWhatWasTurnedOff() {
        val arena = FakeWorld().apply { autoSave = false }
        val world = FakeWorld()
        val gate = AutosaveGate(Switch)
        gate.open()
        assertEquals(listOf(world), gate.turnOff(listOf(arena, world)), "an owner's arena is left alone")
        assertFalse(world.autoSave)
        gate.close()
        assertTrue(world.autoSave)
        assertFalse(arena.autoSave, "and never turned on")
        // Ending twice (finally, then the watchdog) changes nothing.
        world.autoSave = false
        gate.close()
        assertFalse(world.autoSave, "a second end doesn't touch worlds it no longer holds")
    }

    @Test
    fun racingTheEndNeverLeavesAutosaveOff() {
        repeat(5_000) { round ->
            val worlds = listOf(FakeWorld(), FakeWorld(), FakeWorld())
            val gate = AutosaveGate(Switch)
            gate.open()
            val go = CountDownLatch(1)
            val main = Thread { go.await(); gate.turnOff(worlds) }
            val end = Thread { go.await(); gate.close() }
            main.start()
            end.start()
            go.countDown()
            main.join(10_000)
            end.join(10_000)
            for (world in worlds) assertTrue(world.autoSave, "round $round")
            assertTrue(gate.held().isEmpty())
        }
    }
}
