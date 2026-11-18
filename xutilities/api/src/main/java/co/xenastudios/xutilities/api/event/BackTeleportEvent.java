package co.xenastudios.xutilities.api.event;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jetbrains.annotations.NotNull;

/**
 * A player used {@code /back} and is about to be teleported. Cancel it to keep them
 * where they are; they're told nothing, so tell them why.
 *
 * <p>Fired on the player's thread (the main thread on Paper, the player's region thread
 * on Folia), only when something listens for it.
 *
 * @since 2.1.0
 */
public final class BackTeleportEvent extends PlayerEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Location destination;
    private boolean cancelled;

    /**
     * Made by xUtilities; other plugins listen for it.
     *
     * @param player the player using {@code /back}
     * @param destination where {@code /back} would take them
     * @since 2.1.0
     */
    public BackTeleportEvent(@NotNull Player player, @NotNull Location destination) {
        super(player);
        this.destination = destination;
    }

    /**
     * Where {@code /back} would take the player.
     *
     * @return a copy of the spot
     * @since 2.1.0
     */
    public @NotNull Location getDestination() {
        return destination.clone();
    }

    /**
     * Whether the player stays where they are.
     *
     * @return true if cancelled
     * @since 2.1.0
     */
    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * Keeps the player where they are. Their {@code /back} spot is kept.
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
