package co.xenastudios.xrewind.maintain;

import co.xenastudios.core.db.Database;
import co.xenastudios.core.text.Durations;
import co.xenastudios.core.text.Placeholders;
import co.xenastudios.xrewind.XRewind;
import co.xenastudios.xrewind.config.XRConfig;
import co.xenastudios.xrewind.log.WriteGate;
import co.xenastudios.xrewind.storage.Clock;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import org.bukkit.command.CommandSender;

/** {@code /xrewind purge} and {@code /xrewind import}, run off the server's threads. */
public final class Upkeep {

    private static final String IMPORTED = "coreprotect-import";

    private final XRewind plugin;
    private final AtomicBoolean importing = new AtomicBoolean();

    public Upkeep(XRewind plugin) {
        this.plugin = plugin;
    }

    private XRConfig.UpkeepMessages messages() {
        return plugin.config().messages().upkeep();
    }

    /** Deletes history older than {@code seconds}, at least a day. */
    public void purge(CommandSender sender, long seconds) {
        if (seconds < 86_400) {
            messages().purgeTooRecent().send(sender);
            return;
        }
        Database database = ready(sender);
        if (database == null) {
            return;
        }
        messages().purging().send(sender);
        plugin.tasks().async(() -> {
            try {
                plugin.writer().flush(2_000);
                long deleted = Purge.run(database, plugin.writer().gate(), Purge.secondsAgo(seconds));
                String count = String.format("%,d", deleted);
                if (Thread.currentThread().isInterrupted()) {
                    // Purge.run stops between batches when interrupted, as the server stops.
                    plugin.getLogger().warning("A purge was stopped partway, after deleting " + count + " changes.");
                    reply(sender, () -> messages().purgeInterrupted().send(sender, Placeholders.text("count", count)));
                    return;
                }
                reply(sender, () -> messages().purged().send(sender, Placeholders.text("count", count),
                    Placeholders.text("time", Durations.format(Duration.ofSeconds(seconds)))));
            } catch (SQLException | RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "A purge failed", e);
                reply(sender, () -> plugin.config().errorMessages().failed().send(sender));
            }
        });
    }

    /** Imports CoreProtect's history, once unless {@code again}. */
    public void importCoreProtect(CommandSender sender, boolean again) {
        Database database = ready(sender);
        if (database == null) {
            return;
        }
        if (!importing.compareAndSet(false, true)) {
            messages().importRunning().send(sender);
            return;
        }
        plugin.tasks().async(() -> {
            try {
                String before = meta(database, IMPORTED);
                if (before != null && !again) {
                    reply(sender, () -> messages().importedBefore().send(sender, Placeholders.text("date", before)));
                    return;
                }
                CoreProtectImport.Source source;
                try {
                    source = CoreProtectImport.find(plugin.getDataFolder().toPath().getParent());
                } catch (IllegalStateException e) {
                    reply(sender, () -> messages().importFailed().send(sender, Placeholders.text("reason", e.getMessage())));
                    return;
                }
                reply(sender, () -> messages().importing().send(sender, Placeholders.text("source", source.description())));
                // Recorded before anything's read: a second import, even after this one stops
                // partway, would duplicate what came across, so it needs #again.
                setMeta(database, plugin.writer().gate(), IMPORTED, "partway, " + LocalDate.now());
                long start = System.nanoTime();
                long count = new CoreProtectImport(source, plugin.queue(), plugin.getLogger()).run();
                // Done once what it queued is written. Live logging keeps the queue busy, so
                // wait for this import's rows, not for the queue to be empty. A row the
                // database wouldn't take counts as dealt with, so this can't wait forever.
                long target = plugin.writer().target();
                while (!plugin.writer().flushed(target, 5_000) && plugin.writer().problem() == null) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException();
                    }
                    if (!plugin.writer().started()) {
                        throw new IllegalStateException("xRewind stopped before what was imported was saved");
                    }
                }
                setMeta(database, plugin.writer().gate(), IMPORTED, LocalDate.now().toString());
                double seconds = (System.nanoTime() - start) / 1e9;
                plugin.getLogger().info("Imported " + String.format("%,d", count) + " changes from CoreProtect in "
                    + co.xenastudios.xrewind.rollback.Rollbacks.seconds(seconds) + "s.");
                reply(sender, () -> messages().imported().send(sender, Placeholders.text("count", String.format("%,d", count)),
                    Placeholders.text("seconds", co.xenastudios.xrewind.rollback.Rollbacks.seconds(seconds))));
            } catch (SQLException | RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "The CoreProtect import failed", e);
                reply(sender, () -> messages().importFailed().send(sender, Placeholders.text("reason", String.valueOf(e.getMessage()))));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                plugin.getLogger().warning("The CoreProtect import was stopped partway.");
                reply(sender, () -> messages().importInterrupted().send(sender));
            } finally {
                importing.set(false);
            }
        });
    }

    private Database ready(CommandSender sender) {
        Database database = plugin.storage().database();
        if (database == null) {
            plugin.config().messages().lookup().notReady().send(sender);
        }
        return database;
    }

    private static String meta(Database database, String name) throws SQLException {
        try (Connection connection = database.connect();
             PreparedStatement query = connection.prepareStatement(database.sql("SELECT value FROM {p}meta WHERE name = ?"))) {
            query.setString(1, name);
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        }
    }

    // A write, so a turn at the gate: a backup's hold waits for it, and it for the hold.
    private static void setMeta(Database database, WriteGate gate, String name, String value) throws SQLException, InterruptedException {
        gate.enter();
        try (Connection connection = database.connect()) {
            try (PreparedStatement update = connection.prepareStatement(database.sql("UPDATE {p}meta SET value = ? WHERE name = ?"))) {
                update.setString(1, value);
                update.setString(2, name);
                if (update.executeUpdate() > 0) {
                    return;
                }
            }
            try (PreparedStatement insert = connection.prepareStatement(database.sql("INSERT INTO {p}meta (name, value) VALUES (?, ?)"))) {
                insert.setString(1, name);
                insert.setString(2, value);
                insert.executeUpdate();
            }
        } finally {
            gate.exit();
        }
    }

    // The scheduler refuses once xRewind is disabled, as it is when the server stopped the
    // work; the console was told already.
    private void reply(CommandSender sender, Runnable send) {
        try {
            plugin.tasks().sender(sender, send);
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.FINE, "Couldn't reply to " + sender.getName(), e);
        }
    }
}
