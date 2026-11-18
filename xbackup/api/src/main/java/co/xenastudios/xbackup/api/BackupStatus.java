package co.xenastudios.xbackup.api;

import java.time.Instant;

/**
 * What xBackup is doing, and how things stand.
 *
 * @param activity what's running right now, such as "backing up: reading the worlds", or
 *     null when idle
 * @param last the newest backup, or null if there are none
 * @param nextBackup when the next scheduled backup runs, or null if the schedule is off
 * @param snapshots how many backups the repository holds
 * @param repositoryBytes the space the repository takes
 * @param problem the last thing that went wrong, or null
 * @since 1.0.0
 */
public record BackupStatus(String activity, BackupSnapshot last, Instant nextBackup, int snapshots,
                           long repositoryBytes, String problem) {

    /**
     * Whether a backup, check, restore or clean-up is running.
     *
     * @return true while something runs
     * @since 1.0.0
     */
    public boolean busy() {
        return activity != null;
    }
}
