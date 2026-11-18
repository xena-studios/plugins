package co.xenastudios.xbackup.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

/** Autosave is never left off, whatever order the pause, its main-thread step and the end come in. */
class AutosaveGateTest {

    static final class FakeWorld {
        volatile boolean autoSave = true;
    }

    private static AutosaveGate<FakeWorld> gate() {
        return new AutosaveGate<>(new AutosaveGate.Switch<>() {
            @Override
            public boolean isOn(FakeWorld world) {
                return world.autoSave;
            }

            @Override
            public void set(FakeWorld world, boolean on) {
                world.autoSave = on;
            }
        });
    }

    @Test
    void aMainThreadStepThatRunsAfterTheEndDoesNothing() {
        // The backup waited 2 minutes for the main thread (a server paused while empty, say),
        // gave up and ended the pause. The queued step runs later: it must not turn autosave off.
        List<FakeWorld> worlds = List.of(new FakeWorld(), new FakeWorld());
        AutosaveGate<FakeWorld> gate = gate();
        gate.open();
        gate.close();
        assertNull(gate.turnOff(worlds));
        assertTrue(worlds.get(0).autoSave && worlds.get(1).autoSave);
        // And a later end, from the watchdog or disable, is harmless.
        gate.close();
        assertTrue(worlds.get(0).autoSave && worlds.get(1).autoSave);
    }

    @Test
    void everyEndTurnsBackOnWhatWasTurnedOff() {
        FakeWorld arena = new FakeWorld();
        arena.autoSave = false;
        FakeWorld world = new FakeWorld();
        AutosaveGate<FakeWorld> gate = gate();
        gate.open();
        assertEquals(List.of(world), gate.turnOff(List.of(arena, world)), "an owner's arena is left alone");
        assertFalse(world.autoSave);
        gate.close();
        assertTrue(world.autoSave);
        assertFalse(arena.autoSave, "and never turned on");
        // Ending twice (finally, then the watchdog) changes nothing.
        world.autoSave = false;
        gate.close();
        assertFalse(world.autoSave, "a second end doesn't touch worlds it no longer holds");
    }

    @Test
    void racingTheEndNeverLeavesAutosaveOff() throws Exception {
        for (int round = 0; round < 5_000; round++) {
            List<FakeWorld> worlds = List.of(new FakeWorld(), new FakeWorld(), new FakeWorld());
            AutosaveGate<FakeWorld> gate = gate();
            gate.open();
            CountDownLatch go = new CountDownLatch(1);
            Thread main = new Thread(() -> {
                await(go);
                gate.turnOff(worlds);
            });
            Thread end = new Thread(() -> {
                await(go);
                gate.close();
            });
            main.start();
            end.start();
            go.countDown();
            main.join();
            end.join();
            // Whichever came first, the end has happened: nothing may be left off.
            for (FakeWorld world : worlds) {
                assertTrue(world.autoSave, "round " + round);
            }
            assertTrue(gate.held().isEmpty());
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
