package co.xenastudios.xbackup.api.event;

import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * A backup is about to start. Cancel it to skip this one, such as during an event; a
 * scheduled backup then runs again at its next time. Fired on the global thread (the main
 * thread on Paper), and only built when something listens.
 *
 * @since 1.0.0
 */
public final class BackupStartEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();
    private final String reason;
    private boolean cancelled;

    /**
     * Made by xBackup.
     *
     * @param reason why the backup runs
     * @since 1.0.0
     */
    public BackupStartEvent(String reason) {
        this.reason = reason;
    }

    /**
     * Why the backup runs: "scheduled", "manual", or what a plugin asked with.
     *
     * @return the reason
     * @since 1.0.0
     */
    public String reason() {
        return reason;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
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
