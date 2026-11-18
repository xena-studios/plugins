package co.xenastudios.xbackup.server;

import co.xenastudios.xbackup.api.BackupSnapshot;
import co.xenastudios.xbackup.api.BackupStatus;
import co.xenastudios.xbackup.api.QuietMoment;
import co.xenastudios.xbackup.api.StorageBackend;
import co.xenastudios.xbackup.api.XBackupApi;
import co.xenastudios.xbackup.remote.Remotes;
import co.xenastudios.xbackup.store.Snapshot;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import org.bukkit.plugin.Plugin;

/** The API, over the engine. Everything here answers from memory or queues a job. */
public final class Api implements XBackupApi {

    private final Engine engine;

    public Api(Engine engine) {
        this.engine = engine;
    }

    @Override
    public CompletableFuture<BackupSnapshot> backupNow(String reason) {
        String why = reason == null || reason.isBlank() ? "api" : reason.strip();
        return engine.backup(why, false).thenApply(engine::toApi);
    }

    @Override
    public List<BackupSnapshot> listSnapshots() {
        List<Snapshot> snapshots = engine.snapshots();
        List<BackupSnapshot> out = new ArrayList<>(snapshots.size());
        for (int i = snapshots.size() - 1; i >= 0; i--) {
            out.add(engine.toApi(snapshots.get(i)));
        }
        return List.copyOf(out);
    }

    @Override
    public BackupStatus getStatus() {
        return engine.status();
    }

    @Override
    public void registerSource(Plugin plugin, Path path, List<String> excludes) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(path, "path");
        engine.sources.computeIfAbsent(plugin, key -> new CopyOnWriteArrayList<>())
            .add(new Engine.ApiSource(path.toAbsolutePath().normalize(), excludes == null ? List.of() : List.copyOf(excludes)));
    }

    @Override
    public void unregisterSources(Plugin plugin) {
        engine.sources.remove(plugin);
    }

    @Override
    public void registerQuietMoment(Plugin plugin, QuietMoment quietMoment) {
        engine.quietMoments.put(Objects.requireNonNull(plugin, "plugin"), Objects.requireNonNull(quietMoment, "quietMoment"));
    }

    @Override
    public void unregisterQuietMoment(Plugin plugin) {
        engine.quietMoments.remove(plugin);
    }

    @Override
    public void registerBackend(Plugin plugin, String type, StorageBackend backend) {
        Objects.requireNonNull(plugin, "plugin");
        String key = Objects.requireNonNull(type, "type").strip().toLowerCase(java.util.Locale.ROOT);
        if (Remotes.TYPES.contains(key)) {
            throw new IllegalArgumentException("\"" + type + "\" is one of xBackup's own remote types.");
        }
        engine.backends.put(key, new Remotes.Registered(plugin::isEnabled, Objects.requireNonNull(backend, "backend")));
    }

    @Override
    public void unregisterBackend(String type) {
        engine.backends.remove(type.strip().toLowerCase(java.util.Locale.ROOT));
    }
}
