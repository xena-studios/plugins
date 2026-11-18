package co.xenastudios.xteleport.api.event;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

/**
 * A player is setting a home with {@code /sethome}, within their limit. Cancel it to
 * refuse, as a claims plugin would for a home in someone else's land; xTeleport says
 * nothing to the player then, so tell them why.
 *
 * <p>Fired on the player's own thread (the main thread on Paper), and built only while
 * something listens for it.
 *
 * @since 1.0.0
 */
public final class HomeSetEvent extends PlayerEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String name;
    private final Location location;
    private final boolean replacing;
    private boolean cancelled;

    /**
     * Made by xTeleport.
     *
     * @param player who is setting it
     * @param name the home's name
     * @param location where
     * @param replacing whether it moves a home they already have
     * @since 1.0.0
     */
    public HomeSetEvent(Player player, String name, Location location, boolean replacing) {
        super(player);
        this.name = name;
        this.location = location;
        this.replacing = replacing;
    }

    /**
     * The home's name, as the player typed it.
     *
     * @return the name
     * @since 1.0.0
     */
    public String getName() {
        return name;
    }

    /**
     * Where the home would be.
     *
     * @return a copy of the spot
     * @since 1.0.0
     */
    public Location getLocation() {
        return location.clone();
    }

    /**
     * Whether this moves a home the player already has, rather than adding one.
     *
     * @return true if a home by this name exists
     * @since 1.0.0
     */
    public boolean isReplacing() {
        return replacing;
    }

    /**
     * Whether setting the home is cancelled.
     *
     * @return true if it won't be set
     * @since 1.0.0
     */
    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * Refuses the home, or allows it again.
     *
     * @param cancel true to refuse it
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
