package co.xenastudios.xbackup.api.event;

import co.xenastudios.xbackup.api.BackupSnapshot;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * A backup finished and is safely stored. Fired off the game threads (asynchronous), and
 * only built when something listens.
 *
 * @since 1.0.0
 */
public final class BackupFinishedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();
    private final BackupSnapshot snapshot;

    /**
     * Made by xBackup.
     *
     * @param snapshot the new backup
     * @since 1.0.0
     */
    public BackupFinishedEvent(BackupSnapshot snapshot) {
        super(true);
        this.snapshot = snapshot;
    }

    /**
     * The new backup: its size, how long it took and how long the server paused.
     *
     * @return the snapshot
     * @since 1.0.0
     */
    public BackupSnapshot snapshot() {
        return snapshot;
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
