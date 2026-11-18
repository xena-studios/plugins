package co.xenastudios.xvote.storage;

import co.xenastudios.core.db.Database;
import co.xenastudios.core.db.DatabaseException;
import co.xenastudios.core.db.StorageSettings;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The database, and the one thread that talks to it. Every read and write is a job run
 * there, in order, each in its own transaction, so two votes for one player are always
 * counted one after the other. Nothing waits on it from a game thread: a job's future
 * completes on the database thread, and whoever needs a game thread hops there.
 *
 * <p>A job that fails because the database went away is run again on a fresh
 * connection, after a pause that grows to 30 seconds, and the jobs behind it wait, so a
 * vote is never lost to a short outage. One that fails three times on a connection that
 * works is a job the database won't take, not an outage: it's dropped with a warning.
 */
public final class Store {

    /** The SQLite file, in the plugin's folder. */
    static final String FILE = "votes.db";
    static final long MAX_PAUSE_MS = 30_000;

    /** A job: runs in a transaction, committed if it returns. */
    @FunctionalInterface
    public interface Job<T> {
        T run(Connection connection) throws SQLException;
    }

    private final Database database;
    private final Logger logger;
    private final ExecutorService thread;
    // Database thread only.
    private Connection connection;
    private boolean outage;
    private volatile boolean stopping;

    Store(Database database, Logger logger) {
        this.database = database;
        this.logger = logger;
        this.thread = Executors.newSingleThreadExecutor(runnable -> {
            Thread worker = new Thread(runnable, "xVote database");
            worker.setDaemon(true);
            return worker;
        });
    }

    /** Opens the database and brings its tables up to date. Blocks: call once, at startup. */
    public static Store open(StorageSettings settings, Path folder, Logger logger) throws DatabaseException {
        Database database = Database.open(settings, folder.resolve(FILE));
        database.migrate(Schema.STEPS);
        return new Store(database, logger);
    }

    public Database database() {
        return database;
    }

    public String description() {
        return database.description();
    }

    /**
     * Runs a job on the database thread.
     *
     * @return its result; failed if it was dropped, or the store is stopping
     */
    public <T> CompletableFuture<T> submit(Job<T> job) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            thread.execute(() -> {
                try {
                    future.complete(run(job));
                } catch (SQLException | RuntimeException e) {
                    future.completeExceptionally(e);
                }
            });
        } catch (RejectedExecutionException e) {
            future.completeExceptionally(new SQLException("xVote is stopping"));
        }
        return future;
    }

    /** Runs a job right here, blocking. For startup, before the database thread has work. */
    public <T> T now(Job<T> job) throws SQLException {
        try (Connection own = database.connect()) {
            return transaction(own, job);
        }
    }

    private <T> T run(Job<T> job) throws SQLException {
        int refused = 0;
        long pause = 1000;
        while (true) {
            boolean worked = false;
            try {
                if (connection == null) {
                    connection = database.connect();
                }
                worked = connection.isValid(5);
                if (!worked) {
                    throw new SQLException("the connection was lost");
                }
                T result = transaction(connection, job);
                if (outage) {
                    outage = false;
                    logger.info("Reached " + database.description() + " again; votes are being stored.");
                }
                return result;
            } catch (SQLException e) {
                close();
                if (worked && ++refused >= 3) {
                    logger.log(Level.WARNING, database.description() + " refused a change three times, so it was dropped", e);
                    throw e;
                }
                if (stopping) {
                    throw e;
                }
                if (!outage && !worked) {
                    outage = true;
                    logger.log(Level.WARNING, "Can't reach " + database.description() + "; votes wait and are stored once it's back.", e);
                }
                try {
                    Thread.sleep(pause);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
                pause = Math.min(MAX_PAUSE_MS, pause * 2);
            }
        }
    }

    private static <T> T transaction(Connection connection, Job<T> job) throws SQLException {
        connection.setAutoCommit(false);
        try {
            T result = job.run(connection);
            connection.commit();
            return result;
        } catch (SQLException | RuntimeException e) {
            try {
                connection.rollback();
            } catch (SQLException ignored) {
                // The connection is gone; the transaction went with it.
            }
            throw e;
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException ignored) {
                // Closed after a failure.
            }
        }
    }

    private void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // Already gone.
            }
            connection = null;
        }
    }

    /** Finishes the jobs already queued, for up to {@code timeout}, then closes. */
    public void stop(long timeout, TimeUnit unit) {
        stopping = true;
        thread.shutdown();
        try {
            if (thread.awaitTermination(timeout, unit)) {
                close();
            } else {
                logger.warning("The database was still busy after " + unit.toSeconds(timeout) + "s; some changes weren't written.");
                thread.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            thread.shutdownNow();
        }
    }
}
