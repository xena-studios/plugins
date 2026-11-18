package co.xenastudios.xbans.storage;

import co.xenastudios.core.db.ChangeFeed;
import co.xenastudios.core.db.Database;
import co.xenastudios.core.db.DatabaseException;
import co.xenastudios.core.db.Dialect;
import co.xenastudios.core.db.NetworkSettings;
import co.xenastudios.core.db.StorageSettings;
import co.xenastudios.core.task.Tasks;
import co.xenastudios.xbans.api.PunishmentType;
import co.xenastudios.xbans.api.Removal;
import co.xenastudios.xbans.ip.IpKeys;
import co.xenastudios.xbans.model.Active;
import co.xenastudios.xbans.model.Entry;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * xBans' database, and the bans and mutes in force that it loads into memory.
 *
 * <p>Opening a database (connecting, updating its tables, loading what's in force) runs on
 * xBans' database thread, never a game thread. Until the first one is open,
 * {@link #ready()} isn't complete, and the join check waits on it. A database that can't
 * be opened at startup is tried again every 15 seconds until it opens; a new one asked for
 * by a reload that can't be opened leaves the old one in use.
 *
 * <p>Writes run in order on the one database thread, each in a transaction, with network
 * mode's change in the same transaction. Reads (lookups) run on Paper's async threads, each
 * on a connection of its own, so a long lookup never holds up a ban.
 */
public final class Store {

    /** The SQLite file, in xBans' folder. */
    static final String FILE = "punishments.db";
    static final Duration RETRY = Duration.ofSeconds(15);
    /** How long after a failed write joins aren't recorded. */
    static final long SKIP_SEEN_MS = 30_000;
    public static final String TOPIC_PUNISH = "punish";
    public static final String TOPIC_REVOKE = "revoke";
    /** The reason on a ban or mute's removal when a longer one replaced it. It still counts toward its template's ladder. */
    public static final String REPLACED_REASON = "Replaced by a newer one";
    /** After a revoked id in the feed: it was replaced by a new punishment, so it isn't announced. */
    public static final String REPLACED = "|r";
    /** After a punished id in the feed: imported, so it's taken in without a broadcast. */
    public static final String QUIET = "|q";
    /** After a revoked id in the feed: revoked silently. */
    public static final String SILENT = "|s";

    /** What's open: the database, its change feed, what's in force, and how IPs are keyed. */
    public record Backend(Database database, ChangeFeed feed, Active active, IpKeys keys) {}

    /** What the config asks for. */
    public record Wanted(StorageSettings storage, NetworkSettings network, boolean hash) {}

    @FunctionalInterface
    public interface Work<T> {
        T run(Connection connection, Backend backend) throws SQLException;
    }

    private final Tasks tasks;
    private final Logger logger;
    private final Path folder;
    private final ExecutorService writer;
    private final AtomicInteger generation = new AtomicInteger();
    private final CompletableFuture<Void> ready = new CompletableFuture<>();
    private final Consumer<Backend> subscribe;
    private volatile Backend backend;
    private volatile String origin = "server";
    // Global thread only.
    private Wanted wanted;
    private volatile ScheduledTask retry;
    private volatile boolean closed;
    // When a write last failed, so joins don't each queue one while the database is down.
    private volatile long writeFailedAt;

    // Writer thread only: the last reason opening failed, so a retry that fails the same way says nothing.
    private String lastProblem;

    // Writer thread only.
    private Connection connection;
    private Database connectionFor;

    /**
     * @param subscribe called on the database thread with each newly opened backend, before
     *     its change feed starts, to subscribe to the other servers' changes
     */
    public Store(Tasks tasks, Logger logger, Path folder, Consumer<Backend> subscribe) {
        this.tasks = tasks;
        this.logger = logger;
        this.folder = folder;
        this.subscribe = subscribe;
        this.writer = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "xBans database");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Completes once the first database is open and its bans are in memory. */
    public CompletableFuture<Void> ready() {
        return ready;
    }

    /** What's open now, or null before the first database is ready. Any thread. */
    public Backend backend() {
        return backend;
    }

    /** This server's name, written beside each punishment it makes. */
    public void origin(String origin) {
        this.origin = origin;
    }

    /** Opens what the settings describe, unless it's what was already asked for. Global thread. */
    public void sync(Wanted settings) {
        if (settings.equals(wanted)) {
            return;
        }
        wanted = settings;
        int ticket = generation.incrementAndGet();
        stopRetrying();
        writer.execute(() -> open(settings, ticket));
    }

    private void open(Wanted settings, int ticket) {
        if (closed || ticket != generation.get()) {
            return;
        }
        Database opened = null;
        try {
            opened = Database.open(settings.storage(), folder.resolve(FILE));
            int schema = opened.migrate(Schema.STEPS);
            IpKeys keys = ipKeys(opened, settings.hash());
            // The feed first, so it starts from before the load: a change made while the bans
            // load is in both, and Active ignores the second copy.
            ChangeFeed feed = ChangeFeed.open(opened, settings.network(), logger);
            Active active = new Active();
            try (Connection c = opened.connect()) {
                inForce(c, opened).forEach(active::add);
            }
            if (ticket != generation.get()) {
                return; // A newer reload asked for something else meanwhile.
            }
            Backend fresh = new Backend(opened, feed, active, keys);
            subscribe.accept(fresh);
            feed.start(tasks);
            Backend old = backend;
            backend = fresh;
            if (old != null) {
                old.feed().close();
            }
            if (lastProblem != null) {
                logger.info("Opened " + opened.description() + " after all.");
                lastProblem = null;
            }
            logger.info("Loaded " + active.size() + " bans and mutes in force from " + opened.description()
                + ", schema " + schema + (feed.on() ? ", sharing changes with the network." : "."));
            ready.complete(null);
        } catch (DatabaseException | SQLException | IOException | RuntimeException e) {
            if (ticket != generation.get()) {
                return;
            }
            String what = opened != null ? opened.description() : "the database";
            String problem = "Couldn't open " + what + ": " + e.getMessage();
            // Said once: a retry every 15 seconds that fails the same way adds nothing to the log.
            boolean repeat = problem.equals(lastProblem);
            lastProblem = problem;
            if (!repeat) {
                logger.severe(problem);
                if (!(e instanceof DatabaseException || e instanceof SQLException || e instanceof IOException)) {
                    logger.log(Level.SEVERE, "The error", e);
                }
            }
            Backend current = backend;
            if (current != null) {
                logger.severe("Still using " + current.database().description() + ".");
                tasks.global(() -> {
                    if (ticket == generation.get()) {
                        wanted = null; // So reloading with the same settings tries again.
                    }
                });
            } else {
                if (!repeat) {
                    logger.severe("Nobody can join until it opens: trying again every " + RETRY.toSeconds() + "s.");
                }
                retry = tasks.asyncRepeating(() -> {
                    if (ticket == generation.get() && backend == null && !closed) {
                        stopRetrying();
                        writer.execute(() -> open(settings, ticket));
                    }
                }, RETRY, RETRY);
            }
        }
    }

    private void stopRetrying() {
        ScheduledTask task = retry;
        if (task != null) {
            task.cancel();
            retry = null;
        }
    }

    /** Stops the feed and writes what's waiting, for up to 10 seconds. */
    public void close() {
        closed = true;
        stopRetrying();
        Backend current = backend;
        if (current != null) {
            current.feed().close();
        }
        writer.shutdown();
        try {
            if (!writer.awaitTermination(10, TimeUnit.SECONDS)) {
                logger.warning("Some punishments were still being saved when the server stopped.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        closeConnection();
    }

    private void closeConnection() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // Closing anyway.
            }
            connection = null;
        }
    }

    // How IPs are keyed for this database. Hashing, once on, stays on: there's no undoing it.
    // A hashed database opens only with the salt it was hashed with: with another, or none,
    // no IP ban would match anyone, so it fails to open (and is retried) rather than letting
    // IP-banned players in, unless the owner says the salt is lost for good (IpKeys.RESET_FILE).
    private IpKeys ipKeys(Database database, boolean hash) throws SQLException, IOException {
        try (Connection c = database.connect()) {
            String mode = meta(c, database, "ip-mode");
            if ("hashed".equals(mode)) {
                return hashedKeys(c, database, hash);
            }
            if (hash) {
                IpKeys keys = IpKeys.hashed(IpKeys.salt(folder, true));
                int hashed = hashAll(c, database, keys);
                if (hashed < 0) {
                    // Another server of the network turned hashing on first: its salt is the one.
                    return hashedKeys(c, database, hash);
                }
                logger.info("IPs are hashed from now on" + (hashed > 0 ? "; hashed the " + hashed + " already stored." : "."));
                return keys;
            }
            if (mode == null) {
                setMeta(c, database, "ip-mode", "raw");
            }
            return IpKeys.raw();
        }
    }

    // A database whose IPs are hashed: the salt in xBans' folder must be the one they were hashed with.
    private IpKeys hashedKeys(Connection c, Database database, boolean hash) throws SQLException, IOException {
        String check = meta(c, database, "ip-salt-check");
        Path reset = folder.resolve(IpKeys.RESET_FILE);
        if (Files.exists(reset)) {
            // The owner says the salt is lost for good: start over with the one here (or a new one).
            byte[] salt;
            try {
                salt = IpKeys.salt(folder, true);
            } catch (IOException unreadable) {
                // Not a salt at all: kept aside, and a new one made.
                Path file = folder.resolve(IpKeys.SALT_FILE);
                Files.move(file, folder.resolve(IpKeys.SALT_FILE + ".old"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                salt = IpKeys.salt(folder, true);
            }
            IpKeys keys = IpKeys.hashed(salt);
            setMeta(c, database, "ip-salt-check", keys.fingerprint());
            Files.delete(reset);
            if (!keys.fingerprint().equals(check)) {
                logger.warning("Started over with the salt in " + IpKeys.SALT_FILE + ", as " + IpKeys.RESET_FILE
                    + " asked: IP bans and alts recorded before no longer match anyone. Copy " + IpKeys.SALT_FILE
                    + " to every other server sharing this database.");
            }
            return keys;
        }
        IpKeys keys = IpKeys.hashed(IpKeys.salt(folder, false));
        if (check != null && !check.equals(keys.fingerprint())) {
            throw new IOException(IpKeys.SALT_FILE + " isn't the one this database's IPs were hashed with, so no IP"
                + " ban or alt would match. Copy the right one into xBans' folder from the server that hashed"
                + " them (or from a backup). " + IpKeys.LOST);
        }
        if (check == null) {
            setMeta(c, database, "ip-salt-check", keys.fingerprint());
        }
        if (!hash) {
            logger.warning("ips.hash is off, but this database's IPs are already hashed, so they stay hashed.");
        }
        return keys;
    }

    /**
     * Hashes every IP stored, in one transaction with the switch itself, so a crash leaves
     * the database as it was rather than half hashed (and hashed twice at the next start).
     * The switch is claimed first: of several servers turning hashing on at once, one does
     * it, and the others use its salt.
     *
     * @return how many addresses were hashed, or -1 if another server had claimed the switch
     */
    private int hashAll(Connection c, Database database, IpKeys keys) throws SQLException {
        c.setAutoCommit(false);
        try {
            try (PreparedStatement claim = c.prepareStatement(database.sql(database.dialect().insertIgnore()
                + " INTO {p}meta (name, value) VALUES ('ip-salt-check', ?)"))) {
                claim.setString(1, keys.fingerprint());
                if (claim.executeUpdate() == 0) {
                    c.rollback();
                    return -1;
                }
            }
            List<String> raw = new ArrayList<>();
            try (Statement select = c.createStatement();
                 ResultSet rows = select.executeQuery(database.sql(
                     "SELECT DISTINCT ip FROM {p}ips UNION SELECT DISTINCT ip FROM {p}punishments WHERE ip IS NOT NULL"))) {
                while (rows.next()) {
                    raw.add(rows.getString(1));
                }
            }
            rekeyImports(c, database, keys);
            try (PreparedStatement ips = c.prepareStatement(database.sql("UPDATE {p}ips SET ip = ? WHERE ip = ?"));
                 PreparedStatement punishments = c.prepareStatement(database.sql("UPDATE {p}punishments SET ip = ? WHERE ip = ?"))) {
                for (String address : raw) {
                    String key = keys.key(address);
                    for (PreparedStatement update : List.of(ips, punishments)) {
                        update.setString(1, key);
                        update.setString(2, address);
                        update.addBatch();
                    }
                }
                ips.executeBatch();
                punishments.executeBatch();
            }
            renameIpPunishments(c, database);
            setMeta(c, database, "ip-mode", "hashed");
            c.commit();
            return raw.size();
        } catch (SQLException | RuntimeException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(true);
        }
    }

    /** Where an imported IP ban came from names its IP, keyed as the database keys it: {@code vanilla:ip:<key>:<created>}. */
    public static final String VANILLA_IP = "vanilla:ip:";

    // An imported vanilla IP ban remembers where it came from by its IP, so the address is
    // swapped for its hash there too. Before the IPs themselves are: it's matched by the row's ip.
    private static void rekeyImports(Connection c, Database database, IpKeys keys) throws SQLException {
        List<Object[]> rows = new ArrayList<>();
        try (PreparedStatement select = c.prepareStatement(database.sql(
            "SELECT id, ip, imported FROM {p}punishments WHERE imported LIKE ?"))) {
            select.setString(1, VANILLA_IP + "%");
            try (ResultSet found = select.executeQuery()) {
                while (found.next()) {
                    rows.add(new Object[] {found.getLong(1), found.getString(2), found.getString(3)});
                }
            }
        }
        try (PreparedStatement update = c.prepareStatement(database.sql("UPDATE {p}punishments SET imported = ? WHERE id = ?"))) {
            for (Object[] row : rows) {
                String ip = (String) row[1];
                String imported = (String) row[2];
                String prefix = VANILLA_IP + ip;
                String key = ip == null ? "?" : keys.key(ip);
                // Its date after the address; one that doesn't start so (written by hand) keeps no part of it.
                String rest = ip != null && imported.startsWith(prefix) ? imported.substring(prefix.length()) : ":#" + row[0];
                update.setString(1, VANILLA_IP + key + rest);
                update.setLong(2, (Long) row[0]);
                update.addBatch();
            }
            update.executeBatch();
        }
    }

    // An IP punishment with no account is named after its IP (masked, or the start of its
    // hash). Once the IPs are hashed, those names are too, so no part of an address is left.
    private static void renameIpPunishments(Connection c, Database database) throws SQLException {
        List<Object[]> rows = new ArrayList<>();
        try (Statement select = c.createStatement();
             ResultSet found = select.executeQuery(database.sql("SELECT id, ip FROM {p}punishments WHERE uuid IS NULL"))) {
            while (found.next()) {
                rows.add(new Object[] {found.getLong(1), found.getString(2)});
            }
        }
        try (PreparedStatement update = c.prepareStatement(database.sql("UPDATE {p}punishments SET name = ? WHERE id = ?"))) {
            for (Object[] row : rows) {
                update.setString(1, IpKeys.label((String) row[1]));
                update.setLong(2, (Long) row[0]);
                update.addBatch();
            }
            update.executeBatch();
        }
    }

    private static String meta(Connection c, Database database, String name) throws SQLException {
        try (PreparedStatement select = c.prepareStatement(database.sql("SELECT value FROM {p}meta WHERE name = ?"))) {
            select.setString(1, name);
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? row.getString(1) : null;
            }
        }
    }

    private static void setMeta(Connection c, Database database, String name, String value) throws SQLException {
        try (PreparedStatement update = c.prepareStatement(database.sql("UPDATE {p}meta SET value = ? WHERE name = ?"))) {
            update.setString(1, value);
            update.setString(2, name);
            if (update.executeUpdate() > 0) {
                return;
            }
        }
        try (PreparedStatement insert = c.prepareStatement(database.sql("INSERT INTO {p}meta (name, value) VALUES (?, ?)"))) {
            insert.setString(1, name);
            insert.setString(2, value);
            insert.executeUpdate();
        }
    }

    // Every ban and mute in force. The index on (type, expires) keeps this to the rows wanted.
    private static List<Entry> inForce(Connection c, Database database) throws SQLException {
        List<Entry> found = new ArrayList<>();
        try (PreparedStatement select = c.prepareStatement(database.sql(Rows.SELECT
            + " WHERE p.type IN (1, 2) AND (p.expires = 0 OR p.expires > ?) AND r.punishment IS NULL"))) {
            select.setLong(1, System.currentTimeMillis());
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    found.add(Rows.read(rows));
                }
            }
        }
        return found;
    }

    /**
     * Reads every ban and mute in force again and brings memory in line with it: for when
     * this server may have missed changes (a change it couldn't read, or a network feed that
     * fell behind). Anything that changed in memory while it was read is left as it is.
     *
     * @return the bans and mutes that were missing from memory, to be enforced
     */
    public CompletableFuture<List<Entry>> reloadInForce() {
        return read((c, b) -> {
            Active.Sync sync = b.active().beginSync();
            try {
                return sync.finish(inForce(c, b.database()));
            } catch (SQLException | RuntimeException e) {
                sync.abandon();
                throw e;
            }
        });
    }

    // ---- Running work ----

    /** Runs database work on the writer thread, in a transaction, after everything queued before it. */
    public <T> CompletableFuture<T> write(Work<T> work) {
        CompletableFuture<T> result = new CompletableFuture<>();
        try {
            writer.execute(() -> {
                Backend current = backend;
                if (current == null) {
                    result.completeExceptionally(new IllegalStateException("The database isn't open yet."));
                    return;
                }
                try {
                    Connection c = connection(current.database());
                    c.setAutoCommit(false);
                    try {
                        T value = work.run(c, current);
                        c.commit();
                        writeFailedAt = 0;
                        result.complete(value);
                    } catch (SQLException | RuntimeException e) {
                        try {
                            c.rollback();
                        } catch (SQLException ignored) {
                            // The connection is likely gone; it's replaced next time.
                        }
                        throw e;
                    } finally {
                        try {
                            c.setAutoCommit(true);
                        } catch (SQLException ignored) {
                            closeConnection();
                        }
                    }
                } catch (SQLException | RuntimeException e) {
                    if (e instanceof SQLException) {
                        writeFailedAt = System.currentTimeMillis();
                    }
                    result.completeExceptionally(e);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            result.completeExceptionally(new IllegalStateException("xBans is stopping.", e));
        }
        return result;
    }

    /** Runs a read off the server's threads on a connection of its own. */
    public <T> CompletableFuture<T> read(Work<T> work) {
        CompletableFuture<T> result = new CompletableFuture<>();
        Backend current = backend;
        if (current == null) {
            result.completeExceptionally(new IllegalStateException("The database isn't open yet."));
            return result;
        }
        try {
            tasks.async(() -> {
                try (Connection c = current.database().connect()) {
                    result.complete(work.run(c, current));
                } catch (SQLException | RuntimeException e) {
                    result.completeExceptionally(e);
                }
            });
        } catch (RuntimeException e) {
            result.completeExceptionally(e);
        }
        return result;
    }

    private Connection connection(Database database) throws SQLException {
        if (connection != null && connectionFor == database) {
            try {
                if (database.dialect() == Dialect.SQLITE || connection.isValid(2)) {
                    return connection;
                }
            } catch (SQLException ignored) {
                // Replaced below.
            }
        }
        closeConnection();
        connection = database.connect();
        connectionFor = database;
        return connection;
    }

    // ---- Writes ----

    /** A stored punishment, and (for a warning) how many the player now has in force, counted in the same transaction. */
    public record Stored(Entry entry, int warnings) {}

    /** A punishment it replaces was revoked meanwhile (by another command, or another server). Nothing was stored. */
    public static final class Conflict extends RuntimeException {
        private final long id;

        Conflict(long id) {
            super("Punishment #" + id + ", which it replaces, was revoked meanwhile", null, false, false);
            this.id = id;
        }

        /** The punishment that was revoked meanwhile. */
        public long id() {
            return id;
        }
    }

    /**
     * Stores a punishment, first revoking those it replaces, and tells the network. If one
     * it replaces turns out to be revoked already, nothing is stored and it fails with
     * {@link Conflict}: two replacements at once can't leave two in force.
     *
     * @param replaces bans or mutes the new one covers and outlasts, or an empty list
     * @return the punishment with its id
     */
    public CompletableFuture<Stored> insert(Entry draft, List<Entry> replaces, Removal replacedBy) {
        String server = origin;
        return write((c, b) -> {
            for (Entry replaced : replaces) {
                if (!removal(c, b, replaced.id(), replacedBy)) {
                    throw new Conflict(replaced.id());
                }
            }
            long id;
            try (PreparedStatement insert = c.prepareStatement(b.database().sql(Rows.INSERT), Statement.RETURN_GENERATED_KEYS)) {
                Rows.bind(insert, draft, server, null);
                insert.executeUpdate();
                try (ResultSet keys = insert.getGeneratedKeys()) {
                    if (!keys.next()) {
                        throw new SQLException("The database gave the new punishment no id.");
                    }
                    id = keys.getLong(1);
                }
            }
            for (Entry replaced : replaces) {
                b.feed().publish(c, TOPIC_REVOKE, replaced.id() + REPLACED);
            }
            b.feed().publish(c, TOPIC_PUNISH, Long.toString(id));
            UUID target = draft.punishment().target();
            int warnings = draft.type() == PunishmentType.WARN && target != null ? activeWarnings(c, b, target).size() : 0;
            return new Stored(draft.withId(id), warnings);
        });
    }

    /**
     * Records that a punishment was revoked, and tells the network.
     *
     * @return false if it had been revoked already (on another server, say)
     */
    public CompletableFuture<Boolean> revoke(long id, Removal removal, boolean silent) {
        return write((c, b) -> {
            if (!removal(c, b, id, removal)) {
                return false;
            }
            b.feed().publish(c, TOPIC_REVOKE, id + (silent ? SILENT : ""));
            return true;
        });
    }

    // One statement, so two servers revoking the same punishment at once can't both get in:
    // the second's row is ignored (the key is the punishment), and it's told so.
    private static boolean removal(Connection c, Backend b, long id, Removal removal) throws SQLException {
        try (PreparedStatement insert = c.prepareStatement(b.database().sql(b.database().dialect().insertIgnore()
            + " INTO {p}removals (punishment, staff_uuid, staff_name, removed, reason) VALUES (?, ?, ?, ?, ?)"))) {
            insert.setLong(1, id);
            Rows.setUuid(insert, 2, removal.staff());
            insert.setString(3, Rows.clip(removal.staffName(), 64));
            insert.setLong(4, removal.at().toEpochMilli());
            insert.setString(5, Rows.clip(removal.reason(), 1024));
            return insert.executeUpdate() > 0;
        }
    }

    /**
     * Stores an imported punishment, with its removal if it had one, unless a row from the
     * same place was imported before. Part of the caller's transaction.
     *
     * @param imported where it came from, such as {@code "litebans:bans:12"}
     * @return its new id, or -1 if it was already imported
     */
    public static long insertImported(Connection c, Backend b, Entry entry, String imported) throws SQLException {
        try (PreparedStatement exists = c.prepareStatement(b.database().sql("SELECT 1 FROM {p}punishments WHERE imported = ?"))) {
            exists.setString(1, Rows.clip(imported, 96));
            try (ResultSet row = exists.executeQuery()) {
                if (row.next()) {
                    return -1;
                }
            }
        }
        long id;
        try (PreparedStatement insert = c.prepareStatement(b.database().sql(Rows.INSERT), Statement.RETURN_GENERATED_KEYS)) {
            Rows.bind(insert, entry, "import", imported);
            insert.executeUpdate();
            try (ResultSet keys = insert.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("The database gave the imported punishment no id.");
                }
                id = keys.getLong(1);
            }
        }
        if (entry.punishment().removal() != null) {
            removal(c, b, id, entry.punishment().removal());
        }
        return id;
    }

    /** Adds a player an import knows about, unless they're already known. Part of the caller's transaction. */
    public static void importPlayer(Connection c, Backend b, UUID id, String name, long first, long last) throws SQLException {
        try (PreparedStatement insert = c.prepareStatement(b.database().sql(b.database().dialect().insertIgnore()
            + " INTO {p}players (uuid, name, first_seen, last_seen, weight, exempt) VALUES (?, ?, ?, ?, 0, 0)"))) {
            insert.setString(1, id.toString());
            insert.setString(2, Rows.clip(name, 32));
            insert.setLong(3, first);
            insert.setLong(4, last);
            insert.executeUpdate();
        }
    }

    /** Records a join: the player's name, weight and exemption, and their IP key if there is one. */
    public void seen(UUID id, String name, String ip, int weight, boolean exempt) {
        long now = System.currentTimeMillis();
        if (now - writeFailedAt < SKIP_SEEN_MS) {
            // The database is failing: a join doesn't queue a write that will fail too.
            return;
        }
        write((c, b) -> {
            try (PreparedStatement update = c.prepareStatement(b.database().sql(
                "UPDATE {p}players SET name = ?, last_seen = ?, weight = ?, exempt = ? WHERE uuid = ?"))) {
                update.setString(1, name);
                update.setLong(2, now);
                update.setInt(3, weight);
                update.setInt(4, exempt ? 1 : 0);
                update.setString(5, id.toString());
                if (update.executeUpdate() == 0) {
                    try (PreparedStatement insert = c.prepareStatement(b.database().sql(
                        "INSERT INTO {p}players (uuid, name, first_seen, last_seen, weight, exempt) VALUES (?, ?, ?, ?, ?, ?)"))) {
                        insert.setString(1, id.toString());
                        insert.setString(2, name);
                        insert.setLong(3, now);
                        insert.setLong(4, now);
                        insert.setInt(5, weight);
                        insert.setInt(6, exempt ? 1 : 0);
                        insert.executeUpdate();
                    }
                }
            }
            if (ip != null) {
                touchIp(c, b, id, ip, now, now);
            }
            return null;
        }).exceptionally(e -> {
            logger.warning("Couldn't record " + name + "'s join: " + e.getMessage());
            return null;
        });
    }

    /** Adds or refreshes one account's use of an IP. Part of the caller's transaction. */
    public static void touchIp(Connection c, Backend b, UUID id, String ip, long first, long last) throws SQLException {
        try (PreparedStatement update = c.prepareStatement(b.database().sql(
            "UPDATE {p}ips SET last_seen = ? WHERE uuid = ? AND ip = ? AND last_seen < ?"))) {
            update.setLong(1, last);
            update.setString(2, id.toString());
            update.setString(3, ip);
            update.setLong(4, last);
            if (update.executeUpdate() > 0) {
                return;
            }
        }
        try (PreparedStatement insert = c.prepareStatement(b.database().sql(
            b.database().dialect().insertIgnore() + " INTO {p}ips (uuid, ip, first_seen, last_seen) VALUES (?, ?, ?, ?)"))) {
            insert.setString(1, id.toString());
            insert.setString(2, ip);
            insert.setLong(3, first);
            insert.setLong(4, last);
            insert.executeUpdate();
        }
    }

    /**
     * Deletes a player's punishments that are no longer in force (ended, revoked, kicks), made
     * at or after {@code since}: /prunehistory, the only delete.
     *
     * @param dryRun count them without deleting
     * @return how many
     */
    public CompletableFuture<Integer> prune(UUID target, long since, boolean dryRun) {
        Work<Integer> work = (c, b) -> {
            List<Long> ids = new ArrayList<>();
            long now = System.currentTimeMillis();
            try (PreparedStatement select = c.prepareStatement(b.database().sql(Rows.SELECT
                + " WHERE p.uuid = ? AND p.created >= ?"))) {
                select.setString(1, target.toString());
                select.setLong(2, since);
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) {
                        Entry entry = Rows.read(rows);
                        if (!entry.active(now)) {
                            ids.add(entry.id());
                        }
                    }
                }
            }
            if (!dryRun) {
                for (String table : List.of("{p}removals WHERE punishment", "{p}punishments WHERE id")) {
                    try (PreparedStatement delete = c.prepareStatement(b.database().sql("DELETE FROM " + table + " = ?"))) {
                        for (long id : ids) {
                            delete.setLong(1, id);
                            delete.addBatch();
                        }
                        delete.executeBatch();
                    }
                }
            }
            return ids.size();
        };
        return dryRun ? read(work) : write(work);
    }

    // ---- Reads ----

    /** One punishment with its removal, or null. */
    public CompletableFuture<Entry> find(long id) {
        return read((c, b) -> findNow(c, b, id));
    }

    /** One punishment with its removal, on the caller's connection, or null. */
    public static Entry findNow(Connection c, Backend b, long id) throws SQLException {
        try (PreparedStatement select = c.prepareStatement(b.database().sql(Rows.SELECT + " WHERE p.id = ?"))) {
            select.setLong(1, id);
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Rows.read(row) : null;
            }
        }
    }

    /** A player's warnings still in force, newest first. */
    public CompletableFuture<List<Entry>> activeWarnings(UUID target) {
        return read((c, b) -> activeWarnings(c, b, target));
    }

    static List<Entry> activeWarnings(Connection c, Backend b, UUID target) throws SQLException {
        List<Entry> found = new ArrayList<>();
        long now = System.currentTimeMillis();
        try (PreparedStatement select = c.prepareStatement(b.database().sql(Rows.SELECT
            + " WHERE p.uuid = ? AND p.type = 3 AND r.punishment IS NULL AND (p.expires = 0 OR p.expires > ?) ORDER BY p.id DESC"))) {
            select.setString(1, target.toString());
            select.setLong(2, now);
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    found.add(Rows.read(rows));
                }
            }
        }
        return found;
    }

    /**
     * A player's or an IP's bans or mutes in force, read from the database rather than
     * memory, so a server that missed a change can still revoke what's there: by account,
     * and IP punishments on the IP key. Newest first.
     */
    public CompletableFuture<List<Entry>> inForce(PunishmentType type, UUID target, String ip) {
        return read((c, b) -> {
            java.util.Map<Long, Entry> found = new java.util.TreeMap<>(java.util.Comparator.reverseOrder());
            String where = " WHERE p.type = ? AND r.punishment IS NULL AND (p.expires = 0 OR p.expires > ?) AND ";
            if (target != null) {
                inForce(c, b, Rows.SELECT + where + "p.uuid = ?", type, target.toString(), found);
            }
            if (ip != null) {
                inForce(c, b, Rows.SELECT + where + "p.ip = ? AND p.ip_based = 1", type, ip, found);
            }
            return List.copyOf(found.values());
        });
    }

    private static void inForce(Connection c, Backend b, String sql, PunishmentType type, String value,
                                java.util.Map<Long, Entry> into) throws SQLException {
        try (PreparedStatement select = c.prepareStatement(b.database().sql(sql))) {
            select.setInt(1, Rows.code(type));
            select.setLong(2, System.currentTimeMillis());
            select.setString(3, value);
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    Entry entry = Rows.read(rows);
                    into.put(entry.id(), entry);
                }
            }
        }
    }

    /** An account that has joined from an IP, with its weight and exemption when last seen. */
    public record Account(UUID id, String name, int weight, boolean exempt) {}

    /** Every account seen on an IP key, for holding an IP punishment to their weights and exemptions. */
    public CompletableFuture<List<Account>> accountsOn(String ip) {
        return read((c, b) -> {
            List<Account> found = new ArrayList<>();
            try (PreparedStatement select = c.prepareStatement(b.database().sql("SELECT p.uuid, p.name, p.weight, p.exempt"
                + " FROM {p}ips i JOIN {p}players p ON p.uuid = i.uuid WHERE i.ip = ?"))) {
                select.setString(1, ip);
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) {
                        UUID id = Rows.uuid(rows.getString(1));
                        if (id != null) {
                            found.add(new Account(id, rows.getString(2), rows.getInt(3), rows.getInt(4) != 0));
                        }
                    }
                }
            }
            return found;
        });
    }

    /**
     * How many of a player's punishments under a template count toward its ladder: those made
     * since then that weren't revoked (one replaced by a newer one still counts).
     */
    public CompletableFuture<Integer> ladderStep(UUID target, String template, long since) {
        return read((c, b) -> {
            try (PreparedStatement select = c.prepareStatement(b.database().sql("SELECT COUNT(*) FROM {p}punishments p"
                + " LEFT JOIN {p}removals r ON r.punishment = p.id"
                + " WHERE p.uuid = ? AND p.template = ? AND p.created >= ? AND (r.punishment IS NULL OR r.reason = ?)"))) {
                select.setString(1, target.toString());
                select.setString(2, template);
                select.setLong(3, since);
                select.setString(4, REPLACED_REASON);
                try (ResultSet row = select.executeQuery()) {
                    return row.next() ? row.getInt(1) : 0;
                }
            }
        });
    }

    /** What's known of a player who has joined: their weight and exemption when last seen, and their newest IP key. */
    public record Known(String name, int weight, boolean exempt, String lastIp) {}

    public CompletableFuture<Known> known(UUID id) {
        return read((c, b) -> {
            String name = null;
            int weight = 0;
            boolean exempt = false;
            try (PreparedStatement select = c.prepareStatement(b.database().sql(
                "SELECT name, weight, exempt FROM {p}players WHERE uuid = ?"))) {
                select.setString(1, id.toString());
                try (ResultSet row = select.executeQuery()) {
                    if (row.next()) {
                        name = row.getString(1);
                        weight = row.getInt(2);
                        exempt = row.getInt(3) != 0;
                    }
                }
            }
            String ip = null;
            try (PreparedStatement select = c.prepareStatement(b.database().sql(
                "SELECT ip FROM {p}ips WHERE uuid = ? ORDER BY last_seen DESC LIMIT 1"))) {
                select.setString(1, id.toString());
                try (ResultSet row = select.executeQuery()) {
                    if (row.next()) {
                        ip = row.getString(1);
                    }
                }
            }
            return new Known(name, weight, exempt, ip);
        });
    }

    /** A player who joined, by name, newest first: for names Paper doesn't know any more. */
    public CompletableFuture<UUID> idByName(String name) {
        return read((c, b) -> {
            try (PreparedStatement select = c.prepareStatement(b.database().sql(
                "SELECT uuid FROM {p}players WHERE name = ? ORDER BY last_seen DESC LIMIT 1"))) {
                select.setString(1, name);
                try (ResultSet row = select.executeQuery()) {
                    return row.next() ? Rows.uuid(row.getString(1)) : null;
                }
            }
        });
    }

    /** One IP an account used: its key and when it was first and last seen. */
    public record IpUse(UUID id, String name, String ip, long first, long last) {}

    /** The IPs an account has used, newest first. */
    public CompletableFuture<List<IpUse>> ipHistory(UUID id) {
        return read((c, b) -> uses(c, b, "SELECT i.uuid, p.name, i.ip, i.first_seen, i.last_seen FROM {p}ips i"
            + " LEFT JOIN {p}players p ON p.uuid = i.uuid WHERE i.uuid = ? ORDER BY i.last_seen DESC", id.toString()));
    }

    /** Other accounts that used any of the account's IPs (or this IP key), the most recent use of each first. */
    public CompletableFuture<List<IpUse>> alts(UUID id, String ip) {
        return read((c, b) -> {
            List<IpUse> rows = id != null
                ? uses(c, b, "SELECT o.uuid, p.name, o.ip, o.first_seen, o.last_seen FROM {p}ips i"
                    + " JOIN {p}ips o ON o.ip = i.ip AND o.uuid <> i.uuid LEFT JOIN {p}players p ON p.uuid = o.uuid"
                    + " WHERE i.uuid = ? ORDER BY o.last_seen DESC", id.toString())
                : uses(c, b, "SELECT o.uuid, p.name, o.ip, o.first_seen, o.last_seen FROM {p}ips o"
                    + " LEFT JOIN {p}players p ON p.uuid = o.uuid WHERE o.ip = ? ORDER BY o.last_seen DESC", ip);
            List<IpUse> distinct = new ArrayList<>();
            java.util.Set<UUID> seen = new java.util.HashSet<>();
            for (IpUse use : rows) {
                if (use.id() != null && seen.add(use.id())) {
                    distinct.add(use);
                }
            }
            return distinct;
        });
    }

    private static List<IpUse> uses(Connection c, Backend b, String sql, String value) throws SQLException {
        List<IpUse> found = new ArrayList<>();
        try (PreparedStatement select = c.prepareStatement(b.database().sql(sql))) {
            select.setString(1, value);
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    UUID id = Rows.uuid(rows.getString(1));
                    String name = rows.getString(2);
                    found.add(new IpUse(id, name == null ? String.valueOf(id) : name, rows.getString(3), rows.getLong(4),
                        rows.getLong(5)));
                }
            }
        }
        return found;
    }

    /**
     * Which punishments a page lists: a player's ({@code uuid}), a staff member's
     * ({@code staff_uuid}, null for the console), or an IP's; optionally one type.
     */
    public record Filter(String column, String value, PunishmentType type) {

        public static Filter target(UUID id, PunishmentType type) {
            return new Filter("uuid", id.toString(), type);
        }

        public static Filter staff(UUID id, PunishmentType type) {
            return new Filter("staff_uuid", id == null ? null : id.toString(), type);
        }

        String where() {
            return " WHERE p." + column + (value == null ? " IS NULL" : " = ?") + (type == null ? "" : " AND p.type = ?");
        }

        int bind(PreparedStatement statement) throws SQLException {
            int index = 1;
            if (value != null) {
                statement.setString(index++, value);
            }
            if (type != null) {
                statement.setInt(index++, Rows.code(type));
            }
            return index;
        }
    }

    /**
     * Up to {@code limit} punishments newer-first with ids below {@code before}: one page by
     * key range, as fast deep in a long history as at its start.
     */
    public static List<Entry> page(Connection c, Backend b, Filter filter, long before, int limit) throws SQLException {
        List<Entry> found = new ArrayList<>();
        try (PreparedStatement select = c.prepareStatement(b.database().sql(Rows.SELECT + filter.where()
            + " AND p.id < ? ORDER BY p.id DESC LIMIT " + limit))) {
            int index = filter.bind(select);
            select.setLong(index, before);
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    found.add(Rows.read(rows));
                }
            }
        }
        return found;
    }

    public static long count(Connection c, Backend b, Filter filter) throws SQLException {
        try (PreparedStatement select = c.prepareStatement(b.database().sql("SELECT COUNT(*) FROM {p}punishments p" + filter.where()))) {
            filter.bind(select);
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? row.getLong(1) : 0;
            }
        }
    }

    /**
     * The id just above the first one of the {@code skip + 1}th newest, to start a page jumped
     * to without its previous page's last id. Reads only the index.
     */
    public static long boundary(Connection c, Backend b, Filter filter, long skip) throws SQLException {
        if (skip <= 0) {
            return Long.MAX_VALUE;
        }
        try (PreparedStatement select = c.prepareStatement(b.database().sql("SELECT p.id FROM {p}punishments p" + filter.where()
            + " ORDER BY p.id DESC LIMIT 1 OFFSET " + (skip - 1)))) {
            filter.bind(select);
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? row.getLong(1) : Long.MIN_VALUE;
            }
        }
    }
}
