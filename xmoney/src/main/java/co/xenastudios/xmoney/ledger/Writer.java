package co.xenastudios.xmoney.ledger;

import co.xenastudios.core.db.ChangeFeed;
import co.xenastudios.core.db.Database;
import co.xenastudios.core.db.Dialect;
import co.xenastudios.xmoney.api.TransactionResult.Status;
import co.xenastudios.xmoney.money.Format;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The one thread that writes to the database. Changes are made in memory first (on a
 * single server) and queued here; the writer saves them in order, as soon as they arrive:
 * whatever is waiting goes in one database transaction, so a busy shop costs one commit
 * for many changes, and a quiet server commits each change within milliseconds. There's no
 * timer.
 *
 * <p>Each change's new balance and its ledger row are in the same transaction, so after a
 * crash the database still agrees with itself: a change is either wholly saved or not at
 * all. A transaction that fails is retried on a fresh connection, with a pause that grows
 * to 30 seconds; the console hears about it once. Its first ledger row carries a random
 * {@code commit_id}, so if a commit reached the database but its answer was lost, the retry
 * finds it and doesn't apply it twice.
 *
 * <p>On a network, a change the database decides ({@link Op.Checked}) has a caller waiting
 * for it, so it runs at most once: it's started only if its caller hasn't given up (see
 * {@link Pending}), and if its transaction fails before the commit was sent, the caller is
 * told nothing changed, which is true. Only when a commit was sent and its answer lost does
 * the writer find out from {@code commit_id} what really happened before answering. The
 * writer's connection then waits at most {@link #NETWORK_TIMEOUT_MS} for any one reply, so a
 * started change can't hold its caller for the five minutes a MySQL connection otherwise
 * waits.
 */
public final class Writer {

    static final int BATCH = 1_000;
    /** Ledger rows deleted per slice of a prune; other writes go between slices. */
    static final int PRUNE_SLICE = 5_000;
    /**
     * Balances an import sets per transaction, on a network; other work goes between. Small,
     * so a payment arriving mid-chunk waits a fraction of a second for it.
     */
    static final int IMPORT_CHUNK = 100;
    /** On a network, the longest the writer's connection waits for one reply from MySQL. */
    static final int NETWORK_TIMEOUT_MS = 3_000;
    /**
     * Changes waiting beyond which new ones are refused rather than queued: the database has
     * been away a long time, and memory shouldn't grow without end.
     */
    static final int MAX_WAITING = 100_000;
    /** The change feed's topic for "read every account again", after an import. */
    public static final String RESYNC_TOPIC = "accounts";

    private final Database database;
    private final Accounts accounts;
    private final ChangeFeed feed;
    private final boolean shared;
    private final Logger logger;
    private volatile Events events = Events.NONE;
    private final LinkedBlockingQueue<Op> queue = new LinkedBlockingQueue<>();
    private final SecureRandom random = new SecureRandom();
    private final AtomicLong written = new AtomicLong();
    private final AtomicLong unsaved = new AtomicLong();
    private final String currency = Format.currencyId();

    private Thread thread;
    private volatile boolean stopping;
    private volatile boolean abandon;
    private volatile String problem;
    private volatile long lastBatchNanos;
    private volatile int lastBatchSize;
    /** Whether the last write failed and none has worked since. Read by callers, set by the writer. */
    private volatile boolean failing;
    private volatile boolean warnedFull;
    /** For tests: wraps each new connection, to make the database fail on cue. */
    volatile UnaryOperator<Connection> wrap = UnaryOperator.identity();

    // Writer thread only.
    private Connection connection;
    /** Whether the batch being saved sent its commit: if that fails, it may still have gone through. */
    private boolean commitSent;
    /** What the attempt whose commit was sent would have done, for when it turns out it did. */
    private Attempt attempt;

    /** One attempt's results, kept until its commit is known to have worked. */
    private record Attempt(List<Entry> saved, Map<Account, Long> after, Outcome checked, int importEnd) {}

