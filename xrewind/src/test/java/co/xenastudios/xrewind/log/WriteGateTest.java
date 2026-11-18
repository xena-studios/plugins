package co.xenastudios.xrewind.log;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

/** Holds are counted one by one, wait for writes running, and run out. */
class WriteGateTest {

    final WriteGate gate = new WriteGate(Logger.getLogger("test"));
    static final long MINUTE = TimeUnit.MINUTES.toNanos(1);

    @Test
    void oneHoldLetGoDoesntEndAnother() {
        WriteGate.Hold first = gate.hold(MINUTE);
        WriteGate.Hold second = gate.hold(MINUTE);
        assertFalse(gate.tryEnter());
        first.release();
        first.release();
        assertFalse(first.held());
        assertTrue(second.held());
        assertFalse(gate.tryEnter(), "still held by the second");
        second.release();
        assertTrue(gate.tryEnter());
        gate.exit();
    }

    @Test
    void aHoldIsReadyOnceTheWriteRunningEnds() {
        assertTrue(gate.tryEnter());
        WriteGate.Hold hold = gate.hold(MINUTE);
        assertFalse(hold.ready().isDone(), "a write is running");
        assertFalse(gate.tryEnter(), "but no new one starts");
        gate.exit();
        assertTrue(hold.ready().isDone());
        hold.release();
    }

    @Test
    void aHoldRunsOut() throws Exception {
        WriteGate.Hold hold = gate.hold(TimeUnit.MILLISECONDS.toNanos(50));
        assertFalse(gate.tryEnter());
        Thread.sleep(100);
        assertTrue(gate.tryEnter(), "it let go by itself");
        gate.exit();
        assertFalse(hold.held());
    }
}
