package co.xenastudios.xbans.network;

import co.xenastudios.core.db.ChangeFeed;
import co.xenastudios.xbans.XBans;
import co.xenastudios.xbans.model.Entry;
import co.xenastudios.xbans.storage.Store;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import org.bukkit.Bukkit;

/**
 * Network mode: hears the punishments and revocations other servers make, through the
 * core's change feed, and makes them felt here as if made here: the in-memory lists, kicks,
 * the broadcast. Each change carries only an id; the punishment is read from the shared
 * database. Off (and free) on a single server.
 *
 * <p>A change whose punishment can't be read is tried again; if it still can't be, every
 * ban and mute in force is read again ({@link #resync}), so the change isn't lost for good.
 * The same happens whenever the feed says it may have missed changes. One reload runs at a
 * time: one asked for while another runs runs once after it, and one that fails is tried
 * again every {@link #RELOAD_RETRY} until one works. While a reload is due, changes aren't
 * tried again one by one, so a burst of them while the database is down can't hold up the
 * feed or start a reload each.
 */
public final class Network {

    /** Waits between tries at reading a punishment another server made. */
    static final long[] RETRY_MS = {250, 1_000};
    /** How long after a reload fails it's tried again. */
    static final Duration RELOAD_RETRY = Duration.ofSeconds(15);

    /** Reads one punishment; the database's in use, tests' can fail. */
    @FunctionalInterface
    interface Reader {
        Entry read(Store.Backend backend, long id) throws SQLException;
    }

    /** Reads every ban and mute in force again; the store's in use, tests' can fail. */
    @FunctionalInterface
    interface Reloader {
        CompletableFuture<List<Entry>> reload();
    }

    private final XBans xbans;
    private final Reader reader;
    private final Reloader reloader;
    private final Duration retry;

    // Guarded by this.
    private boolean reloading;
    private boolean again;
    private Store.Backend failedFor;
    private int tries;

    public Network(XBans xbans) {
        this(xbans, (backend, id) -> {
            try (Connection connection = backend.database().connect()) {
                return Store.findNow(connection, backend, id);
            }
        }, () -> xbans.store().reloadInForce(), RELOAD_RETRY);
    }

    Network(XBans xbans, Reader reader, Reloader reloader, Duration retry) {
        this.xbans = xbans;
        this.reader = reader;
        this.reloader = reloader;
        this.retry = retry;
    }

    /** Subscribes to a freshly opened database's feed, before it starts. */
    public void subscribe(Store.Backend backend) {
        ChangeFeed feed = backend.feed();
        if (!feed.on()) {
            return;
        }
        feed.subscribe(Store.TOPIC_PUNISH, change -> handle(backend, change, false));
        feed.subscribe(Store.TOPIC_REVOKE, change -> handle(backend, change, true));
        // The feed may have missed changes (it couldn't check for a while, or rows went unseen):
        // read again what's in force.
        feed.onResync(() -> resync(backend));
    }

    /**
     * Reads every ban and mute in force again and brings memory in line, enforcing any ban
     * this server missed (its players are kicked; nothing is broadcast). For when this server
     * may have missed changes: one it couldn't read, or a feed that fell behind. If one is
     * running, another runs once it's done, however many are asked for meanwhile.
     */
    public void resync(Store.Backend backend) {
        synchronized (this) {
            if (reloading) {
                again = true;
                return;
            }
            reloading = true;
        }
        reload(backend);
    }

    private void reload(Store.Backend backend) {
        if (backend != xbans.store().backend()) {
            done(backend, null); // The database changed: the new one was read whole when it opened.
            return;
        }
        CompletableFuture<List<Entry>> reading;
        try {
            reading = reloader.reload();
        } catch (RuntimeException e) {
            reading = CompletableFuture.failedFuture(e);
        }
        reading.whenComplete((added, failure) -> {
            if (failure == null && !added.isEmpty()) {
                xbans.getLogger().info("Caught up with the network: " + added.size() + " bans and mutes this server had missed.");
                added.forEach(xbans.punisher()::enforce);
            }
            done(backend, failure);
        });
    }