    /**
     * @param shared whether the database is shared with other servers (network mode): every
     *     write then reads the balance back, publishes to the feed, and corrects memory
     */
    public Writer(Database database, Accounts accounts, ChangeFeed feed, boolean shared, Logger logger) {
        this.database = database;
        this.accounts = accounts;
        this.feed = feed;
        this.shared = shared;
        this.logger = logger;
    }

    public void events(Events events) {
        this.events = events;
    }

    public synchronized void start() {
        if (thread == null) {
            thread = new Thread(this::run, "xMoney writer");
            thread.setDaemon(true);
            thread.start();
        }
    }

    /** Queues work. Any thread. */
    void add(Op op) {
        queue.add(op);
    }

    /**
     * Whether the database is failing: a write failed and none has worked since. On a network
     * the ledger refuses changes the database decides meanwhile, at once. Any thread.
     */
    public boolean failing() {
        return failing;
    }

    /**
     * Whether so many changes are waiting that new ones are refused. The console hears about
     * it once, until the queue drains. Any thread.
     */
    boolean full() {
        if (queue.size() < MAX_WAITING) {
            warnedFull = false;
            return false;
        }
        if (!warnedFull) {
            warnedFull = true;
            logger.severe(MAX_WAITING + " balance changes are waiting for " + database.description()
                + ", so new ones are refused until it catches up. " + (problem == null ? "" : "The problem: " + problem));
        }
        return true;
    }

    /** Whether this is the writer's own thread. */
    boolean isWriter() {
        return Thread.currentThread() == thread;
    }

