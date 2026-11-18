package co.xenastudios.xbans.network;

import co.xenastudios.core.db.ChangeFeed;
import co.xenastudios.xbans.XBans;
import co.xenastudios.xbans.model.Entry;
import co.xenastudios.xbans.storage.Store;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Level;

/**
 * Network mode: hears the punishments and revocations other servers make, through the
 * core's change feed, and makes them felt here as if made here: the in-memory lists, kicks,
 * the broadcast. Each change carries only an id; the punishment is read from the shared
 * database. Off (and free) on a single server.
 */
public final class Network {

    private final XBans xbans;

    public Network(XBans xbans) {
        this.xbans = xbans;
    }

    /** Subscribes to a freshly opened database's feed, before it starts. */
    public void subscribe(Store.Backend backend) {
        ChangeFeed feed = backend.feed();
        if (!feed.on()) {
            return;
        }
        feed.subscribe(Store.TOPIC_PUNISH, change -> handle(backend, change, false));
        feed.subscribe(Store.TOPIC_REVOKE, change -> handle(backend, change, true));
    }

    // On the feed's own thread, in order, never a game thread.
    private void handle(Store.Backend backend, ChangeFeed.Change change, boolean revoke) {
        String payload = change.payload();
        boolean replaced = payload.endsWith(Store.REPLACED);
        boolean silent = payload.endsWith(Store.SILENT);
        long id;
        try {
            id = Long.parseLong(replaced || silent ? payload.substring(0, payload.length() - 2) : payload);
        } catch (NumberFormatException e) {
            xbans.getLogger().warning("Another server sent a change xBans can't read: " + payload);
            return;
        }
        Entry entry;
        try (Connection connection = backend.database().connect()) {
            entry = Store.findNow(connection, backend, id);
        } catch (SQLException e) {
            xbans.getLogger().log(Level.WARNING, "Couldn't read punishment #" + id + " from another server", e);
            if (revoke) {
                // Lifted all the same: a ban stays lifted even if its details can't be read.
                backend.active().remove(id);
            }
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
        } else if (entry != null && entry.punishment().removal() == null) {
            xbans.punisher().applyMade(entry, null, false, false, null);
        }
    }
}
