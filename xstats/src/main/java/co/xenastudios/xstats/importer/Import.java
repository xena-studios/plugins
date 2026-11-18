package co.xenastudios.xstats.importer;

import co.xenastudios.xstats.XStats;
import co.xenastudios.xstats.data.Engine;
import co.xenastudios.xstats.storage.Store;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.function.Consumer;
import org.bukkit.Bukkit;

/** The history import: files read off the server's threads, rows added on xStats' thread. */
public final class Import {

    private static final int CHUNK = 500;

    private Import() {}

    public static void run(XStats plugin, Path world, Consumer<String> done) {
        plugin.tasks().async(() -> {
            List<Path> folders = StatsFiles.folders(world);
            if (folders.isEmpty()) {
                plugin.worker().execute(() -> {
                    mark(plugin);
                    done.accept("There's no stats folder in " + world.getFileName() + " yet, so there's no history to import.");
                });
                return;
            }
            long started = System.currentTimeMillis();
            StatsFiles.Result result = StatsFiles.read(folders, plugin.catalog().snapshot().stats(),
                id -> Bukkit.getOfflinePlayer(id).getName());
            plugin.worker().execute(() -> {
                Engine engine = plugin.engine();
                Store store = engine.store();
                // What's in memory goes in first, so the import never adds a row a save is about to write.
                engine.save(System.currentTimeMillis());
                int added = 0;
                try {
                    for (int from = 0; from < result.players().size(); from += CHUNK) {
                        added += store.importRows(result.players().subList(from, Math.min(result.players().size(), from + CHUNK)));
                    }
                    mark(plugin);
                    engine.afterImport();
                } catch (SQLException e) {
                    done.accept("The import stopped: " + e.getMessage() + ". Run /xstats import to try again; nothing is added twice.");
                    return;
                }
                plugin.afterSave();
                long took = System.currentTimeMillis() - started;
                done.accept("Imported " + added + " stats for " + result.players().size() + " players from " + result.files()
                    + " files in " + took + " ms" + (result.skipped() > 0 ? " (" + result.skipped() + " files couldn't be read)" : "")
                    + ". Players xStats already counted kept their numbers.");
            });
        });
    }

    private static void mark(XStats plugin) {
        try {
            plugin.engine().store().setFlag("imported");
        } catch (SQLException e) {
            plugin.getLogger().warning("Couldn't note that the import ran: " + e.getMessage());
        }
    }
}
