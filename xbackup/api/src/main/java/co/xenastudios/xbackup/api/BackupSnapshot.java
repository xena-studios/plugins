package co.xenastudios.xbackup.api;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * One backup.
 *
 * @param id its id; the first 8 characters are how {@code /backup} shows it
 * @param time when it started
 * @param duration how long it took
 * @param reason why it ran: "scheduled", "manual", or what a plugin asked with
 * @param files how many files it holds
 * @param bytes the size of those files
 * @param addedBytes how much new space it took in the repository
 * @param pause how long the server's threads spent saving for it
 * @param pinned whether retention will always keep it
 * @param warnings what it couldn't do, such as a database it couldn't copy safely
 * @since 1.0.0
 */
public record BackupSnapshot(String id, Instant time, Duration duration, String reason, long files, long bytes,
                             long addedBytes, Duration pause, boolean pinned, List<String> warnings) {

    /**
     * Makes a snapshot record; the list is copied.
     *
     * @since 1.0.0
     */
    public BackupSnapshot {
        warnings = List.copyOf(warnings);
    }

    /**
     * The id as people see it: its first 8 characters.
     *
     * @return the short id
     * @since 1.0.0
     */
    public String shortId() {
        return id.substring(0, Math.min(8, id.length()));
    }
}
