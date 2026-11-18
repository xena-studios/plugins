package co.xenastudios.xrewind.log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Who may write to the database now. Everything that writes (the writer, a purge, the
 * import's notes) takes a turn for each transaction. A hold (the API's pause, for a
 * backup) lets the turns already running finish, then lets no new one start until every
 * hold is let go or runs out. Holds are counted one by one, so letting go of one never
 * ends another plugin's.
 */
public final class WriteGate {

    private final Logger logger;
    private final Object lock = new Object();
    /** Each hold, and the nanoTime it runs out. */
    private final Map<Hold, Long> holds = new HashMap<>();
    /** Holds waiting for the turns running when they were asked for. */
    private final List<Hold> waiting = new ArrayList<>();
    private int turns;
    // Wakes the writer when the last hold goes, so it doesn't sleep out the time limit.
    private volatile Runnable wake = () -> {};

    public WriteGate(Logger logger) {
        this.logger = logger;
    }

    void onRelease(Runnable wake) {
        this.wake = wake;
    }

    /** One hold; let it go with {@link #release()}. */
    public final class Hold {

        private final CompletableFuture<Void> ready = new CompletableFuture<>();

        private Hold() {}

        /**
         * Completes once nothing is writing and nothing new will start, on whichever
         * thread finished the last write (or the caller's, if nothing was writing).
         */
        public CompletableFuture<Void> ready() {
            return ready;
        }

        /** Whether it still holds: false once let go or run out. */
        public boolean held() {
            synchronized (lock) {
                return holds.containsKey(this);
            }
        }

        /** Lets this hold go. Does nothing the second time. */
        public void release() {
            boolean last;
            synchronized (lock) {
                if (holds.remove(this) == null) {
                    return;
                }
                waiting.remove(this);
                last = holds.isEmpty();
                lock.notifyAll();
            }
            // Let go before it was ready: nothing is held for whoever waits on it.
            ready.complete(null);
            if (last) {
                wake.run();
            }
        }
    }

    /** Holds the gate for {@code maxNanos} at most. See {@link Hold#ready()}. */
    public Hold hold(long maxNanos) {
        Hold hold = new Hold();
        boolean now;
        synchronized (lock) {
            holds.put(hold, System.nanoTime() + maxNanos);
            now = turns == 0;
            if (!now) {
                waiting.add(hold);
            }
        }
        if (now) {
            hold.ready.complete(null);
        }
        return hold;
    }

    /** Takes a turn unless the gate is held. Call {@link #exit()} after, if it returns true. */
    public boolean tryEnter() {
        synchronized (lock) {
            expire(System.nanoTime());
            if (!holds.isEmpty()) {
                return false;
            }
            turns++;
            return true;
        }
    }

    /** Takes a turn, held or not: for the writer's last writes as the server stops. */
    public void enterAnyway() {
        synchronized (lock) {
            turns++;
        }
    }

    /** Waits until the gate isn't held, then takes a turn. Call {@link #exit()} after. Off the server's threads. */
    public void enter() throws InterruptedException {
        synchronized (lock) {
            while (true) {
                long now = System.nanoTime();
                expire(now);
                if (holds.isEmpty()) {
                    turns++;
                    return;
                }
                long left = earliest() - now;
                TimeUnit.NANOSECONDS.timedWait(lock, Math.max(1, left));
            }
        }
    }

    /** Ends a turn. */
    public void exit() {
        List<Hold> ready;
        synchronized (lock) {
            turns--;
            if (turns > 0 || waiting.isEmpty()) {
                return;
            }
            ready = List.copyOf(waiting);
            waiting.clear();
        }
        ready.forEach(hold -> hold.ready.complete(null));
    }

    /** How long until the gate may open: until the earliest hold runs out; 0 if it isn't held. */
    public long heldNanos() {
        synchronized (lock) {
            return holds.isEmpty() ? 0 : Math.max(1, earliest() - System.nanoTime());
        }
    }

    // Under the lock.
    private long earliest() {
        long earliest = Long.MAX_VALUE;
        for (long until : holds.values()) {
            earliest = Math.min(earliest, until);
        }
        return earliest;
    }

    // Under the lock: holds that ran out are let go, with a warning.
    private void expire(long now) {
        List<Hold> over = new ArrayList<>();
        holds.forEach((hold, until) -> {
            if (until - now <= 0) {
                over.add(hold);
            }
        });
        for (Hold hold : over) {
            holds.remove(hold);
            waiting.remove(hold);
            hold.ready.complete(null);
            logger.warning("A plugin held the writer for a backup and didn't let go, so writing carries on.");
        }
        if (!over.isEmpty()) {
            lock.notifyAll();
        }
    }
}
