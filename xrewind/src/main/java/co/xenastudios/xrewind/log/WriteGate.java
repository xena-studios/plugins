package co.xenastudios.xrewind.log;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;

/**
 * Who may write to the database now. Everything that writes (the writer, a purge, the
 * import's notes) takes a turn for each transaction. A hold (the API's pause, for a
 * backup) lets the turns already running finish, then lets no new one start until every
 * hold is let go or runs out. Holds are counted one by one, so letting go of one never
 * ends another plugin's.
 *
 * <p>A hold's time starts once it's ready, so a long write before it doesn't use it up; a
 * hold still waiting when its time is up fails instead. Holds that overlap can't keep
 * writes stopped for good: once the gate has been shut for {@link #MAX_CLOSED_NANOS}, new
 * holds are refused until a turn has gone through.
 *
 * <p>Futures are only ever completed outside the lock, so whatever runs on them can't
 * hold up, or deadlock with, a write.
 */
public final class WriteGate {

    /** How long holds that overlap may keep the gate shut before new ones are refused. */
    static final long MAX_CLOSED_NANOS = TimeUnit.SECONDS.toNanos(60);

    private final Logger logger;
    private final long maxClosedNanos;
    private final Object lock = new Object();
    /** Every hold, ready or still waiting. */
    private final Set<Hold> holds = new HashSet<>();
    /** Holds waiting for the turns running when they were asked for. */
    private final List<Hold> waiting = new ArrayList<>();
    private int turns;
    /** When the gate shut, while any hold is held. */
    private long closedAt;
    /** Shut too long: no new holds until a turn has gone through. */
    private boolean refusing;
    // Read without the lock: by the writer between rows, and the queue for its warning.
    private volatile boolean closed;
    // Wakes the writer when the last hold goes, so it doesn't sleep out the time limit.
    private volatile Runnable wake = () -> {};

    public WriteGate(Logger logger) {
        this(logger, MAX_CLOSED_NANOS);
    }

    WriteGate(Logger logger, long maxClosedNanos) {
        this.logger = logger;
        this.maxClosedNanos = maxClosedNanos;
    }

    void onRelease(Runnable wake) {
        this.wake = wake;
    }

    /** One hold; let it go with {@link #release()}. */
    public final class Hold {

        private final CompletableFuture<Void> ready = new CompletableFuture<>();
        private final long maxNanos;
        /** Under the lock: when it runs out once ready; when it gives up while waiting. */
        private long until;

        private Hold(long maxNanos) {
            this.maxNanos = maxNanos;
        }

        /**
         * Completes once nothing is writing and nothing new will start, on whichever
         * thread finished the last write (or the caller's, if nothing was writing). Fails
         * with a {@link TimeoutException} if the writes running didn't finish in the hold's
         * time, with an {@link IllegalStateException} if holds have kept writes stopped too
         * long already, and with a {@link CancellationException} if it was let go first.
         */
        public CompletableFuture<Void> ready() {
            return ready;
        }

        /** Whether it still holds, or is waiting to: false once let go or run out. */
        public boolean held() {
            synchronized (lock) {
                return holds.contains(this);
            }
        }

        /** Lets this hold go. Does nothing the second time. */
        public void release() {
            boolean last;
            synchronized (lock) {
                if (!holds.remove(this)) {
                    return;
                }
                waiting.remove(this);
                last = opened();
            }
            // Let go before it was ready: nothing is held for whoever waits on it.
            ready.completeExceptionally(new CancellationException("The hold was let go before it was ready"));
            if (last) {
                wake.run();
            }
        }
    }

    /** Holds the gate for {@code maxNanos} at most, counted from when it's ready. See {@link Hold#ready()}. */
    public Hold hold(long maxNanos) {
        Hold hold = new Hold(maxNanos);
        boolean now = false;
        boolean refused;
        int expired;
        synchronized (lock) {
            long time = System.nanoTime();
            expired = expire(time);
            if (!holds.isEmpty() && time - closedAt >= maxClosedNanos) {
                refusing = true;
            }
            refused = refusing;
            if (!refused) {
                if (holds.isEmpty()) {
                    closedAt = time;
                    closed = true;
                }
                holds.add(hold);
                hold.until = time + maxNanos;
                now = turns == 0;
                if (!now) {
                    waiting.add(hold);
                }
            }
        }
        warn(expired);
        if (now) {
            hold.ready.complete(null);
        } else if (refused) {
            hold.ready.completeExceptionally(new IllegalStateException(
                "Writes have been held for " + TimeUnit.NANOSECONDS.toSeconds(maxClosedNanos)
                    + " seconds by holds that overlap; try again once what's waiting is written"));
        } else {
            // One-shot, on the JDK's shared timer: gives up if the writes running outlast it.
            CompletableFuture.delayedExecutor(maxNanos, TimeUnit.NANOSECONDS).execute(() -> giveUp(hold));
        }
        return hold;
    }