    /**
     * Saves what's waiting, then stops. If the database can't be reached in time, what's
     * left is counted in {@link #unsaved} and the console told.
     *
     * @return whether everything was saved
     */
    public boolean stop(long timeout, TimeUnit unit) {
        Thread running;
        synchronized (this) {
            running = thread;
        }
        if (running == null) {
            return true;
        }
        stopping = true;
        try {
            running.join(unit.toMillis(timeout));
            if (running.isAlive()) {
                abandon = true;
                running.interrupt();
                running.join(5_000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        List<Op> left = new ArrayList<>(queue);
        // Changes the database would have decided are refused below, not lost: they never happened.
        long lost = unsaved.get() + entries(left.stream().filter(op -> !(op instanceof Op.Checked)).toList());
        for (Op op : left) {
            // Never started, so never run: their callers are told so.
            if (op instanceof Op.Checked checked) {
                checked.done().abandon(unavailable(checked));
            } else if (op instanceof Op.Import importing) {
                importing.done().completeExceptionally(new IllegalStateException("stopped before the import was saved"));
            } else if (op instanceof Op.Flush flush) {
                flush.done().completeExceptionally(new IllegalStateException("stopped before everything was saved"));
            }
        }
        if (lost > 0) {
            logger.severe(lost + " balance changes couldn't be saved before stopping: " + (problem == null ? "the database didn't answer" : problem)
                + ". Balances in the database are from before them.");
            return false;
        }
        return true;
    }

    /** Changes waiting to be saved. */
    public int waiting() {
        return queue.size();
    }

    /** Changes saved since startup. */
    public long written() {
        return written.get();
    }

    /** What's wrong with the database, or null when nothing is. */
    public String problem() {
        return problem;
    }

    public int lastBatchSize() {
        return lastBatchSize;
    }

    public long lastBatchNanos() {
        return lastBatchNanos;
    }

    private void run() {
        List<Op> batch = new ArrayList<>();
        while (true) {
            Op op;
            try {
                op = queue.poll(200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                if (abandon) {
                    break;
                }
                continue;
            }
            if (op == null) {
                if (stopping) {
                    break;
                }
                continue;
            }
            batch.clear();
            batch.add(op);
            if (op.batches()) {
                Op next;
                while (batch.size() < BATCH && (next = queue.peek()) != null && next.batches()) {
                    batch.add(queue.poll());
                }
            }
            save(batch);
            if (abandon) {
                break;
            }
        }
        close();
    }

    // ---- one batch, with retries ----

    private void save(List<Op> batch) {
        long commitId = random.nextLong() & Long.MAX_VALUE;
        long pause = 500;
        // Whether a commit for this batch may have reached the database without our hearing.
        boolean uncertain = false;
        int deadlocks = 0;
        attempt = null;
        while (true) {
            commitSent = false;
            try {
                Connection db = connection();
                if (uncertain && committed(db, commitId)) {
                    alreadySaved(db, batch);
                } else {
                    if (uncertain) {
                        // The commit didn't go through: a started change is answered now, not run again.
                        notSaved(batch);
                        if (batch.isEmpty()) {
                            recovered();
                            return;
                        }
                    }
                    long start = System.nanoTime();
                    run(db, batch, commitId);
                    lastBatchNanos = System.nanoTime() - start;
                    lastBatchSize = batch.size();
                }
                recovered();
                return;
            } catch (SQLException | RuntimeException e) {
                uncertain |= commitSent;
                if (!uncertain && deadlocks++ < 3 && rolledBack(e)) {
                    // Another server's transaction wanted the same rows; the database undid ours
                    // and nothing else is wrong. Its caller is still waiting: run it again now.
                    rollback();
                    continue;
                }
                rollback();
                close();
                problem = e.getMessage();
                if (!failing) {
                    failing = true;
                    logger.log(Level.WARNING, "Couldn't save balance changes to " + database.description()
                        + "; trying again, every " + 30 + "s at most. Balances in memory are right meanwhile.", e);
                }
                if (!uncertain) {
                    // Nothing was committed, so a started change is answered: nothing changed.
                    notSaved(batch);
                }
                expire(batch);
                if (batch.isEmpty()) {
                    return;
                }
                if (abandon || (stopping && Thread.currentThread().isInterrupted())) {
                    unsaved.addAndGet(entries(batch));
                    return;
                }
                if (!pause(pause)) {
                    unsaved.addAndGet(entries(batch));
                    return;
                }
                pause = Math.min(pause * 2, 30_000);
            }
        }
    }

    // A deadlock or serialization failure: the database rolled the transaction back, definitely.
    private static boolean rolledBack(Exception e) {
        return e instanceof SQLException sql && sql.getSQLState() != null && sql.getSQLState().startsWith("40");
    }

    private void recovered() {
        if (failing) {
            failing = false;
            problem = null;
            logger.info("Saving balances to " + database.description() + " again.");
        }
    }

    // Waits before a retry, giving up waiting changes as their deadlines pass. False when stopping for good.
    private boolean pause(long millis) {
        long until = System.currentTimeMillis() + millis;
        long left;
        while ((left = until - System.currentTimeMillis()) > 0) {
            try {
                Thread.sleep(Math.min(left, 250));
            } catch (InterruptedException interrupted) {
                if (abandon) {
                    return false;
                }
            }
            expireQueued();
        }
        return true;
    }

    // A started change whose transaction is known not to have committed: its caller is told
    // nothing changed. It isn't run again, since its caller may have moved on.
    private void notSaved(List<Op> batch) {
        attempt = null;
        batch.removeIf(op -> {
            if (op instanceof Op.Checked checked && checked.done().started()) {
                checked.done().complete(unavailable(checked));
                return true;
            }
            return false;
        });
    }

    // Changes not started by their deadline are given up: their callers are told nothing moved.
    private void expire(List<Op> batch) {
        long now = System.currentTimeMillis();
        batch.removeIf(op -> op instanceof Op.Checked checked && checked.done().overdue(now) && checked.done().abandon(unavailable(checked)));
        expireQueued();
    }

    private void expireQueued() {
        long now = System.currentTimeMillis();
        queue.removeIf(op -> op instanceof Op.Checked checked
            && (checked.done().isDone() || checked.done().overdue(now) && checked.done().abandon(unavailable(checked))));
    }

    private static Outcome unavailable(Op.Checked checked) {
        return Outcome.failed(Status.UNAVAILABLE, checked.changes().getFirst().account().balance());
    }

    private static long entries(List<Op> ops) {
        long count = 0;
        for (Op op : ops) {
            if (op instanceof Op.Write write) {
                count += write.entries().size();
            } else if (op instanceof Op.Checked checked) {
                count += checked.changes().size();
            } else if (op instanceof Op.Import importing) {
                count += importing.targets().size() - importing.next()[0];
            }
        }
        return count;
    }

    private boolean committed(Connection db, long commitId) throws SQLException {
        try (PreparedStatement query = db.prepareStatement(database.sql("SELECT 1 FROM {p}transactions WHERE commit_id = ?"))) {
            query.setLong(1, commitId);
            try (ResultSet result = query.executeQuery()) {
                return result.next();
            }
        }
    }

    // The commit reached the database, but its answer didn't reach us: what that attempt would
    // have done is done now, events and answers included. On a single server memory is
    // already right; on a network it's read back.
    private void alreadySaved(Connection db, List<Op> batch) throws SQLException {
        Attempt done = attempt;
        attempt = null;
        List<Entry> saved = done == null ? List.of() : done.saved();
        written.addAndGet(saved.size());
        java.util.Set<UUID> changed = new java.util.LinkedHashSet<>();
        for (Op op : batch) {
            switch (op) {
                case Op.Write write -> {
                    if (shared && write.optimistic()) {
                        settle(write);
                    }
                    write.entries().forEach(entry -> changed.add(entry.account().id()));
                }
                case Op.Checked checked -> checked.changes().forEach(change -> changed.add(change.account().id()));
                case Op.Create create -> changed.add(create.account().id());
                case Op.Import importing -> {
                    int end = done == null ? importing.next()[0] : done.importEnd();
                    for (int i = importing.next()[0]; i < end; i++) {
                        changed.add(importing.targets().get(i).account().id());
                    }
                }
                default -> {
                    // Names were saved with the rest; nothing more to do.
                }
            }
        }
        if (shared) {
            for (UUID id : changed) {
                refresh(db, id);
            }
        }
        finish(batch, done == null ? null : done.checked(), done == null ? Map.of() : done.after(), done == null ? -1 : done.importEnd());
        if (!saved.isEmpty()) {
            events.saved(saved);
        }
    }

    // ---- the work ----

    private void run(Connection db, List<Op> batch, long commitId) throws SQLException {
        attempt = null;
        Statements sql = new Statements(db);
        List<Entry> saved = new ArrayList<>();
        Map<Account, Long> after = new LinkedHashMap<>();
        java.util.Set<Account> renamed = new java.util.LinkedHashSet<>();
        Outcome checkedOutcome = null;
        int importEnd = -1;
        try {
            long[] commit = {commitId};
            for (Op op : batch) {
                switch (op) {
                    case Op.Write write -> {
                        for (Entry entry : write.entries()) {
                            long balance = sql.add(entry.account(), entry.delta());
                            if (!shared) {
                                balance = entry.after();
                            }
                            Entry row = shared ? new Entry(entry.account(), entry.delta(), balance, entry.cause(), entry.other(), entry.time()) : entry;
                            sql.ledger(row, commit);
                            saved.add(row);
                            after.put(entry.account(), balance);
                        }
                    }
                    case Op.Name name -> {
                        sql.account(name.account());
                        renamed.add(name.account());
                    }
                    case Op.Checked checked -> {
                        // Alone in its batch. Given up on by its caller, or too late: nothing runs.
                        if (!checked.done().start(System.currentTimeMillis(), unavailable(checked))) {
                            return;
                        }
                        checkedOutcome = checked(sql, checked.changes(), commit, saved, after);
                        if (!checkedOutcome.success()) {
                            db.rollback();
                            for (Op.Change change : checked.changes()) {
                                refresh(db, change.account().id());
                            }
                            checked.done().complete(Outcome.failed(checkedOutcome.status(), checked.changes().getFirst().account().balance()));
                            return;
                        }
                    }
                    case Op.Create create -> create(sql, create, commit, saved, after);
                    case Op.Import importing -> importEnd = importChunk(sql, importing, commit, saved, after);
                    case Op.Refresh refresh -> {
                        refresh(db, refresh.id());
                        return;
                    }
                    case Op.Resync ignored -> {
                        resync(db);
                        return;
                    }
                    case Op.Prune prune -> {
                        prune(db, prune);
                        return;
                    }
                    case Op.Flush flush -> {
                        flush.done().complete(null);
                        return;
                    }
                }
            }
            if (shared) {
                if (importEnd >= 0) {
                    // One word to the other servers once the import is done, not one per account.
                    if (importEnd == ((Op.Import) batch.getFirst()).targets().size()) {
                        feed.publish(db, RESYNC_TOPIC, "import");
                    }
                } else {
                    // The other servers read each changed account again, names included.
                    renamed.addAll(after.keySet());
                    for (Account account : renamed) {
                        feed.publish(db, "account", account.id().toString());
                    }
                }
            }
            attempt = new Attempt(saved, after, checkedOutcome, importEnd);
            commitSent = true;
            db.commit();
        } finally {
            sql.close();
        }
        attempt = null;
        written.addAndGet(saved.size());
        if (shared) {
            for (Op op : batch) {
                if (op instanceof Op.Write write && write.optimistic()) {
                    settle(write);
                }
            }
            after.forEach((account, balance) -> {
                synchronized (account) {
                    accounts.set(account, balance + account.pending);
                }
            });
        }
        finish(batch, checkedOutcome, after, importEnd);
        if (!saved.isEmpty()) {
            events.saved(saved);
        }
    }

    // Once a batch is known to be saved: answers waiting callers, and queues an import's next chunk.
    private void finish(List<Op> batch, Outcome checkedOutcome, Map<Account, Long> after, int importEnd) {
        for (Op op : batch) {
            if (op instanceof Op.Checked checked) {
                Op.Change first = checked.changes().getFirst();
                long amount = checkedOutcome != null ? checkedOutcome.amount() : Math.abs(first.delta());
                Long balance = after.get(first.account());
                checked.done().complete(new Outcome(Status.SUCCESS, amount, balance != null ? balance : first.account().balance()));
            } else if (op instanceof Op.Import importing) {
                if (importEnd >= 0) {
                    importing.next()[0] = importEnd;
                }
                if (importing.next()[0] < importing.targets().size()) {
                    queue.add(importing);
                } else {
                    importing.done().complete(importing.targets().size());
                }
            }
        }
    }

    // An optimistic write is saved: it's no longer pending.
    private void settle(Op.Write write) {
        for (Entry entry : write.entries()) {
            Account account = entry.account();
            synchronized (account) {
                account.pending -= entry.delta();
            }
        }
    }

    // Rows are changed in uuid order, whichever way the money goes, so two servers' opposite
    // transfers can't deadlock each other. The amount moved is the first change's (the payer's).
    private Outcome checked(Statements sql, List<Op.Change> changes, long[] commit, List<Entry> saved, Map<Account, Long> after)
        throws SQLException {
        List<Op.Change> ordered = new ArrayList<>(changes);
        ordered.sort(java.util.Comparator.comparing(change -> change.account().id()));
        long moved = 0;
        long time = System.currentTimeMillis();
        for (Op.Change change : ordered) {
            Account account = change.account();
            long delta;
            if (change.setTo() >= 0) {
                delta = sql.setTo(account, change.setTo());
            } else {
                delta = change.delta();
                if (delta < 0) {
                    if (!sql.take(account, -delta)) {
                        return Outcome.failed(Status.INSUFFICIENT_FUNDS, 0);
                    }
                } else if (delta > 0) {
                    sql.add(account, delta);
                }
            }
            if (change == changes.getFirst()) {
                moved = Math.abs(delta);
            }
            if (delta == 0) {
                continue;
            }
            long balance = sql.balance(account);
            Entry row = new Entry(account, delta, balance, change.cause(), change.other(), time);
            sql.ledger(row, commit);
            saved.add(row);
            after.put(account, balance);
        }
        for (Op.Change change : changes) {
            after.putIfAbsent(change.account(), sql.balance(change.account()));
        }
        return new Outcome(Status.SUCCESS, moved, 0);
    }

    // The next chunk of an import, as sets the database makes. Returns where the next one starts.
    private int importChunk(Statements sql, Op.Import importing, long[] commit, List<Entry> saved, Map<Account, Long> after)
        throws SQLException {
        int from = importing.next()[0];
        int end = Math.min(importing.targets().size(), from + IMPORT_CHUNK);
        List<Op.Change> changes = new ArrayList<>(end - from);
        for (Ledger.Target target : importing.targets().subList(from, end)) {
            changes.add(new Op.Change(target.account(), 0, target.balance(), importing.cause(), null));
        }
        checked(sql, changes, commit, saved, after);
        return end;
    }

    // On a network, the first server to make a player's account gives the starting balance.
    private void create(Statements sql, Op.Create create, long[] commit, List<Entry> saved, Map<Account, Long> after)
        throws SQLException {
        Account account = create.account();
        sql.account(account);
        if (sql.insertBalance(account, create.start()) && create.start() > 0) {
            Entry row = new Entry(account, create.start(), create.start(), create.cause(), null, System.currentTimeMillis());
            sql.ledger(row, commit);
            saved.add(row);
        }
        after.put(account, sql.balance(account));
    }

    /** Reads an account from the database into memory, keeping this server's unsaved changes on top. */
    private void refresh(Connection db, UUID id) throws SQLException {
        String name = null;
        boolean player = true;
        Long balance = null;
        try (PreparedStatement query = db.prepareStatement(database.sql("SELECT name, player FROM {p}accounts WHERE uuid = ?"))) {
            query.setString(1, id.toString());
            try (ResultSet result = query.executeQuery()) {
                if (result.next()) {
                    name = result.getString(1);
                    player = result.getInt(2) != 0;
                }
            }
        }
        try (PreparedStatement query = db.prepareStatement(database.sql("SELECT balance FROM {p}balances WHERE uuid = ? AND currency = ?"))) {
            query.setString(1, id.toString());
            query.setString(2, currency);
            try (ResultSet result = query.executeQuery()) {
                if (result.next()) {
                    balance = result.getLong(1);
                }
            }
        }
        db.commit();
        if (shared && (name != null || balance != null)) {
            read(id, name, player, balance == null ? 0 : balance);
        }
    }

    /** Reads every account and balance again, as at startup, keeping this server's unsaved changes on top. */
    private void resync(Connection db) throws SQLException {
        if (!shared) {
            return;
        }
        List<Store.Stored> all = new ArrayList<>();
        Store.readAll(database, db, all::add);
        db.commit();
        for (Store.Stored stored : all) {
            read(stored.id(), stored.name(), stored.player(), stored.balance());
        }
    }

    // One account as the database has it: new ones are added, known ones set to the stored
    // balance plus what this server hasn't saved yet.
    private void read(UUID id, String name, boolean player, long stored) {
        Account account = accounts.get(id);
        if (account == null) {
            accounts.load(id, name == null ? id.toString().substring(0, 8) : name, player, stored);
            return;
        }
        synchronized (account) {
            if (name != null && !name.equals(account.storedName())) {
                // Renamed on another server: a player joining there under it, most likely.
                accounts.renamedElsewhere(account, name);
            }
            accounts.set(account, stored + account.pending);
        }
    }

    private void prune(Connection db, Op.Prune prune) throws SQLException {
        long oldest;
        long newest;
        try (PreparedStatement query = db.prepareStatement(database.sql("SELECT MIN(id), MAX(id) FROM {p}transactions WHERE time < ?"))) {
            query.setLong(1, prune.before());
            try (ResultSet result = query.executeQuery()) {
                if (!result.next() || result.getObject(1) == null) {
                    db.commit();
                    return;
                }
                oldest = result.getLong(1);
                newest = result.getLong(2);
            }
        }
        long upTo = Math.min(newest, oldest + PRUNE_SLICE - 1);
        int deleted;
        try (PreparedStatement delete = db.prepareStatement(database.sql("DELETE FROM {p}transactions WHERE id <= ? AND time < ?"))) {
            delete.setLong(1, upTo);
            delete.setLong(2, prune.before());
            deleted = delete.executeUpdate();
        }
        db.commit();
        if (upTo < newest) {
            // The rest after whatever else is waiting, so a big prune never holds up payments.
            queue.add(prune);
        } else if (deleted > 0 && database.dialect() == Dialect.SQLITE) {
            try (var statement = db.createStatement()) {
                statement.execute("PRAGMA incremental_vacuum(1000)");
            }
            db.commit();
        }
    }

    // ---- connection ----

    private Connection connection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            connection = wrap.apply(database.connect());
            connection.setAutoCommit(false);
            if (shared && database.dialect() == Dialect.MYSQL) {
                // A caller may be waiting on a started change: never five minutes for one reply.
                connection.setNetworkTimeout(Runnable::run, NETWORK_TIMEOUT_MS);
            }
        }
        return connection;
    }

    private void rollback() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.rollback();
            }
        } catch (SQLException ignored) {
            // The connection is being replaced anyway.
        }
    }

    private void close() {
        try {
            if (connection != null) {
                connection.close();
            }
        } catch (SQLException ignored) {
            // Nothing to save on a connection that's going.
        }
        connection = null;
    }

    /** The writer's statements on one connection, prepared once per batch. */
    private final class Statements implements AutoCloseable {
        private final Connection db;
        private PreparedStatement upsert;
        private PreparedStatement insertIgnore;
        private PreparedStatement take;
        private PreparedStatement select;
        private PreparedStatement ledger;
        private PreparedStatement accountUpsert;
        private PreparedStatement compareSet;

        Statements(Connection db) {
            this.db = db;
        }

        private PreparedStatement prepare(PreparedStatement existing, String statement) throws SQLException {
            return existing != null ? existing : db.prepareStatement(database.sql(statement));
        }

        /**
         * Adds to a balance, making its row if there's none. One statement either way, so two
         * servers making the same row at once add to it rather than fail or deadlock (an
         * update that finds no row takes a gap lock on MySQL). On a network, returns the
         * balance after.
         */
        long add(Account account, long delta) throws SQLException {
            upsert = prepare(upsert, database.dialect() == Dialect.MYSQL
                ? "INSERT INTO {p}balances (uuid, currency, balance) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE balance = balance + VALUES(balance)"
                : "INSERT INTO {p}balances (uuid, currency, balance) VALUES (?, ?, ?)"
                    + " ON CONFLICT (uuid, currency) DO UPDATE SET balance = balance + excluded.balance");
            upsert.setString(1, account.id().toString());
            upsert.setString(2, currency);
            upsert.setLong(3, delta);
            upsert.executeUpdate();
            return shared ? balance(account) : 0;
        }

        /** Adds a balance's row, unless one is already there (another server's): then false. */
        boolean insertBalance(Account account, long balance) throws SQLException {
            insertIgnore = prepare(insertIgnore, database.dialect().insertIgnore() + " INTO {p}balances (uuid, currency, balance) VALUES (?, ?, ?)");
            insertIgnore.setString(1, account.id().toString());
            insertIgnore.setString(2, currency);
            insertIgnore.setLong(3, balance);
            return insertIgnore.executeUpdate() > 0;
        }

        /** Takes from a shared balance only if it covers the amount. */
        boolean take(Account account, long amount) throws SQLException {
            take = prepare(take, "UPDATE {p}balances SET balance = balance - ? WHERE uuid = ? AND currency = ? AND balance >= ?");
            take.setLong(1, amount);
            take.setString(2, account.id().toString());
            take.setString(3, currency);
            take.setLong(4, amount);
            return take.executeUpdate() > 0;
        }

        /** Sets a balance whatever it was, and returns how much that changed it by. */
        long setTo(Account account, long target) throws SQLException {
            for (int attempt = 0; attempt < 10; attempt++) {
                long current = balanceOrMissing(account);
                if (current < 0) {
                    if (insertBalance(account, target)) {
                        return target;
                    }
                    continue;
                }
                compareSet = prepare(compareSet, "UPDATE {p}balances SET balance = ? WHERE uuid = ? AND currency = ? AND balance = ?");
                compareSet.setLong(1, target);
                compareSet.setString(2, account.id().toString());
                compareSet.setString(3, currency);
                compareSet.setLong(4, current);
                if (compareSet.executeUpdate() > 0) {
                    return target - current;
                }
            }
            throw new SQLException("The balance kept changing under a set; trying again.");
        }

        long balance(Account account) throws SQLException {
            long balance = balanceOrMissing(account);
            return Math.max(0, balance);
        }

        private long balanceOrMissing(Account account) throws SQLException {
            select = prepare(select, "SELECT balance FROM {p}balances WHERE uuid = ? AND currency = ?");
            select.setString(1, account.id().toString());
            select.setString(2, currency);
            try (ResultSet result = select.executeQuery()) {
                return result.next() ? result.getLong(1) : -1;
            }
        }

        void ledger(Entry entry, long[] commit) throws SQLException {
            ledger = prepare(ledger, "INSERT INTO {p}transactions (time, uuid, currency, amount, balance, kind, source, reason, other, commit_id)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
            ledger.setLong(1, entry.time());
            ledger.setString(2, entry.account().id().toString());
            ledger.setString(3, currency);
            ledger.setLong(4, entry.delta());
            ledger.setLong(5, entry.after());
            ledger.setString(6, entry.cause().kind().name());
            ledger.setString(7, entry.cause().source());
            ledger.setString(8, entry.cause().reason());
            if (entry.other() == null) {
                ledger.setNull(9, Types.CHAR);
            } else {
                ledger.setString(9, entry.other().toString());
            }
            if (commit[0] > 0) {
                ledger.setLong(10, commit[0]);
                commit[0] = 0;
            } else {
                ledger.setNull(10, Types.BIGINT);
            }
            ledger.executeUpdate();
        }

        /** An account's row, made or renamed in one statement, for the same reason as {@link #add}. */
        void account(Account account) throws SQLException {
            String name = account.storedName();
            if (name.length() > Schema.NAME_LENGTH) {
                name = name.substring(0, Schema.NAME_LENGTH);
            }
            accountUpsert = prepare(accountUpsert, database.dialect() == Dialect.MYSQL
                ? "INSERT INTO {p}accounts (uuid, name, player, created) VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE name = VALUES(name)"
                : "INSERT INTO {p}accounts (uuid, name, player, created) VALUES (?, ?, ?, ?)"
                    + " ON CONFLICT (uuid) DO UPDATE SET name = excluded.name");
            accountUpsert.setString(1, account.id().toString());
            accountUpsert.setString(2, name);
            accountUpsert.setInt(3, account.player() ? 1 : 0);
            accountUpsert.setLong(4, System.currentTimeMillis());
            accountUpsert.executeUpdate();
        }

        @Override
        public void close() {
            for (PreparedStatement statement : new PreparedStatement[] {upsert, insertIgnore, take, select, ledger, accountUpsert, compareSet}) {
                if (statement != null) {
                    try {
                        statement.close();
                    } catch (SQLException ignored) {
                        // Closing; nothing to do.
                    }
                }
            }
        }
    }
}
