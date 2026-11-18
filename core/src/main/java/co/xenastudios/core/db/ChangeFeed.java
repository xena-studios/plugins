package co.xenastudios.core.db;

import co.xenastudios.core.task.Tasks;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Changes one server tells the others about, for plugins on several servers sharing a
 * MySQL or MariaDB database: a row in the plugin's {@code changes} table, which every
 * other server reads within a few seconds and hands to whatever subscribed to its topic.
 * A ban, a payment, a renamed home: the server that made it changed its own memory
 * already; the others hear about it here and update theirs.
 *
 * <p>Only on while {@link NetworkSettings#enabled()} is on and the database is MySQL or
 * MariaDB. Otherwise publishing does nothing and nothing is checked, so a single server
 * pays nothing for it.
 *
 * <p>While it's on, one task off the server's threads checks every
 * {@link NetworkSettings#checkEvery()} seconds for rows newer than the last it saw (by
 * the table's key, so it's one index range), skips its own server's, and hands each to
 * the topic's subscribers in order. Changes from before this server started aren't
 * delivered: a plugin reads what it needs from its tables when it starts. Rows older
 * than ten minutes are deleted.
 */
public final class ChangeFeed implements AutoCloseable {

    /** Most rows read per check; more wait for the next one. */
    static final int BATCH = 500;
    /** Longest payload, in characters. Send ids and let subscribers read the rest. */
    public static final int MAX_PAYLOAD = 1024;
    /** How long a missing id is waited for: a transaction that took its id but hasn't committed. */
    static final long GAP_WAIT_MS = 15_000;
    /** Ids skipped at once beyond which they aren't waited for (a big rollback, or another cluster's numbering). */
    static final int MAX_GAP = 1_000;
    static final long KEEP_MS = Duration.ofMinutes(10).toMillis();
    static final long PRUNE_EVERY_MS = Duration.ofMinutes(1).toMillis();

    /** One change, as read back. {@code created} is the publishing server's clock, in epoch milliseconds. */
    public record Change(long id, String topic, String payload, long created) {}

    private final Database database;
    private final String origin;
    private final Duration period;
    private final Logger logger;
    private final boolean on;
    private final Map<String, List<Consumer<Change>>> subscribers = new ConcurrentHashMap<>();
    private final AtomicBoolean checking = new AtomicBoolean();

    // Owned by whichever thread is checking (one at a time, through `checking`).
    private long high;
    private final TreeMap<Long, Long> gaps = new TreeMap<>();
    private long lastPrune;
    private boolean failing;

    private volatile ScheduledTask task;
    private volatile boolean closed;

    ChangeFeed(Database database, String origin, Duration period, Logger logger, boolean on) {
        this.database = database;
        this.origin = origin;
        this.period = period;
        this.logger = logger;
        this.on = on;
    }

    /** A feed that's off: publishing does nothing and subscribers never hear anything. */
    public static ChangeFeed off() {
        return new ChangeFeed(null, null, Duration.ZERO, null, false);
    }

    /**
     * Opens the feed the settings ask for, making its table if it's on. Call it off the
     * server's threads (it reads the database), then {@link #start} it. With network mode
     * on but an SQLite database, it's off, and the console is told why.
     *
     * @throws DatabaseException if the table couldn't be made or read
     */
    public static ChangeFeed open(Database database, NetworkSettings settings, Logger logger) throws DatabaseException {
        if (!settings.enabled()) {
            return off();
        }
        if (database.dialect() != Dialect.MYSQL) {
            logger.warning("network.enabled is on, but storage.type is sqlite. Servers can only share changes through"
                + " a MySQL or MariaDB database they all use; until then, network mode is off.");
            return off();
        }
        ChangeFeed feed = new ChangeFeed(database, newOrigin(), Duration.ofSeconds(settings.checkEvery()), logger, true);
        feed.prepare();
        return feed;
    }

    static String newOrigin() {
        byte[] bytes = new byte[8];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** Makes the table if it's missing and starts from its newest row. */
    void prepare() throws DatabaseException {
        try (Connection connection = database.connect()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute(database.sql("CREATE TABLE IF NOT EXISTS {p}changes (id {rowid},"
                    + " origin VARCHAR(16) NOT NULL, topic VARCHAR(64) NOT NULL, payload VARCHAR(" + MAX_PAYLOAD + ") NOT NULL,"
                    + " created BIGINT NOT NULL){table}"));
            }
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(database.sql("SELECT MAX(id) FROM {p}changes"))) {
                high = result.next() ? result.getLong(1) : 0;
            }
            lastPrune = System.currentTimeMillis();
        } catch (SQLException e) {
            throw new DatabaseException("Couldn't set up network mode's changes table in " + database.description()
                + ": " + e.getMessage(), e);
        }
    }

    /** Whether this server shares changes with others. */
    public boolean on() {
        return on;
    }

    /** Starts checking for the other servers' changes, if the feed is on. */
    public void start(Tasks tasks) {
        if (on && task == null && !closed) {
            task = tasks.asyncRepeating(this::check, period, period);
        }
    }

    /** Stops checking. Changes already published stay for the other servers. */
    @Override
    public void close() {
        closed = true;
        ScheduledTask running = task;
        if (running != null) {
            running.cancel();
            task = null;
        }
    }

    /**
     * Hands {@code topic}'s changes from other servers to {@code subscriber}, oldest
     * first, on the feed's own thread (never a game thread): a subscriber that touches
     * the world or players goes through {@link Tasks}. Subscribe before {@link #start}.
     */
    public void subscribe(String topic, Consumer<Change> subscriber) {
        subscribers.computeIfAbsent(topic, key -> new CopyOnWriteArrayList<>()).add(subscriber);
    }

    /**
     * Tells the other servers about a change, as part of the caller's transaction: if it
     * rolls back, so does this, so they never hear of a change that didn't happen. Does
     * nothing when the feed is off. Database work: never on a game thread.
     *
     * @param topic what changed, such as {@code "ban"}; up to 64 characters
     * @param payload what they need to find it, such as the player's uuid; up to {@link #MAX_PAYLOAD}
     */
    public void publish(Connection connection, String topic, String payload) throws SQLException {
        if (!on) {
            return;
        }
        if (topic.isEmpty() || topic.length() > 64 || payload.length() > MAX_PAYLOAD) {
            throw new IllegalArgumentException("A change's topic is 1 to 64 characters and its payload at most "
                + MAX_PAYLOAD + "; send an id and let the other servers read the rest.");
        }
        try (PreparedStatement insert = connection.prepareStatement(
            database.sql("INSERT INTO {p}changes (origin, topic, payload, created) VALUES (?, ?, ?, ?)"))) {
            insert.setString(1, origin);
            insert.setString(2, topic);
            insert.setString(3, payload);
            insert.setLong(4, System.currentTimeMillis());
            insert.executeUpdate();
        }
    }

    /** {@link #publish(Connection, String, String)} on a connection of its own, for a change already committed. */
    public void publish(String topic, String payload) throws SQLException {
        if (!on) {
            return;
        }
        try (Connection connection = database.connect()) {
            publish(connection, topic, payload);
        }
    }

    /** One check, as the repeating task runs it. A failure is logged once, until a check works again. */
    void check() {
        if (closed || !checking.compareAndSet(false, true)) {
            return;
        }
        try {
            poll(System.currentTimeMillis());
            if (failing) {
                failing = false;
                logger.info("Network mode is reading other servers' changes again.");
            }
        } catch (SQLException | RuntimeException e) {
            if (!failing) {
                failing = true;
                logger.log(Level.WARNING, "Couldn't read other servers' changes from " + database.description()
                    + "; trying again every " + period.toSeconds() + "s.", e);
            }
        } finally {
            checking.set(false);
        }
    }

    /**
     * Reads and delivers what's new. Ids are handed out when a row is inserted, not when its
     * transaction commits, so a row can appear after a newer one: a skipped id is asked for
     * again on each check for {@link #GAP_WAIT_MS}, and delivered if it turns up.
     */
    void poll(long now) throws SQLException {
        List<Change> found = new ArrayList<>();
        List<String> origins = new ArrayList<>();
        try (Connection connection = database.connect()) {
            if (!gaps.isEmpty()) {
                String ids = gaps.keySet().stream().limit(BATCH).map(String::valueOf).collect(Collectors.joining(","));
                read(connection, "SELECT id, origin, topic, payload, created FROM {p}changes WHERE id IN (" + ids + ")",
                    null, found, origins);
            }
            read(connection, "SELECT id, origin, topic, payload, created FROM {p}changes WHERE id > ? ORDER BY id LIMIT " + BATCH,
                high, found, origins);
            for (int i = 0; i < found.size(); i++) {
                Change change = found.get(i);
                if (change.id() > high) {
                    if (change.id() - high - 1 <= MAX_GAP) {
                        for (long missing = high + 1; missing < change.id(); missing++) {
                            gaps.put(missing, now);
                        }
                    }
                    high = change.id();
                } else if (gaps.remove(change.id()) == null) {
                    continue;
                }
                if (!origin.equals(origins.get(i))) {
                    deliver(change);
                }
            }
            gaps.values().removeIf(seen -> now - seen > GAP_WAIT_MS);
            if (now - lastPrune >= PRUNE_EVERY_MS) {
                lastPrune = now;
                prune(connection, now);
            }
        }
    }

    private void read(Connection connection, String query, Long after, List<Change> found, List<String> origins) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(database.sql(query))) {
            if (after != null) {
                select.setLong(1, after);
            }
            try (ResultSet result = select.executeQuery()) {
                while (result.next()) {
                    found.add(new Change(result.getLong(1), result.getString(3), result.getString(4), result.getLong(5)));
                    origins.add(result.getString(2));
                }
            }
        }
    }

    private void deliver(Change change) {
        for (Consumer<Change> subscriber : subscribers.getOrDefault(change.topic(), List.of())) {
            try {
                subscriber.accept(change);
            } catch (RuntimeException e) {
                logger.log(Level.WARNING, "Handling another server's '" + change.topic() + "' change failed", e);
            }
        }
    }

    /**
     * Deletes rows older than {@link #KEEP_MS}, by the key range below the newest row seen
     * so the newest row always stays: a table emptied completely could hand out its ids
     * again after a restart on an old MySQL.
     */
    private void prune(Connection connection, long now) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement(
            database.sql("DELETE FROM {p}changes WHERE id < ? AND created < ?"))) {
            delete.setLong(1, gaps.isEmpty() ? high : Math.min(high, gaps.firstKey()));
            delete.setLong(2, now - KEEP_MS);
            delete.executeUpdate();
        }
    }

    // For tests.
    long high() {
        return high;
    }

    int waitingFor() {
        return gaps.size();
    }
}
