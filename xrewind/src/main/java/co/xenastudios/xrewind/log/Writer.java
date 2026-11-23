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
import org.bukkit.inventory.ItemStack;

/**
 * The one thread that writes the log: it takes entries off the {@link LogQueue} and
 * writes them in batches, each one transaction, every half second or as soon as a
 * batch is waiting. A batch that fails is kept and tried again, after a growing pause,
 * on a fresh connection. Entries are dropped only by the queue's limit, or one at a time
 * when the database won't take a row (see {@code isolate}).
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

    private volatile Thread thread;
    private volatile boolean running;
    private Database database;
    private Connection connection;
    private Dictionaries dictionaries;
    private PreparedStatement blocks;
    private PreparedStatement sessions;
    private PreparedStatement containers;
    private PreparedStatement kills;
    private PreparedStatement texts;
    private long backoffNanos;
    /** Failures of one batch in a row, on a live connection, before its rows are tried one by one. */
    static final int ISOLATE_AFTER = 3;
    private int failures;
    private boolean isolating;
    private volatile long dropped;

    private volatile long written;
    private volatile long lastBatchNanos;
    private volatile int lastBatchSize;
    private volatile String problem;
    // Each batch is a turn at the gate, so a backup's hold (the API's pause) can stop writes.
    private final WriteGate gate;

    public Writer(LogQueue queue, Supplier<Database> databases, Logger logger) {
        this.queue = queue;
        this.databases = databases;
        this.logger = logger;
        this.gate = new WriteGate(logger);
        queue.held(gate::closed);
        gate.onRelease(() -> {
            Thread current = thread;
            if (current != null) {
                LockSupport.unpark(current);
            }
        });
    }

    /** What everything that writes to the database takes turns at, and the API's pause holds. */
    public WriteGate gate() {
        return gate;
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
        // A plugin's hold doesn't outlive the writer, so enabled again it starts open.
        gate.releaseAll();
    }

    /** {@link #step()}'s answer when the writer should stop. */
    private static final long STOP = -1;

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
            // Between batches: a backup's hold waits here. Stopping always writes what's left.
            if (!running) {
                gate.enterAnyway();
            } else if (!gate.tryEnter()) {
                LockSupport.parkNanos(gate.heldNanos());
                continue;
            }
            long wait;
            try {
                wait = step();
            } finally {
                gate.exit();
            }
            if (wait == STOP) {
                break;
            }
            if (wait > 0) {
                LockSupport.parkNanos(wait);
            }
        }
        disconnect();
    }

    /**
     * Writes one batch, during a turn at the gate. Waiting is left to the caller, after the
     * turn, so a hold never waits on the writer's sleep.
     *
     * @return how long to wait before the next, or {@link #STOP}
     */
    private long step() {
        // The rest of a batch being written one row at a time goes first, on its own.
        if (isolating && !batch.isEmpty()) {
            isolate();
            return 0;
        }
        Entry entry;
        while (batch.size() < MAX_BATCH && (entry = queue.poll()) != null) {
            batch.add(entry);
        }
        if (batch.isEmpty()) {
            return running ? FLUSH_NANOS : STOP;
        }
        if (write()) {
            backoffNanos = 0;
            // "Three times in a row": a write that goes through starts the count again.
            failures = 0;
            // A full batch means more is waiting: go again at once.
            return batch.isEmpty() && queue.size() < LogQueue.BATCH && running ? FLUSH_NANOS : 0;
        }
        // The same batch failing again and again on a live connection is a row the
        // database won't take, not the database being down: find it.
        boolean alive = alive();
        disconnect();
        if (!running) {
            return STOP;
        }
        failures = alive ? failures + 1 : 0;
        if (failures >= ISOLATE_AFTER) {
            isolating = true;
            failures = 0;
            return 0;
        }
        backoffNanos = Math.min(MAX_BACKOFF_NANOS, Math.max(TimeUnit.SECONDS.toNanos(1), backoffNanos * 2));
        return backoffNanos;
    }

    // Opens a connection to the database the storage has now, and reads its names.
    private boolean connected(Database current) {
        if (current == database && connection != null) {
            return true;
        }
        disconnect();
        try {
            Connection opened = current.connect();
            // Held at once, so a failure below closes it (and its statements) in disconnect.
            connection = opened;
            Dictionaries names = new Dictionaries();
            names.load(opened, current);
            opened.setAutoCommit(false);
            blocks = opened.prepareStatement(current.sql("INSERT INTO {p}block (time, user, world, x, y, z, action, old_state, new_state, extra, rolled_back) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"));
            sessions = opened.prepareStatement(current.sql("INSERT INTO {p}session (time, user, world, x, y, z, action) VALUES (?, ?, ?, ?, ?, ?, ?)"));
            containers = opened.prepareStatement(current.sql("INSERT INTO {p}container (time, user, world, x, y, z, action, material, item, amount, rolled_back) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"));
            kills = opened.prepareStatement(current.sql("INSERT INTO {p}entity (time, user, world, x, y, z, action, type) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"));
            texts = opened.prepareStatement(current.sql("INSERT INTO {p}text (time, user, world, x, y, z, kind, message) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"));
            dictionaries = names;
            database = current;
            // Not "working again" yet: only a write that goes through says that.
            return true;
        } catch (SQLException e) {
            fail("couldn't connect to " + current.description() + ": " + e.getMessage(), e);
            disconnect();
            return false;
        }
    }

    private boolean write() {
        long start = System.nanoTime();
        List<Entry.MarkRolledBack> marks = new ArrayList<>();
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
                        if (change.contents() == null) {
                            blocks.setNull(10, java.sql.Types.INTEGER);
                        } else {
                            blocks.setInt(10, dictionaries.bytes(Contents.write(change.contents())));
                        }
                        blocks.setInt(11, 0);
                        blocks.addBatch();
                    }
                    case Entry.ImportedBlock change -> {
                        blocks.setInt(1, change.time());
                        blocks.setInt(2, dictionaries.user(change.actor()));
                        blocks.setInt(3, dictionaries.world(change.world()));
                        blocks.setInt(4, change.x());
                        blocks.setInt(5, change.y());
                        blocks.setInt(6, change.z());
                        blocks.setByte(7, change.action());
                        blocks.setInt(8, dictionaries.name(change.before()));
                        blocks.setInt(9, dictionaries.name(change.after()));
                        blocks.setNull(10, java.sql.Types.INTEGER);
                        blocks.setInt(11, change.rolledBack() ? 1 : 0);
                        blocks.addBatch();
                    }
                    case Entry.ImportedContainer change -> {
                        containers.setInt(1, change.time());
                        containers.setInt(2, dictionaries.user(change.actor()));
                        containers.setInt(3, dictionaries.world(change.world()));
                        containers.setInt(4, change.x());
                        containers.setInt(5, change.y());
                        containers.setInt(6, change.z());
                        containers.setByte(7, change.action());
                        containers.setInt(8, dictionaries.name(change.material()));
                        containers.setInt(9, dictionaries.bytes(change.item()));
                        containers.setInt(10, change.amount());
                        containers.setInt(11, change.rolledBack() ? 1 : 0);
                        containers.addBatch();
                    }
                    case Entry.MarkRolledBack mark -> marks.add(mark);
                    case Entry.ContainerChange change -> {
                        ItemStack one = change.item().asOne();
                        containers.setInt(1, change.time());
                        containers.setInt(2, dictionaries.user(change.actor()));
                        containers.setInt(3, dictionaries.world(change.world()));
                        containers.setInt(4, change.x());
                        containers.setInt(5, change.y());
                        containers.setInt(6, change.z());
                        containers.setByte(7, change.action());
                        containers.setInt(8, dictionaries.name(one.getType().getKey().toString()));
                        containers.setInt(9, dictionaries.bytes(one.serializeAsBytes()));
                        containers.setInt(10, change.amount());
                        containers.setInt(11, 0);
                        containers.addBatch();
                    }
                    case Entry.Kill kill -> {
                        kills.setInt(1, kill.time());
                        kills.setInt(2, dictionaries.user(kill.actor()));
                        kills.setInt(3, dictionaries.world(kill.world()));
                        kills.setInt(4, kill.x());
                        kills.setInt(5, kill.y());
                        kills.setInt(6, kill.z());
                        kills.setByte(7, Actions.KILL);
                        kills.setInt(8, dictionaries.name(kill.type()));
                        kills.addBatch();
                    }
                    case Entry.Text text -> {
                        texts.setInt(1, text.time());
                        texts.setInt(2, dictionaries.user(text.actor()));
                        texts.setInt(3, dictionaries.world(text.world()));
                        texts.setInt(4, text.x());
                        texts.setInt(5, text.y());
                        texts.setInt(6, text.z());
                        texts.setByte(7, text.kind());
                        texts.setString(8, text.message());
                        texts.addBatch();
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
            containers.executeBatch();
            kills.executeBatch();
            texts.executeBatch();
            // After the inserts: a mark can be for rows in this same batch.
            for (Entry.MarkRolledBack mark : marks) {
                mark(mark);
            }
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

    /**
     * Writes the batch one row at a time, dropping any row the database won't take (and
     * saying so), so one bad row can't stop all logging. If the connection goes, the rest
     * waits for it as usual. Each row is its own commit, so a batch can take a while on a
     * remote database: if a backup asks to hold writes meanwhile, the rest waits for the
     * next turn rather than keep it waiting.
     */
    private void isolate() {
        List<Entry> rows = new ArrayList<>(batch);
        batch.clear();
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0 && running && gate.closed()) {
                batch.addAll(rows.subList(i, rows.size()));
                return;
            }
            batch.add(rows.get(i));
            if (write()) {
                continue;
            }
            batch.clear();
            if (!alive()) {
                batch.addAll(rows.subList(i, rows.size()));
                disconnect();
                return;
            }
            // Counted at once, so flush sees it's been dealt with.
            dropped++;
            logger.warning("Dropped one logged change the database wouldn't take ("
                + rows.get(i).getClass().getSimpleName() + "): " + problem);
            // Dropped, so it isn't stopping writes any more.
            problem = null;
        }
        isolating = false;
        problem = null;
    }

    private boolean alive() {
        try {
            return connection != null && connection.isValid(2);
        } catch (SQLException e) {
            return false;
        }
    }

    /** Changes dropped because the database wouldn't take them. */
    public long dropped() {
        return dropped;
    }

    // UPDATE ... WHERE id IN (...), 500 ids at a time.
    private void mark(Entry.MarkRolledBack mark) throws SQLException {
        long[] ids = mark.ids();
        for (int from = 0; from < ids.length; from += 500) {
            int to = Math.min(ids.length, from + 500);
            StringBuilder sql = new StringBuilder(database.sql("UPDATE {p}" + mark.table() + " SET rolled_back = ? WHERE id IN ("));
            for (int i = from; i < to; i++) {
                sql.append(i == from ? "?" : ", ?");
            }
            try (PreparedStatement update = connection.prepareStatement(sql.append(")").toString())) {
                update.setInt(1, mark.rolledBack() ? 1 : 0);
                for (int i = from; i < to; i++) {
                    update.setLong(i - from + 2, ids[i]);
                }
                update.executeUpdate();
            }
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
        containers = null;
        kills = null;
        texts = null;
    }

    /**
     * Waits until what's queued now is written, so a lookup sees a block placed a moment
     * ago. Gives up after {@code timeoutMillis}, or at once if writes are failing. Not on
     * a server thread.
     *
     * @return true if everything queued before the call is written (or dropped, as a row
     *     the database won't take is) and writes are going through; what's queued after
     *     doesn't count, so it's true on a busy server too
     */
    public boolean flush(long timeoutMillis) {
        return flushed(target(), timeoutMillis);
    }

    /**
     * Everything queued so far, as a count {@link #flushed} can wait for: every entry the
     * writer has taken, and every one still waiting. Counting what's in the queue first
     * means one taken meanwhile is counted twice at worst, so it's never short.
     */
    public long target() {
        long waiting = queue.size();
        return queue.taken() + waiting;
    }

    /**
     * Waits until {@code target} entries have been written or dropped, as {@link #flush}
     * does. Returns at once if the writer isn't running.
     */
    public boolean flushed(long target, long timeoutMillis) {
        Thread current = thread;
        if (current == null) {
            return done(target) && problem == null;
        }
        LockSupport.unpark(current);
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (!done(target) && problem == null && System.nanoTime() < until && !Thread.currentThread().isInterrupted()) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
        }
        return done(target) && problem == null;
    }

    // Every entry taken ends up written or dropped; the rest are still on their way.
    private boolean done(long target) {
        return written + dropped >= target;
    }

    /** Whether the writer thread is running: false before start and after stop. */
    public boolean started() {
        return thread != null;
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
