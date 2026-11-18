package co.xenastudios.xutilities.api.event;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jetbrains.annotations.NotNull;

/**
 * A spot is about to be saved as where {@code /back} takes a player. Cancel it to keep
 * the spot they had before, such as so {@code /back} can't return into a minigame or a
 * jail.
 *
 * <p>Fired on the player's thread (the main thread on Paper, the player's region thread
 * on Folia), only while {@code /back} is on, and only when something listens for it.
 * Not fired for {@link co.xenastudios.xutilities.api.XUtilitiesApi#setBackLocation}.
 *
 * @since 2.1.0
 */
public final class BackRecordEvent extends PlayerEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    /**
     * Why the spot is being saved.
     *
     * @since 2.1.0
     */
    public enum Reason {
        /** The player was teleported by a command or a plugin; the spot is where they were. */
        TELEPORT,
        /** The player died; the spot is where. */
        DEATH
    }

    private final Location location;
    private final Reason reason;
    private boolean cancelled;

    /**
     * Made by xUtilities; other plugins listen for it.
     *
     * @param player the player
     * @param location the spot about to be saved
     * @param reason why
     * @since 2.1.0
     */
    public BackRecordEvent(@NotNull Player player, @NotNull Location location, @NotNull Reason reason) {
        super(player);
        this.location = location;
        this.reason = reason;
    }

    /**
     * The spot about to be saved.
     *
     * @return a copy of the spot
     * @since 2.1.0
     */
    public @NotNull Location getLocation() {
        return location.clone();
    }

    /**
     * Why the spot is being saved.
     *
     * @return the reason
     * @since 2.1.0
     */
    public @NotNull Reason getReason() {
        return reason;
    }

    /**
     * Whether the spot won't be saved.
     *
     * @return true if cancelled
     * @since 2.1.0
     */
    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * Keeps the spot from being saved; the player keeps the one they had.
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
