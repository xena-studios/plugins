package co.xenastudios.xrewind.maintain;

import co.xenastudios.core.db.Database;
import co.xenastudios.core.text.Durations;
import co.xenastudios.core.text.Placeholders;
import co.xenastudios.xrewind.XRewind;
import co.xenastudios.xrewind.config.XRConfig;
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
                long deleted = Purge.run(database, Purge.secondsAgo(seconds));
                reply(sender, () -> messages().purged().send(sender, Placeholders.text("count", String.format("%,d", deleted)),
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
                setMeta(database, IMPORTED, "partway, " + LocalDate.now());
                long start = System.nanoTime();
                long count = new CoreProtectImport(source, plugin.queue(), plugin.getLogger()).run();
                // Done once what it queued is written. Live logging keeps the queue busy, so
                // wait for this import's rows, not for the queue to be empty.
                long target = plugin.writer().written() + plugin.queue().size();
                while (plugin.writer().written() < target && plugin.writer().problem() == null) {
                    plugin.writer().flush(5_000);
                }
                setMeta(database, IMPORTED, LocalDate.now().toString());
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

    private static void setMeta(Database database, String name, String value) throws SQLException {
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
        }
    }

    private void reply(CommandSender sender, Runnable send) {
        plugin.tasks().sender(sender, send);
    }
}
