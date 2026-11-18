package co.xenastudios.xbans.network;

import co.xenastudios.core.db.ChangeFeed;
import co.xenastudios.xbans.XBans;
import co.xenastudios.xbans.model.Entry;
import co.xenastudios.xbans.storage.Store;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.logging.Level;

/**
 * Network mode: hears the punishments and revocations other servers make, through the
 * core's change feed, and makes them felt here as if made here: the in-memory lists, kicks,
 * the broadcast. Each change carries only an id; the punishment is read from the shared
 * database. Off (and free) on a single server.
 *
 * <p>A change whose punishment can't be read is tried again; if it still can't be, every
 * ban and mute in force is read again ({@link #resync}), so the change isn't lost for good.
 */
public final class Network {

    /** Waits between tries at reading a punishment another server made. */
    static final long[] RETRY_MS = {250, 1_000};

    /** Reads one punishment; the database's in use, tests' can fail. */
    @FunctionalInterface
    interface Reader {
        Entry read(Store.Backend backend, long id) throws SQLException;
    }

    private final XBans xbans;
    private final Reader reader;

    public Network(XBans xbans) {
        this(xbans, (backend, id) -> {
            try (Connection connection = backend.database().connect()) {
                return Store.findNow(connection, backend, id);
            }
        });
    }

    Network(XBans xbans, Reader reader) {
        this.xbans = xbans;
        this.reader = reader;
    }

    /** Subscribes to a freshly opened database's feed, before it starts. */
    public void subscribe(Store.Backend backend) {
        ChangeFeed feed = backend.feed();
        if (!feed.on()) {
            return;
        }
        feed.subscribe(Store.TOPIC_PUNISH, change -> handle(backend, change, false));
        feed.subscribe(Store.TOPIC_REVOKE, change -> handle(backend, change, true));
        // TODO(core #…): feed.onResync(() -> resync(backend)); once ChangeFeed.onResync is on main.
    }

    /**
     * Reads every ban and mute in force again and brings memory in line, enforcing any ban
     * this server missed (its players are kicked; nothing is broadcast). For when this server
     * may have missed changes: one it couldn't read, or a feed that fell behind.
     */
    public void resync(Store.Backend backend) {
        if (backend != xbans.store().backend()) {
            return;
        }
        xbans.store().reloadInForce().thenAccept(added -> {
            if (!added.isEmpty()) {
                xbans.getLogger().info("Caught up with the network: " + added.size() + " bans and mutes this server had missed.");
            }
            added.forEach(xbans.punisher()::enforce);
        }).exceptionally(e -> {
            xbans.getLogger().log(Level.WARNING, "Couldn't read the bans and mutes in force to catch up with the network", e);
            return null;
        });
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
        Entry entry;
        try {
            entry = read(backend, id);
        } catch (SQLException e) {
            xbans.getLogger().warning("Couldn't read punishment #" + id + " from another server (" + e.getMessage()
                + "); reading every ban and mute in force again instead.");
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

    private Entry read(Store.Backend backend, long id) throws SQLException {
        for (int attempt = 0; ; attempt++) {
            try {
                return reader.read(backend, id);
            } catch (SQLException e) {
                if (attempt >= RETRY_MS.length) {
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
