package co.xenastudios.xbackup.api

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.event.EventBus
import kotlin.concurrent.Volatile
import kotlin.jvm.JvmStatic

/**
 * xBackup's developer API, the same on Paper, Folia, Spigot and Fabric: run a backup, read the
 * list and the status from memory, add folders to back up, keep a plugin's files still while
 * they're read, add places to copy backups to, and the backup events.
 *
 * Get it with [XBackup.api]. On Bukkit it's also in the services manager while xBackup is
 * enabled. Nothing here blocks the caller or touches the disk on its thread: reads answer from
 * memory, and anything that does work returns a [Pending] (a `CompletableFuture` on the JVM) that
 * settles on xBackup's own thread.
 *
 * The family's own plugins need none of this for their databases: each rewritten Xena plugin's
 * database is copied by its own writer during a backup, through the core
 * (decisions/2026-11-27-the-familys-backup-seam.md).
 *
 * @since 2.0.0
 */
public interface XBackupApi {
    /** The running xBackup's version, such as `2.0.0`. */
    public val version: String

    /**
     * The backup events: [co.xenastudios.xbackup.api.event.BackupStartEvent] (cancellable) before
     * a backup, [co.xenastudios.xbackup.api.event.BackupFinishedEvent] and
     * [co.xenastudios.xbackup.api.event.BackupFailedEvent] after. On Bukkit each is also a Bukkit
     * event in `co.xenastudios.xbackup.api.bukkit`.
     */
    public val events: EventBus

    /**
     * Starts a backup now, or queues it behind the job running. Any thread. Settles off the game
     * threads with the new backup, or fails with a message for people if the backup failed or a
     * [co.xenastudios.xbackup.api.event.BackupStartEvent] listener cancelled it.
     *
     * @param reason shown in `/backup list`, such as `before the event`
     */
    public fun backupNow(reason: String): Pending<BackupSnapshot>

    /** Every backup in the repository, newest first. Any thread, from memory. */
    public fun listSnapshots(): List<BackupSnapshot>

    /** What xBackup is doing, and how the last backup went. Any thread, from memory. */
    public fun getStatus(): BackupStatus

    /**
     * Adds a folder or file to every backup, until [unregisterSources] is called for [owner] (on
     * Bukkit also when that plugin is disabled). Any thread.
     *
     * @param owner the plugin that owns it, by name, such as `MyPlugin`
     * @param path a path inside the server folder, relative to it or absolute
     * @param excludes glob patterns to leave out, which only ever apply inside this source: one
     *     that starts with the source's path is used as it is, any other is read from the source
     *     itself (`cache` and everything in it, or every `.tmp` file in it)
     */
    public fun registerSource(owner: String, path: String, excludes: List<String>)

    /** [registerSource] with nothing left out. */
    public fun registerSource(owner: String, path: String): Unit = registerSource(owner, path, emptyList())

    /** Removes every source [owner] added. Any thread. */
    public fun unregisterSources(owner: String)

    /**
     * Keeps [owner]'s writes paused while xBackup reads [folder] (its data folder), for a plugin
     * that writes files in several steps. SQLite databases need none of this. Any thread.
     *
     * @param folder the folder it covers, inside the server folder, relative to it or absolute
     */
    public fun registerQuietMoment(owner: String, folder: String, quietMoment: QuietMoment)

    /** Removes [owner]'s quiet moment. Any thread. */
    public fun unregisterQuietMoment(owner: String)

    /**
     * Adds a place backups can be copied to: a remote in xBackup's config with `type: <type>`
     * then uses [backend]. Any thread.
     *
     * @param type the name owners write as the remote's type, such as `myplugin-s3`; not one of
     *     xBackup's own (local, s3, webdav, sftp, ftp, drive, onedrive, dropbox)
     * @throws IllegalArgumentException if [type] is one of xBackup's own
     */
    public fun registerBackend(owner: String, type: String, backend: StorageBackend)

    /** Removes the backend registered as [type]. Any thread. */
    public fun unregisterBackend(type: String)
}

/**
 * Where xBackup's API is found, on every platform:
 *
 * ```java
 * XBackupApi api = XBackup.api();
 * ```
 *
 * @since 2.0.0
 */
public object XBackup {
    @Volatile
    private var running: XBackupApi? = null

    /**
     * xBackup's API.
     *
     * @throws IllegalStateException if xBackup isn't installed and enabled
     */
    @JvmStatic
    public fun api(): XBackupApi =
        running ?: throw IllegalStateException("xBackup isn't enabled. Add it to depend (or softdepend and check XBackup.isEnabled() first).")

    /** Whether xBackup is enabled, so [api] answers. */
    @JvmStatic
    public fun isEnabled(): Boolean = running != null

    /** Made available by xBackup as it's enabled, and taken away as it's disabled: not for other plugins. */
    @JvmStatic
    public fun register(api: XBackupApi?) {
        running = api
    }
}
