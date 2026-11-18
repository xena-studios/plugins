package co.xenastudios.xrewind.log;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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

    @Test
    void aHoldStillWaitingWhenItsTimeIsUpFails() throws Exception {
        assertTrue(gate.tryEnter(), "a long write, such as a batch written row by row");
        WriteGate.Hold hold = gate.hold(TimeUnit.MILLISECONDS.toNanos(50));
        Thread.sleep(150);
        assertTrue(hold.ready().isCompletedExceptionally(), "it gave up while the write ran, rather than be ready with no time left");
        assertInstanceOf(TimeoutException.class, cause(hold.ready()));
        assertFalse(hold.held());
        assertTrue(gate.tryEnter(), "and holds nothing");
        gate.exit();
        gate.exit();
    }

    @Test
    void aHoldsTimeStartsWhenItsReady() throws Exception {
        assertTrue(gate.tryEnter());
        WriteGate.Hold hold = gate.hold(TimeUnit.MILLISECONDS.toNanos(300));
        Thread.sleep(200);
        gate.exit();
        assertTrue(hold.ready().isDone());
        assertFalse(hold.ready().isCompletedExceptionally());
        Thread.sleep(200);
        assertFalse(gate.tryEnter(), "400 ms after it was asked for, but 200 after it was ready");
        assertTrue(hold.held());
        Thread.sleep(250);
        assertTrue(gate.tryEnter(), "then it ran out");
        gate.exit();
        assertFalse(hold.held());
    }

    @Test
    void holdsThatOverlapCantKeepWritesStoppedForGood() throws Exception {
        WriteGate short_ = new WriteGate(Logger.getLogger("test"), TimeUnit.MILLISECONDS.toNanos(50));
        WriteGate.Hold first = short_.hold(MINUTE);
        Thread.sleep(80);
        WriteGate.Hold second = short_.hold(MINUTE);
        assertInstanceOf(IllegalStateException.class, cause(second.ready()), "shut too long: refused");
        assertFalse(second.held());
        first.release();
        assertInstanceOf(IllegalStateException.class, cause(short_.hold(MINUTE).ready()), "until a turn has gone through");
        assertTrue(short_.tryEnter());
        short_.exit();
        WriteGate.Hold third = short_.hold(MINUTE);
        assertTrue(third.ready().isDone());
        assertFalse(third.ready().isCompletedExceptionally(), "then holds are taken again");
        third.release();
    }

    @Test
    void aHoldLetGoBeforeItsReadyFailsAndHoldsNothing() {
        assertTrue(gate.tryEnter());
        WriteGate.Hold hold = gate.hold(MINUTE);
        hold.release();
        assertThrows(CancellationException.class, hold.ready()::join);
        assertTrue(gate.tryEnter(), "nothing holds the gate");
        gate.exit();
        gate.exit();
    }

    @Test
    void stoppingLetsEveryHoldGo() {
        WriteGate.Hold ready = gate.hold(MINUTE);
        gate.releaseAll();
        assertFalse(ready.held());
        assertFalse(gate.closed());
        assertTrue(gate.tryEnter());
        gate.exit();
    }

    /** What runs on a hold's future runs outside the gate's lock: here, another thread using the gate. */
    @Test
    void readyIsNeverCompletedUnderTheLock() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            assertTrue(gate.tryEnter());
            WriteGate.Hold afterWrite = gate.hold(MINUTE);
            CompletableFuture<Void> usedGate = afterWrite.ready().thenRun(this::useGateFromAnotherThread);
            gate.exit();
            usedGate.get();
            afterWrite.release();

            assertTrue(gate.tryEnter());
            WriteGate.Hold givesUp = gate.hold(TimeUnit.MILLISECONDS.toNanos(20));
            CompletableFuture<Void> usedAgain = givesUp.ready().handle((ignored, error) -> {
                useGateFromAnotherThread();
                return null;
            });
            usedAgain.get();
            gate.exit();
        });
    }

    private void useGateFromAnotherThread() {
        Thread other = new Thread(gate::heldNanos);
        other.start();
        try {
            other.join();
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Throwable cause(CompletableFuture<?> future) {
        ExecutionException thrown = assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
        return thrown.getCause();
    }
}
