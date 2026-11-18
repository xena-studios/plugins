package co.xenastudios.xbans.api.event;

import co.xenastudios.xbans.api.Punishment;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * A ban, mute or warning was revoked (an unban, unmute or unwarn): the database has committed the removal.
 *
 * <p>Fired asynchronously: on xBans' database thread for a change made on this server,
 * or on the network thread for one made on another server of a network
 * ({@link #isFromOtherServer()}). Only built when something listens for it.
 *
 * @since 1.0.0
 */
public final class RevokedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Punishment punishment;
    private final boolean fromOtherServer;

    /**
     * Made by xBans; other plugins listen for it.
     *
     * @param punishment the punishment revoked, with its {@link co.xenastudios.xbans.api.Removal}
     * @param fromOtherServer whether another server of the network made the change
     * @since 1.0.0
     */
    public RevokedEvent(@NotNull Punishment punishment, boolean fromOtherServer) {
        super(true);
        this.punishment = punishment;
        this.fromOtherServer = fromOtherServer;
    }

    /**
     * The punishment.
     *
     * @return the punishment revoked, with its {@link co.xenastudios.xbans.api.Removal}
     * @since 1.0.0
     */
    public @NotNull Punishment getPunishment() {
        return punishment;
    }

    /**
     * Whether another server of the network made the change, so this server only heard
     * about it.
     *
     * @return true if it came from another server
     * @since 1.0.0
     */
    public boolean isFromOtherServer() {
        return fromOtherServer;
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
