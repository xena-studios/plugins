package co.xenastudios.xlimbo.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jetbrains.annotations.NotNull;

/**
 * A player arrived in limbo (joined this server).
 *
 * <p>Proxies don't tell the server they send a player to why they did, so the
 * {@link #getReason() reason} is what xLimbo can tell for itself. Fired on the main
 * thread as the player joins, only when something listens for it.
 *
 * @since 2.1.0
 */
public final class PlayerEnterLimboEvent extends PlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    /**
     * Why the player is here, as far as xLimbo can tell.
     *
     * @since 2.1.0
     */
    public enum Reason {
        /** Server-check says the main server is down. */
        MAIN_SERVER_DOWN,
        /**
         * xLimbo sent them back moments ago and they're here again: the main server
         * refused them (full, banned, kicked, or not ready).
         */
        REFUSED,
        /** Anything else: kicked to limbo by the proxy, sent on purpose, or joined it directly. */
        UNKNOWN
    }

    private final Reason reason;

    /**
     * Made by xLimbo; other plugins listen for it.
     *
     * @param player the player who arrived
     * @param reason why, as far as xLimbo can tell
     * @since 2.1.0
     */
    public PlayerEnterLimboEvent(@NotNull Player player, @NotNull Reason reason) {
        super(player);
        this.reason = reason;
    }

    /**
     * Why the player is here, as far as xLimbo can tell.
     *
     * @return the reason
     * @since 2.1.0
     */
    public @NotNull Reason getReason() {
        return reason;
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
