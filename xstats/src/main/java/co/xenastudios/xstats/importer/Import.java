package co.xenastudios.xstats.importer;

import co.xenastudios.xstats.XStats;
import co.xenastudios.xstats.data.Engine;
import co.xenastudios.xstats.stat.StatDef;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import org.bukkit.Bukkit;

/**
 * The history import: files read off the server's threads, rows added on xStats' thread,
 * {@value #CHUNK} players at a time, so a world with a hundred thousand players never has
 * them all in memory at once.
 */
public final class Import {

    private static final int CHUNK = 500;

    private Import() {}

    public static void run(XStats plugin, Path world, Consumer<String> done) {
        plugin.tasks().async(() -> {
            List<Path> folders = StatsFiles.folders(world);
            if (folders.isEmpty()) {
                plugin.worker().execute(() -> {
                    mark(plugin);
                    done.accept("There's no stats folder in " + StatsFiles.levelRoot(world).getFileName() + " yet, so there's no history to import.");
                });
                return;
            }
            long started = System.currentTimeMillis();
            Engine engine = plugin.engine();
            // Names from the server's cache of recent players, else what the player's saved data remembers.
            Map<UUID, String> cached = StatsFiles.userCache(Bukkit.getWorldContainer().toPath().resolve("usercache.json"));
            List<StatDef> stats = plugin.catalog().snapshot().stats();
            StatsFiles.Index index = StatsFiles.index(folders);
            int added = 0;
            int players = 0;
            int skipped = index.skipped();
            try {
                // What's in memory goes in first, so the import never adds a row a save is about to write.
                plugin.worker().execute(() -> engine.save(System.currentTimeMillis())).get();
                for (int from = 0; from < index.files().size(); from += CHUNK) {
                    StatsFiles.Part part = StatsFiles.readPart(index.files().subList(from, Math.min(index.files().size(), from + CHUNK)),
                        stats, id -> cached.containsKey(id) ? cached.get(id) : Bukkit.getOfflinePlayer(id).getName());
                    skipped += part.skipped();
                    players += part.players().size();
                    int[] count = {0};
                    SQLException[] failed = {null};
                    plugin.worker().execute(() -> {
                        try {
                            count[0] = engine.store().importRows(part.players());
                            // In the same step, so no save between writes back the lower value it held.
                            engine.raiseImported(part.players());
                        } catch (SQLException e) {
                            failed[0] = e;
                        }
                    }).get();
                    if (failed[0] != null) {
                        throw failed[0];
                    }
                    added += count[0];
                }
            } catch (SQLException e) {
                done.accept("The import stopped: " + e.getMessage() + ". Run /xstats import to try again; nothing is added twice.");
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException | RuntimeException e) {
                done.accept("The import stopped: " + e + ". Run /xstats import to try again; nothing is added twice.");
                return;
            }
            int total = added;
            int read = players;
            int unread = skipped;
            plugin.worker().execute(() -> {
                try {
                    mark(plugin);
                    engine.afterImport();
                } catch (SQLException e) {
                    done.accept("The import stopped: " + e.getMessage() + ". Run /xstats import to try again; nothing is added twice.");
                    return;
                }
                plugin.afterSave();
                long took = System.currentTimeMillis() - started;
                done.accept("Imported " + total + " stats for " + read + " players from " + index.files().size()
                    + " files in " + took + " ms" + (unread > 0 ? " (" + unread + " files couldn't be read)" : "")
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
