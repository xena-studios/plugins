package co.xenastudios.xrewind.log;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.logging.Logger;

/**
 * Where listeners leave entries for the writer. Adding is lock-free and safe from any
 * thread, Folia's region threads included.
 *
 * <p>If the writer falls behind (the database is slow or down), entries wait here. Past
 * {@link #WARN_AT} the console is told; past {@link #LIMIT}, new entries are counted and
 * dropped rather than run the server out of memory, and the console says how many.
 */
public final class LogQueue {

    /** The writer is woken early once this many are waiting. */
    static final int BATCH = 2_000;
    static final int WARN_AT = 100_000;
    /** About 100 MB of waiting entries. */
    static final int LIMIT = 1_000_000;

    private final Queue<Entry> entries = new ConcurrentLinkedQueue<>();
    private final AtomicInteger size = new AtomicInteger();
    private final AtomicLong dropped = new AtomicLong();
    private final Logger logger;
    private volatile Thread writer;

    public LogQueue(Logger logger) {
        this.logger = logger;
    }

    /** Hands an entry to the writer. Never blocks. */
    public void add(Entry entry) {
        int waiting = size.incrementAndGet();
        if (waiting > LIMIT) {
            size.decrementAndGet();
            if (dropped.getAndIncrement() == 0) {
                logger.severe(LIMIT + " changes are waiting to be saved, so new ones aren't being logged."
                    + " Check the database; /xrewind status shows how many were missed.");
            }
            return;
        }
        entries.add(entry);
        if (waiting == WARN_AT) {
            logger.warning(WARN_AT + " changes are waiting to be saved: the database is slow or can't be reached.");
        }
        if (waiting == BATCH) {
            Thread thread = writer;
            if (thread != null) {
                LockSupport.unpark(thread);
            }
        }
    }

    /** Takes the next entry, or null if there's none. Writer thread only. */
    Entry poll() {
        Entry entry = entries.poll();
        if (entry != null) {
            size.decrementAndGet();
        }
        return entry;
    }

    void writer(Thread thread) {
        this.writer = thread;
    }

    public int size() {
        return size.get();
    }

    /** Entries dropped because too many were waiting. */
    public long dropped() {
        return dropped.get();
    }
}
