package co.xenastudios.xbackup.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * A backup failed; nothing was saved for it, and the repository is as it was. Fired off the
 * game threads (asynchronous), and only built when something listens.
 *
 * @since 1.0.0
 */
public final class BackupFailedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();
    private final String reason;
    private final String problem;

    /**
     * Made by xBackup.
     *
     * @param reason why the backup ran
     * @param problem what went wrong, for people
     * @since 1.0.0
     */
    public BackupFailedEvent(String reason, String problem) {
        super(true);
        this.reason = reason;
        this.problem = problem;
    }

    /**
     * Why the backup ran.
     *
     * @return the reason
     * @since 1.0.0
     */
    public String reason() {
        return reason;
    }

    /**
     * What went wrong, as xBackup tells the console.
     *
     * @return the problem
     * @since 1.0.0
     */
    public String problem() {
        return problem;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    /**
     * Bukkit's handler list for this event.
     *
     * @return the handler list
     * @since 1.0.0
     */
    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
