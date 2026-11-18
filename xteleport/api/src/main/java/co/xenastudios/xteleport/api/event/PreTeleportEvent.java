package co.xenastudios.xteleport.api.event;

import co.xenastudios.xteleport.api.TeleportType;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

/**
 * A teleport is about to happen: the warmup is over, the destination is loaded and was
 * found safe, and nothing has been charged yet. Cancel it to stop the teleport, as a
 * combat-tag, jail or minigame plugin would; xTeleport says nothing to the player then,
 * so tell them why.
 *
 * <p>Fired on the player's own thread (the main thread on Paper), and built only while
 * something listens for it.
 *
 * @since 1.0.0
 */
public final class PreTeleportEvent extends PlayerEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Location to;
    private final TeleportType type;
    private boolean cancelled;

    /**
     * Made by xTeleport.
     *
     * @param player who is teleporting
     * @param to where to
     * @param type what kind of teleport
     * @since 1.0.0
     */
    public PreTeleportEvent(Player player, Location to, TeleportType type) {
        super(player);
        this.to = to;
        this.type = type;
    }

    /**
     * Where the player is going.
     *
     * @return a copy of the destination
     * @since 1.0.0
     */
    public Location getTo() {
        return to.clone();
    }

    /**
     * What kind of teleport this is.
     *
     * @return its type
     * @since 1.0.0
     */
    public TeleportType getType() {
        return type;
    }

    /**
     * Whether the teleport is cancelled.
     *
     * @return true if it won't happen
     * @since 1.0.0
     */
    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * Cancels the teleport, or lets it happen again.
     *
     * @param cancel true to stop it
     * @since 1.0.0
     */
    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    /**
     * Bukkit's handler list.
     *
     * @return the handlers
     * @since 1.0.0
     */
    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    /**
     * Bukkit's handler list.
     *
     * @return the handlers
     * @since 1.0.0
     */
    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