    // After a reload: the next one asked for meanwhile, or the next try at a failed one.
    private void done(Store.Backend backend, Throwable failure) {
        boolean next;
        boolean recovered;
        int failures;
        synchronized (this) {
            reloading = false;
            recovered = failure == null && failedFor != null;
            if (failure == null || backend != xbans.store().backend()) {
                failedFor = null;
                tries = 0;
            } else {
                failedFor = backend;
                tries++;
            }
            failures = tries;
            // A failed one is tried again later anyway, and that covers whatever was asked for meanwhile.
            next = again && failure == null;
            again = false;
        }
        if (failure != null && backend == xbans.store().backend()) {
            if (failures == 1) {
                xbans.getLogger().log(Level.WARNING, "Couldn't read the bans and mutes in force to catch up with the network;"
                    + " trying again every " + retry.toSeconds() + "s until it works", failure);
            }
            try {
                Bukkit.getAsyncScheduler().runDelayed(xbans, task -> tryAgain(backend, failures),
                    retry.toMillis(), TimeUnit.MILLISECONDS);
            } catch (RuntimeException e) {
                // The plugin is stopping: nothing to catch up for.
            }
        } else if (recovered) {
            xbans.getLogger().info("Caught up with the network after all.");
        }
        if (next) {
            resync(backend);
        }
    }

    // A failed reload's next try, unless one has worked since (or failed since, which set its own).
    private void tryAgain(Store.Backend backend, int failures) {
        synchronized (this) {
            if (failedFor != backend || tries != failures) {
                return;
            }
        }
        resync(backend);
    }

    // On the feed's own thread, in order, never a game thread.
    void handle(Store.Backend backend, ChangeFeed.Change change, boolean revoke) {
        String payload = change.payload();
        boolean replaced = payload.endsWith(Store.REPLACED);
        boolean silent = payload.endsWith(Store.SILENT);
        boolean quiet = payload.endsWith(Store.QUIET);
        long id;
        try {
            id = Long.parseLong(replaced || silent || quiet ? payload.substring(0, payload.length() - 2) : payload);
        } catch (NumberFormatException e) {
            xbans.getLogger().warning("Another server sent a change xBans can't read: " + payload);
            return;
        }
        boolean waiting;
        boolean busy;
        synchronized (this) {
            waiting = failedFor == backend;
            busy = reloading || waiting;
        }
        if (waiting) {
            // The database couldn't be read moments ago, and the reload that's due reads this too.
            if (revoke) {
                backend.active().remove(id);
            }
            return;
        }
        Entry entry;
        try {
            // While a reload is due, one try: if it fails, the reload after this one covers it.
            entry = read(backend, id, busy ? 0 : RETRY_MS.length);
        } catch (SQLException e) {
            if (!busy) {
                xbans.getLogger().warning("Couldn't read punishment #" + id + " from another server (" + e.getMessage()
                    + "); reading every ban and mute in force again instead.");
            }
            if (revoke) {
                // Lifted all the same: a ban stays lifted even if its details can't be read.
                backend.active().remove(id);
            }
            resync(backend);
            return;
        }
        if (backend != xbans.store().backend()) {
            return; // The database changed while this was read.
        }
        if (revoke) {
            if (entry == null) {
                backend.active().remove(id);
            } else {
                xbans.punisher().applyRevoked(entry, false, silent || entry.punishment().silent(), false, !replaced, null);
            }
        } else if (entry != null && quiet) {
            backend.active().add(entry);
            xbans.punisher().enforce(entry);
        } else if (entry != null && entry.punishment().removal() == null) {
            xbans.punisher().applyMade(entry, List.of(), false, false, null);
        }
    }

    private Entry read(Store.Backend backend, long id, int retries) throws SQLException {
        for (int attempt = 0; ; attempt++) {
            try {
                return reader.read(backend, id);
            } catch (SQLException e) {
                if (attempt >= retries) {
                    throw e;
                }
                try {
                    Thread.sleep(RETRY_MS[attempt]);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }
}
