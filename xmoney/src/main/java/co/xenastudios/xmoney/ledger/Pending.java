package co.xenastudios.xmoney.ledger;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The outcome of a change the shared database decides, on a network. It's queued, then either
 * started by the writer or abandoned, never both: whichever comes first wins, by
 * compare-and-set. An abandoned change is never run, so refusing it is true: nothing was
 * taken. A started one runs once, and its caller is told what really happened.
 */
public final class Pending extends CompletableFuture<Outcome> {

    private static final int QUEUED = 0;
    private static final int STARTED = 1;
    private static final int ABANDONED = 2;

    private final AtomicInteger state = new AtomicInteger(QUEUED);
    private final long deadline;
    /** When the writer started it ({@link System#nanoTime}), for its budget before the commit. */
    private volatile long startedNanos;

    /** @param deadline epoch ms after which the writer won't start it; {@link Long#MAX_VALUE} for none */
    Pending(long deadline) {
        this.deadline = deadline;
    }

    /**
     * Gives up on the change if the writer hasn't started it, and completes this with
     * {@code refused}. Any thread.
     *
     * @return true if it will never run; false if it already started or finished, and its
     *     real outcome is coming
     */
    public boolean abandon(Outcome refused) {
        if (state.compareAndSet(QUEUED, ABANDONED)) {
            complete(refused);
            return true;
        }
        return false;
    }

    /** Whether the writer has started it, so its outcome is the database's. */
    public boolean started() {
        return state.get() == STARTED;
    }

    /**
     * The writer's claim on it, just before running it. Past the deadline it's abandoned
     * instead, and completed with {@code refused}.
     *
     * @return whether to run it: false if it was abandoned, now or before
     */
    boolean start(long now, Outcome refused) {
        int current = state.get();
        if (current == STARTED) {
            return true;
        }
        if (current == ABANDONED) {
            return false;
        }
        if (now > deadline) {
            abandon(refused);
            return state.get() == STARTED;
        }
        if (state.compareAndSet(QUEUED, STARTED)) {
            startedNanos = System.nanoTime();
            return true;
        }
        return state.get() == STARTED;
    }

    /** When the writer started it, by {@link System#nanoTime}; only once {@link #started}. */
    long startedNanos() {
        return startedNanos;
    }

    /** Whether it's still waiting to be started, and past its deadline. */
    boolean overdue(long now) {
        return state.get() == QUEUED && now > deadline;
    }
}
