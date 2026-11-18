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
 */
public final class Writer {

    static final int BATCH = 1_000;
    /** Ledger rows deleted per slice of a prune; other writes go between slices. */
    static final int PRUNE_SLICE = 5_000;

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

    // Writer thread only.
    private Connection connection;
    private boolean failing;

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
        long lost = unsaved.get() + entries(new ArrayList<>(queue));
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
        boolean tried = false;
        while (true) {
            try {
                Connection db = connection();
                if (tried && committed(db, commitId)) {
                    alreadySaved(batch);
                } else {
                    long start = System.nanoTime();
                    run(db, batch, commitId);
                    lastBatchNanos = System.nanoTime() - start;
                    lastBatchSize = batch.size();
                }
                if (failing) {
                    failing = false;
                    problem = null;
                    logger.info("Saving balances to " + database.description() + " again.");
                }
                return;
            } catch (SQLException | RuntimeException e) {
                tried = true;
                rollback();
                close();
                problem = e.getMessage();
                if (!failing) {
                    failing = true;
                    logger.log(Level.WARNING, "Couldn't save balance changes to " + database.description()
                        + "; trying again, every " + 30 + "s at most. Balances in memory are right meanwhile.", e);
                }
                expire(batch);
                if (batch.isEmpty()) {
                    return;
                }
                if (abandon || (stopping && Thread.currentThread().isInterrupted())) {
                    unsaved.addAndGet(entries(batch));
                    return;
                }
                try {
                    Thread.sleep(pause);
                } catch (InterruptedException interrupted) {
                    if (abandon) {
                        unsaved.addAndGet(entries(batch));
                        return;
                    }
                }
                pause = Math.min(pause * 2, 30_000);
            }
        }
    }

    // A checked change not started by its deadline is given up: the caller is told nothing moved.
    private void expire(List<Op> batch) {
        long now = System.currentTimeMillis();
        batch.removeIf(op -> {
            if (op instanceof Op.Checked checked && now > checked.deadline()) {
                Account first = checked.changes().getFirst().account();
                checked.done().complete(Outcome.failed(Status.UNAVAILABLE, first.balance()));
                return true;
            }
            return false;
        });
    }

    private static long entries(List<Op> ops) {
        long count = 0;
        for (Op op : ops) {
            if (op instanceof Op.Write write) {
                count += write.entries().size();
            } else if (op instanceof Op.Checked checked) {
                count += checked.changes().size();
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

    // The commit reached the database, but its answer didn't reach us. On a single server
    // memory is already right; on a network it's read back.
    private void alreadySaved(List<Op> batch) throws SQLException {
        for (Op op : batch) {
            switch (op) {
                case Op.Write write -> {
                    written.addAndGet(write.entries().size());
                    if (shared) {
                        settle(write);
                        for (Entry entry : write.entries()) {
                            refresh(connection, entry.account().id());
                        }
                    }
                }
                case Op.Checked checked -> {
                    for (Op.Change change : checked.changes()) {
                        refresh(connection, change.account().id());
                    }
                    Account first = checked.changes().getFirst().account();
                    checked.done().complete(new Outcome(Status.SUCCESS, Math.abs(checked.changes().getFirst().delta()), first.balance()));
                }
                default -> run(connection, List.of(op), 0);
            }
        }
    }

    // ---- the work ----

    private void run(Connection db, List<Op> batch, long commitId) throws SQLException {
        Statements sql = new Statements(db);
        List<Entry> saved = new ArrayList<>();
        Map<Account, Long> after = new LinkedHashMap<>();
        Outcome checkedOutcome = null;
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
                    case Op.Name name -> sql.account(name.account());
                    case Op.Checked checked -> {
                        checkedOutcome = checked(sql, checked, commit, saved, after);
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
                    case Op.Refresh refresh -> {
                        refresh(db, refresh.id());
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
                for (Account account : after.keySet()) {
                    feed.publish(db, "account", account.id().toString());
                }
            }
            db.commit();
        } finally {
            sql.close();
        }
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
        for (Op op : batch) {
            if (op instanceof Op.Checked checked) {
                checked.done().complete(checkedOutcome.success()
                    ? new Outcome(Status.SUCCESS, checkedOutcome.amount(), after.get(checked.changes().getFirst().account()))
                    : checkedOutcome);
            }
        }
        if (!saved.isEmpty()) {
            events.saved(saved);
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

    private Outcome checked(Statements sql, Op.Checked checked, long[] commit, List<Entry> saved, Map<Account, Long> after)
        throws SQLException {
        long moved = 0;
        long time = System.currentTimeMillis();
        for (Op.Change change : checked.changes()) {
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
            if (moved == 0) {
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
        for (Op.Change change : checked.changes()) {
            after.putIfAbsent(change.account(), sql.balance(change.account()));
        }
        return new Outcome(Status.SUCCESS, moved, 0);
    }

    // On a network, the first server to make a player's account gives the starting balance.
    private void create(Statements sql, Op.Create create, long[] commit, List<Entry> saved, Map<Account, Long> after)
        throws SQLException {
        Account account = create.account();
        sql.account(account);
        if (sql.insertBalance(account, create.start(), true) && create.start() > 0) {
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
        if (!shared) {
            db.commit();
            return;
        }
        db.commit();
        if (name == null && balance == null) {
            return;
        }
        long stored = balance == null ? 0 : balance;
        Account account = accounts.get(id);
        if (account == null) {
            accounts.load(id, name == null ? id.toString().substring(0, 8) : name, player, stored);
            return;
        }
        if (name != null && !name.equals(account.name())) {
            accounts.rename(account, name);
        }
        synchronized (account) {
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
            connection = database.connect();
            connection.setAutoCommit(false);
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
        private PreparedStatement add;
        private PreparedStatement insert;
        private PreparedStatement insertIgnore;
        private PreparedStatement take;
        private PreparedStatement select;
        private PreparedStatement ledger;
        private PreparedStatement accountUpdate;
        private PreparedStatement accountInsert;
        private PreparedStatement compareSet;

        Statements(Connection db) {
            this.db = db;
        }

        private PreparedStatement prepare(PreparedStatement existing, String statement) throws SQLException {
            return existing != null ? existing : db.prepareStatement(database.sql(statement));
        }

        /** Adds to a balance, making its row if there's none. On a network, returns the balance after. */
        long add(Account account, long delta) throws SQLException {
            add = prepare(add, "UPDATE {p}balances SET balance = balance + ? WHERE uuid = ? AND currency = ?");
            add.setLong(1, delta);
            add.setString(2, account.id().toString());
            add.setString(3, currency);
            if (add.executeUpdate() == 0) {
                // No row means a balance of 0. Two servers adding the first row at once: one
                // fails on the key, and its retry adds to the other's row.
                insertBalance(account, delta, false);
            }
            return shared ? balance(account) : 0;
        }

        /**
         * Adds a balance's row. With {@code ignore}, a row already there (another server's) is
         * left as it is and false returned; without, that fails the transaction, so its retry
         * adds to the row instead.
         */
        boolean insertBalance(Account account, long balance, boolean ignore) throws SQLException {
            PreparedStatement statement = ignore ? insertIgnore : insert;
            if (statement == null) {
                statement = db.prepareStatement(database.sql(
                    (ignore ? database.dialect().insertIgnore() : "INSERT") + " INTO {p}balances (uuid, currency, balance) VALUES (?, ?, ?)"));
                if (ignore) {
                    insertIgnore = statement;
                } else {
                    insert = statement;
                }
            }
            statement.setString(1, account.id().toString());
            statement.setString(2, currency);
            statement.setLong(3, balance);
            return statement.executeUpdate() > 0;
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
                    if (insertBalance(account, target, true)) {
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

        void account(Account account) throws SQLException {
            String name = account.name() == null ? "" : account.name();
            if (name.length() > Schema.NAME_LENGTH) {
                name = name.substring(0, Schema.NAME_LENGTH);
            }
            accountUpdate = prepare(accountUpdate, "UPDATE {p}accounts SET name = ? WHERE uuid = ?");
            accountUpdate.setString(1, name);
            accountUpdate.setString(2, account.id().toString());
            if (accountUpdate.executeUpdate() > 0) {
                return;
            }
            if (accountInsert == null) {
                accountInsert = db.prepareStatement(database.sql(database.dialect().insertIgnore()
                    + " INTO {p}accounts (uuid, name, player, created) VALUES (?, ?, ?, ?)"));
            }
            accountInsert.setString(1, account.id().toString());
            accountInsert.setString(2, name);
            accountInsert.setInt(3, account.player() ? 1 : 0);
            accountInsert.setLong(4, System.currentTimeMillis());
            accountInsert.executeUpdate();
        }

        @Override
        public void close() {
            for (PreparedStatement statement : new PreparedStatement[] {add, insert, insertIgnore, take, select, ledger, accountUpdate, accountInsert, compareSet}) {
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
