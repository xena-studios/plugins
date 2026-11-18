package co.xenastudios.xbackup.api;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * xBackup's developer API: run backups, read their status, add folders to back up, keep a
 * plugin's files still while they're read, and add places to copy backups to.
 *
 * <p>Get it with {@link #get()}, or from Bukkit's services manager. Every method says which
 * thread it may be called from and whether it waits for anything; none of them blocks the
 * caller or touches the disk on the calling thread.
 *
 * @since 1.0.0
 */
public interface XBackupApi {

    /**
     * xBackup's API, from Bukkit's services manager.
     *
     * @return the API
     * @throws IllegalStateException if xBackup isn't enabled
     * @since 1.0.0
     */
    static XBackupApi get() {
        XBackupApi api = Bukkit.getServicesManager().load(XBackupApi.class);
        if (api == null) {
            throw new IllegalStateException("xBackup isn't enabled. Add it to the server, and depend on it in your"
                + " plugin.yml or paper-plugin.yml so it loads first.");
        }
        return api;
    }

    /**
     * Starts a backup now, or queues it behind the one running. Any thread.
     *
     * @param reason shown in {@code /backup list}, such as "before the event"
     * @return completes off the game thread with the new snapshot, or exceptionally if the
     *     backup failed or a {@link co.xenastudios.xbackup.api.event.BackupStartEvent} listener cancelled it
     * @since 1.0.0
     */
    CompletableFuture<BackupSnapshot> backupNow(String reason);

    /**
     * Every backup in the repository, newest first. Any thread; answers from memory,
     * returns immediately.
     *
     * @return the snapshots, newest first
     * @since 1.0.0
     */
    List<BackupSnapshot> listSnapshots();

    /**
     * What xBackup is doing and how the last backup went. Any thread; answers from memory,
     * returns immediately.
     *
     * @return the status
     * @since 1.0.0
     */
    BackupStatus getStatus();

    /**
     * Adds a folder or file to every backup, until the plugin is disabled or
     * {@link #unregisterSources} is called. Any thread; returns immediately.
     *
     * @param plugin the plugin that owns it
     * @param path a path inside the server folder
     * @param excludes glob patterns, relative to the server folder, to leave out
     * @since 1.0.0
     */
    void registerSource(Plugin plugin, Path path, List<String> excludes);

    /**
     * Removes every source the plugin added. Any thread; returns immediately.
     *
     * @param plugin the plugin
     * @since 1.0.0
     */
    void unregisterSources(Plugin plugin);

    /**
     * Asks xBackup to keep the plugin's writes paused while it reads the plugin's data
     * folder, for plugins that write files in several steps. SQLite databases need none of
     * this: xBackup always copies them through SQLite itself. Any thread; returns
     * immediately.
     *
     * @param plugin the plugin whose data folder this covers
     * @param quietMoment called before and after the folder is read
     * @since 1.0.0
     */
    void registerQuietMoment(Plugin plugin, QuietMoment quietMoment);

    /**
     * Removes the plugin's quiet moment. Any thread; returns immediately.
     *
     * @param plugin the plugin
     * @since 1.0.0
     */
    void unregisterQuietMoment(Plugin plugin);

    /**
     * Adds a place backups can be copied to. A remote in xBackup's config with
     * {@code type: <type>} then uses it. Any thread; returns immediately.
     *
     * @param plugin the plugin that provides it; removed when that plugin is disabled
     * @param type the name owners write as the remote's type, such as {@code "myplugin-s3"}
     * @param backend the storage
     * @since 1.0.0
     */
    void registerBackend(Plugin plugin, String type, StorageBackend backend);

    /**
     * Removes a backend added with {@link #registerBackend}. Any thread; returns immediately.
     *
     * @param type the type it was registered as
     * @since 1.0.0
     */
    void unregisterBackend(String type);
}
