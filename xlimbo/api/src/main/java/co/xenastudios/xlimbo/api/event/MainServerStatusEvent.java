package co.xenastudios.xlimbo.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * The main server went up or down, as xLimbo's server-check saw it. Only fired while
 * server-check is on, and only for a change: the first answer after server-check starts
 * (on startup, or when its settings change on reload) isn't one.
 *
 * <p>Fired asynchronously, on the thread that asked the main server, only when something
 * listens for it. Players are sent back afterwards, on the main thread.
 *
 * @since 2.1.0
 */
public final class MainServerStatusEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final boolean up;

    /**
     * Made by xLimbo; other plugins listen for it.
     *
     * @param up true if the main server came up; false if it went down
     * @since 2.1.0
     */
    public MainServerStatusEvent(boolean up) {
        super(true);
        this.up = up;
    }

    /**
     * Whether the main server came up, rather than went down.
     *
     * @return true if it's up now
     * @since 2.1.0
     */
    public boolean isUp() {
        return up;
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
