package co.xenastudios.xlimbo.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jetbrains.annotations.NotNull;

/**
 * A player is about to be sent back to the main server. Cancel it to keep them in limbo.
 *
 * <p>Fired on the main thread, only when something listens for it. A cancelled automatic
 * return is tried again on a later pass of the queue, so the event can fire again for
 * the same player.
 *
 * @since 2.1.0
 */
public final class PlayerReturnEvent extends PlayerEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    /**
     * What's sending the player back.
     *
     * @since 2.1.0
     */
    public enum Cause {
        /**
         * The return queue: auto-join, server-check seeing the main server up, or a plugin's
         * {@link co.xenastudios.xlimbo.api.XLimboApi#sendBack} while the queue is on.
         */
        QUEUE,
        /** The player ran {@code /join}. */
        JOIN_COMMAND,
        /** Another plugin's {@link co.xenastudios.xlimbo.api.XLimboApi#sendBack} while the queue is off, so sent at once. */
        PLUGIN
    }

    private final String server;
    private final Cause cause;
    private boolean cancelled;

    /**
     * Made by xLimbo; other plugins listen for it.
     *
     * @param player the player about to be sent
     * @param server the proxy's name for the main server
     * @param cause what's sending them
     * @since 2.1.0
     */
    public PlayerReturnEvent(@NotNull Player player, @NotNull String server, @NotNull Cause cause) {
        super(player);
        this.server = server;
        this.cause = cause;
    }

    /**
     * Where the player is going: the proxy's name for the main server
     * ({@code main-server} in xLimbo's config).
     *
     * @return the server's name
     * @since 2.1.0
     */
    public @NotNull String getServer() {
        return server;
    }

    /**
     * What's sending the player back.
     *
     * @return the cause
     * @since 2.1.0
     */
    public @NotNull Cause getCause() {
        return cause;
    }

    /**
     * Whether the player stays in limbo.
     *
     * @return true if cancelled
     * @since 2.1.0
     */
    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * Keeps the player in limbo. They're told nothing, so tell them why.
     *
     * @param cancel true to cancel
     * @since 2.1.0
     */
    @Override
    public void setCancelled(boolean cancel) {
        cancelled = cancel;
    }

    /**
     * This event's listeners.
     *
     * @return the handlers
     * @since 2.1.0
     */
    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    /**
     * Every listener for this event, as Bukkit needs.
     *
     * @return the handlers
     * @since 2.1.0
     */
    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
