package co.xenastudios.xrewind.storage;

import co.xenastudios.core.db.Database;
import co.xenastudios.core.db.DatabaseException;
import co.xenastudios.core.db.StorageSettings;
import co.xenastudios.core.task.Tasks;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Keeps xRewind's database matching the config. Opening one (connecting, updating its
 * tables) happens off the server's threads, so a slow or unreachable MySQL never holds
 * up startup or a reload. Until it's ready, {@link #database()} is null.
 *
 * <p>If a new database can't be opened, the one already in use stays in use.
 */
public final class Storage {

    /** The SQLite file, in the plugin's folder. */
    static final String FILE = "history.db";

    private final Tasks tasks;
    private final Logger logger;
    private final Path folder;
    private final AtomicInteger generation = new AtomicInteger();
    // Global thread only: what the config last asked for.
    private StorageSettings wanted;
    private volatile Database database;

    public Storage(Tasks tasks, Logger logger, Path folder) {
        this.tasks = tasks;
        this.logger = logger;
        this.folder = folder;
    }

    /** The database in use, or null before the first one is ready. Safe from any thread. */
    public Database database() {
        return database;
    }

    /** Opens the database the settings describe, unless it's the one already asked for. Call on the global thread. */
    public void sync(StorageSettings settings) {
        if (settings.equals(wanted)) {
            return;
        }
        wanted = settings;
        int ticket = generation.incrementAndGet();
        tasks.async(() -> open(settings, ticket));
    }

    private void open(StorageSettings settings, int ticket) {
        try {
            Database opened = Database.open(settings, folder.resolve(FILE));
            int schema = opened.migrate(Schema.STEPS);
            if (ticket != generation.get()) {
                return; // A newer reload asked for something else while this one connected.
            }
            database = opened;
            logger.info("Storage ready: " + opened.description() + ", schema " + schema + ".");
        } catch (DatabaseException e) {
            if (ticket != generation.get()) {
                return;
            }
            Database current = database;
            logger.severe(e.getMessage());
            logger.severe(current != null
                ? "Still using " + current.description() + "."
                : "Nothing can be saved until the storage settings are fixed and xRewind is reloaded.");
            // So reloading with the same settings tries again, as when a server was just down.
            tasks.global(() -> {
                if (ticket == generation.get()) {
                    wanted = null;
                }
            });
        }
    }
}
