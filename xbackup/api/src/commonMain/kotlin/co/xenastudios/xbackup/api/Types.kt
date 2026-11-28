package co.xenastudios.xbackup.api

import kotlin.time.Instant

/**
 * One backup.
 *
 * @since 2.0.0
 */
public class BackupSnapshot(
    /** Its id, 64 hex digits; the first 8 are how `/backup` shows it ([shortId]). */
    public val id: String,
    /** When it started. */
    public val time: Instant,
    /** How long it took, in milliseconds. */
    public val durationMillis: Long,
    /** Why it ran: `scheduled`, `manual`, or what a plugin asked with. */
    public val reason: String,
    /** How many files it holds. */
    public val files: Long,
    /** The size of those files. */
    public val bytes: Long,
    /** How much new space it took in the repository. */
    public val addedBytes: Long,
    /** How long the server's threads spent saving for it, in milliseconds. */
    public val pauseMillis: Long,
    /** Whether retention will always keep it. */
    public val pinned: Boolean,
    /** What it couldn't do, such as a database it couldn't copy safely. */
    public val warnings: List<String>,
) {
    /** The id as people see it: its first 8 characters. */
    public val shortId: String get() = id.take(8)

    override fun toString(): String = "BackupSnapshot($shortId, $time, $reason)"
}

/**
 * What xBackup is doing, and how things stand.
 *
 * @since 2.0.0
 */
public class BackupStatus(
    /** What's running right now, such as `backing up: reading the worlds`; `null` when idle. */
    public val activity: String?,
    /** The newest backup, or `null` if there are none. */
    public val last: BackupSnapshot?,
    /** When the next scheduled backup runs, or `null` if the schedule is off. */
    public val nextBackup: Instant?,
    /** How many backups the repository holds. */
    public val snapshots: Int,
    /** The space the repository takes. */
    public val repositoryBytes: Long,
    /** The last thing that went wrong, or `null`. */
    public val problem: String?,
) {
    /** Whether a backup, check, restore or clean-up is running. */
    public val busy: Boolean get() = activity != null
}

/**
 * Keeps a plugin's files still while xBackup reads them. Both are called on xBackup's own
 * thread, never a game thread, one after the other.
 *
 * @since 2.0.0
 */
public interface QuietMoment {
    /**
     * Finish any write in progress and hold new ones until [resume]. Return once the files are
     * still; keep it short, xBackup waits for it (30 seconds at most, then reads anyway). Throw
     * if the plugin can't pause: its folder is then read anyway.
     */
    public fun pause()

    /** Carry on writing. Always called after [pause], even if that threw. */
    public fun resume()
}

/**
 * A place xBackup can copy its repository to. Every file is written once and never changed, so a
 * backend only needs to put, get, list and delete whole files. Called on xBackup's own threads,
 * never a game thread; throw any exception to fail the copy.
 *
 * Names are relative paths with `/`, such as `data/ab/ab12...`. A put must be all or nothing: a
 * file only appears under its name once all of it is there.
 *
 * @since 2.0.0
 */
public interface StorageBackend {
    /** For messages, such as `my bucket at example.com`. */
    public fun describe(): String

    /** Stores [file], a path on this machine, under [name], replacing any file already there. */
    public fun put(name: String, file: String)

    /** The bytes of [name], or `null` if there's no such file. */
    public fun get(name: String): ByteArray?

    /** Every file whose name starts with [prefix], at any depth. */
    public fun list(prefix: String): List<StoredFile>

    /** Removes [name]. A file that isn't there is fine. */
    public fun delete(name: String)
}

/**
 * A file a [StorageBackend] holds.
 *
 * @since 2.0.0
 */
public class StoredFile(public val name: String, public val size: Long) {
    override fun toString(): String = "$name ($size bytes)"
}