    // A hold still waiting when its time is up fails, rather than be ready with no time left.
    private void giveUp(Hold hold) {
        boolean last;
        synchronized (lock) {
            if (!waiting.remove(hold)) {
                return;
            }
            holds.remove(hold);
            last = opened();
        }
        hold.ready.completeExceptionally(new TimeoutException("Writes running didn't finish within "
            + TimeUnit.NANOSECONDS.toMillis(hold.maxNanos) + " ms"));
        if (last) {
            wake.run();
        }
    }

    /** Takes a turn unless the gate is held. Call {@link #exit()} after, if it returns true. */
    public boolean tryEnter() {
        int expired;
        boolean entered;
        synchronized (lock) {
            expired = expire(System.nanoTime());
            entered = holds.isEmpty();
            if (entered) {
                turns++;
            }
        }
        warn(expired);
        return entered;
    }

    /** Takes a turn, held or not: for the writer's last writes as the server stops. */
    public void enterAnyway() {
        synchronized (lock) {
            turns++;
        }
    }

    /** Waits until the gate isn't held, then takes a turn. Call {@link #exit()} after. Off the server's threads. */
    public void enter() throws InterruptedException {
        while (true) {
            int expired;
            synchronized (lock) {
                long now = System.nanoTime();
                expired = expire(now);
                if (expired == 0) {
                    if (holds.isEmpty()) {
                        turns++;
                        return;
                    }
                    TimeUnit.NANOSECONDS.timedWait(lock, Math.max(1, earliest() - now));
                }
            }
            warn(expired);
        }
    }

    /** Ends a turn. */
    public void exit() {
        List<Hold> ready;
        synchronized (lock) {
            turns--;
            if (holds.isEmpty()) {
                // A turn went through: holds may be asked for again.
                refusing = false;
            }
            if (turns > 0 || waiting.isEmpty()) {
                return;
            }
            // Each one's time starts now that it's ready.
            long now = System.nanoTime();
            ready = List.copyOf(waiting);
            waiting.clear();
            for (Hold hold : ready) {
                hold.until = now + hold.maxNanos;
            }
        }
        for (Hold hold : ready) {
            if (!hold.ready.complete(null)) {
                // Its future was finished some other way, so no one has it to let go.
                hold.release();
            }
        }
    }

    /** Whether a hold is held, or waiting to be: lock-free, for the writer between rows. */
    public boolean closed() {
        return closed;
    }

    /** Whether a turn is running now: something is writing. */
    public boolean writing() {
        synchronized (lock) {
            return turns > 0;
        }
    }

    /** How long until the gate may open: until the earliest hold runs out; 0 if it isn't held. */
    public long heldNanos() {
        synchronized (lock) {
            return holds.isEmpty() ? 0 : Math.max(1, earliest() - System.nanoTime());
        }
    }

    /**
     * Lets every hold go, for when the writer stops: a plugin's hold mustn't outlive it, and
     * xRewind enabled again starts with an open gate.
     */
    void releaseAll() {
        List<Hold> all;
        synchronized (lock) {
            all = List.copyOf(holds);
            holds.clear();
            waiting.clear();
            refusing = false;
            opened();
        }
        all.forEach(hold -> hold.ready.completeExceptionally(new CancellationException("xRewind stopped")));
        if (!all.isEmpty()) {
            wake.run();
        }
    }

    // Under the lock, after a hold went: true if it was the last.
    private boolean opened() {
        lock.notifyAll();
        if (!holds.isEmpty()) {
            return false;
        }
        closed = false;
        return true;
    }

    // Under the lock.
    private long earliest() {
        long earliest = Long.MAX_VALUE;
        for (Hold hold : holds) {
            earliest = Math.min(earliest, hold.until);
        }
        return earliest;
    }

    // Under the lock: ready holds that ran out are let go. Waiting ones give up on their timer.
    private int expire(long now) {
        int over = 0;
        var each = holds.iterator();
        while (each.hasNext()) {
            Hold hold = each.next();
            if (hold.until - now <= 0 && !waiting.contains(hold)) {
                each.remove();
                over++;
            }
        }
        if (over > 0) {
            opened();
        }
        return over;
    }

    // Outside the lock, so the log's handlers never run while writes wait on it.
    private void warn(int expired) {
        for (int i = 0; i < expired; i++) {
            logger.warning("A plugin held the writer for a backup and didn't let go, so writing carries on.");
        }
    }
}
