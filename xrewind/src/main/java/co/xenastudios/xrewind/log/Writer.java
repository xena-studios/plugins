package co.xenastudios.xrewind.log;

import co.xenastudios.core.db.Database;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The one thread that writes the log: it takes entries off the {@link LogQueue} and
 * writes them in batches, each one transaction, every half second or as soon as a
 * batch is waiting. A batch that fails is kept and tried again, after a growing pause,
 * on a fresh connection; entries are only ever dropped by the queue's limit.
 */
public final class Writer implements Runnable {

    /** How often waiting entries are written when there are too few to wake the writer. */
    static final long FLUSH_NANOS = TimeUnit.MILLISECONDS.toNanos(500);
    /** The most rows one transaction writes. */
    static final int MAX_BATCH = 10_000;
    private static final long MAX_BACKOFF_NANOS = TimeUnit.SECONDS.toNanos(30);

    private final LogQueue queue;
    private final Supplier<Database> databases;
    private final Logger logger;
    private final List<Entry> batch = new ArrayList<>();

    private Thread thread;
    private volatile boolean running;
    private Database database;
    private Connection connection;
    private Dictionaries dictionaries;
    private PreparedStatement blocks;
    private PreparedStatement sessions;
    private long backoffNanos;

    private volatile long written;
    private volatile long lastBatchNanos;
    private volatile int lastBatchSize;
    private volatile String problem;

    public Writer(LogQueue queue, Supplier<Database> databases, Logger logger) {
        this.queue = queue;
        this.databases = databases;
        this.logger = logger;
    }

    public synchronized void start() {
        if (thread != null) {
            return;
        }
        running = true;
        thread = new Thread(this, "xRewind writer");
        thread.setDaemon(true);
        queue.writer(thread);
        thread.start();
    }

    /**
     * Writes what's waiting and stops, giving up after {@code timeout}. Says in the
     * console how much was written, and how much couldn't be.
     */
    public synchronized void stop(long timeout, TimeUnit unit) {
        if (thread == null) {
            return;
        }
        running = false;
        LockSupport.unpark(thread);
        try {
            thread.join(unit.toMillis(timeout));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (thread.isAlive()) {
            thread.interrupt();
        }
        int lost = queue.size() + batch.size();
        if (lost > 0) {
            logger.warning(lost + " logged changes couldn't be saved before stopping" + (problem == null ? "." : ": " + problem));
        }
        thread = null;
    }

    @Override
    public void run() {
        while (true) {
            Database current = databases.get();
            if (current == null || !connected(current)) {
                if (!running) {
                    break;
                }
                LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(1));
                continue;
            }
            Entry entry;
            while (batch.size() < MAX_BATCH && (entry = queue.poll()) != null) {
                batch.add(entry);
            }
            if (batch.isEmpty()) {
                if (!running) {
                    break;
                }
                LockSupport.parkNanos(FLUSH_NANOS);
                continue;
            }
            if (write()) {
                backoffNanos = 0;
                // A full batch means more is waiting: go again at once.
                if (batch.isEmpty() && queue.size() < LogQueue.BATCH && running) {
                    LockSupport.parkNanos(FLUSH_NANOS);
                }
            } else {
                disconnect();
                if (!running) {
                    break;
                }
                backoffNanos = Math.min(MAX_BACKOFF_NANOS, Math.max(TimeUnit.SECONDS.toNanos(1), backoffNanos * 2));
                LockSupport.parkNanos(backoffNanos);
            }
        }
        disconnect();
    }

    // Opens a connection to the database the storage has now, and reads its names.
    private boolean connected(Database current) {
        if (current == database && connection != null) {
            return true;
        }
        disconnect();
        try {
            Connection opened = current.connect();
            Dictionaries names = new Dictionaries();
            names.load(opened, current);
            opened.setAutoCommit(false);
            blocks = opened.prepareStatement(current.sql("INSERT INTO {p}block (time, user, world, x, y, z, action, old_state, new_state) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"));
            sessions = opened.prepareStatement(current.sql("INSERT INTO {p}session (time, user, world, x, y, z, action) VALUES (?, ?, ?, ?, ?, ?, ?)"));
            connection = opened;
            dictionaries = names;
            database = current;
            if (problem != null) {
                logger.info("Saving logged changes again.");
                problem = null;
            }
            return true;
        } catch (SQLException e) {
            fail("couldn't connect to " + current.description() + ": " + e.getMessage(), e);
            disconnect();
            return false;
        }
    }

    private boolean write() {
        long start = System.nanoTime();
        try {
            for (Entry entry : batch) {
                switch (entry) {
                    case Entry.BlockChange change -> {
                        blocks.setInt(1, change.time());
                        blocks.setInt(2, dictionaries.user(change.actor()));
                        blocks.setInt(3, dictionaries.world(change.world()));
                        blocks.setInt(4, change.x());
                        blocks.setInt(5, change.y());
                        blocks.setInt(6, change.z());
                        blocks.setByte(7, change.action());
                        blocks.setInt(8, dictionaries.state(change.before()));
                        blocks.setInt(9, dictionaries.state(change.after()));
                        blocks.addBatch();
                    }
                    case Entry.Session session -> {
                        sessions.setInt(1, session.time());
                        sessions.setInt(2, dictionaries.user(session.actor()));
                        sessions.setInt(3, dictionaries.world(session.world()));
                        sessions.setInt(4, session.x());
                        sessions.setInt(5, session.y());
                        sessions.setInt(6, session.z());
                        sessions.setByte(7, session.action());
                        sessions.addBatch();
                    }
                }
            }
            // Names first, so no row ever points at one that isn't there.
            dictionaries.write(connection, database);
            blocks.executeBatch();
            sessions.executeBatch();
            connection.commit();
            dictionaries.committed();
            written += batch.size();
            lastBatchSize = batch.size();
            lastBatchNanos = System.nanoTime() - start;
            batch.clear();
            if (problem != null) {
                logger.info("Saving logged changes again.");
                problem = null;
            }
            return true;
        } catch (SQLException | RuntimeException e) {
            try {
                connection.rollback();
            } catch (SQLException ignored) {
                // The connection is replaced anyway.
            }
            fail("couldn't save " + batch.size() + " logged changes: " + e.getMessage() + " Trying again.", e);
            return false;
        }
    }

    // Says what went wrong once, not on every retry.
    private void fail(String message, Exception e) {
        if (!message.equals(problem)) {
            logger.log(Level.WARNING, message, e instanceof SQLException ? null : e);
        }
        problem = message;
    }

    private void disconnect() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // Nothing more to do with it.
            }
        }
        connection = null;
        database = null;
        dictionaries = null;
        blocks = null;
        sessions = null;
    }

    /** Entries written since the server started. */
    public long written() {
        return written;
    }

    /** How long the last batch took, and how big it was. */
    public long lastBatchNanos() {
        return lastBatchNanos;
    }

    public int lastBatchSize() {
        return lastBatchSize;
    }

    /** What's stopping writes now, or null when they're going through. */
    public String problem() {
        return problem;
    }
}
