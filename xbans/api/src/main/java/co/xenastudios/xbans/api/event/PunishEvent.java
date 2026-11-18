package co.xenastudios.xbans.api.event;

import co.xenastudios.xbans.api.PunishRequest;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * A punishment is about to be stored, from a staff command or {@link co.xenastudios.xbans.api.XBansApi#punish}.
 * Cancel it to stop it; the staff member is told it was stopped.
 *
 * <p>Fired asynchronously, off the server's threads, after the command's checks and before
 * anything is stored (never inside a database transaction, so a listener may call the API
 * and wait for it). Only built when something listens for it. Not fired for punishments
 * made on another server of a network.
 *
 * @since 1.0.0
 */
public final class PunishEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final PunishRequest request;
    private boolean cancelled;

    /**
     * Made by xBans; other plugins listen for it.
     *
     * @param request the punishment about to be made, with its duration and scope worked out
     * @since 1.0.0
     */
    public PunishEvent(@NotNull PunishRequest request) {
        super(true);
        this.request = request;
    }

    /**
     * The punishment about to be made.
     *
     * @return the request
     * @since 1.0.0
     */
    public @NotNull PunishRequest getRequest() {
        return request;
    }

    /**
     * Whether it won't be made.
     *
     * @return true if cancelled
     * @since 1.0.0
     */
    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * Stops the punishment, or lets it go ahead.
     *
     * @param cancel true to stop it
     * @since 1.0.0
     */
    @Override
    public void setCancelled(boolean cancel) {
        cancelled = cancel;
    }

    /**
     * This event's listeners.
     *
     * @return the handlers
     * @since 1.0.0
     */
    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    /**
     * Every listener for this event, as Bukkit needs.
     *
     * @return the handlers
     * @since 1.0.0
     */
    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
