package co.xenastudios.xteleport.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

/**
 * A player is sending a teleport request with {@code /tpa} or {@code /tpahere}. Cancel it
 * to stop the request, as a plugin that keeps muted or jailed players apart would;
 * xTeleport says nothing to the player then, so tell them why.
 *
 * <p>Fired on the sender's own thread (the main thread on Paper), and built only while
 * something listens for it.
 *
 * @since 1.0.0
 */
public final class TpaRequestEvent extends PlayerEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player target;
    private final boolean here;
    private boolean cancelled;

    /**
     * Made by xTeleport.
     *
     * @param sender who is asking
     * @param target who is asked
     * @param here true for {@code /tpahere} (the target would come to the sender), false
     *     for {@code /tpa} (the sender would go to the target)
     * @since 1.0.0
     */
    public TpaRequestEvent(Player sender, Player target, boolean here) {
        super(sender);
        this.target = target;
        this.here = here;
    }

    /**
     * Who the request is for.
     *
     * @return the target
     * @since 1.0.0
     */
    public Player getTarget() {
        return target;
    }

    /**
     * Whether it's {@code /tpahere}: the target would come to the sender.
     *
     * @return true for {@code /tpahere}, false for {@code /tpa}
     * @since 1.0.0
     */
    public boolean isHere() {
        return here;
    }

    /**
     * Whether the request is cancelled.
     *
     * @return true if it won't be sent
     * @since 1.0.0
     */
    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * Stops the request, or lets it through again.
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
