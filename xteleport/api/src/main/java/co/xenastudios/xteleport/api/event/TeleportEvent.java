package co.xenastudios.xteleport.api.event;

import co.xenastudios.xteleport.api.TeleportType;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

/**
 * A player arrived from one of xTeleport's teleports.
 *
 * <p>Fired on the player's own thread (the main thread on Paper), and built only while
 * something listens for it.
 *
 * @since 1.0.0
 */
public final class TeleportEvent extends PlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Location from;
    private final Location to;
    private final TeleportType type;

    /**
     * Made by xTeleport.
     *
     * @param player who teleported
     * @param from where they were
     * @param to where they arrived
     * @param type what kind of teleport
     * @since 1.0.0
     */
    public TeleportEvent(Player player, Location from, Location to, TeleportType type) {
        super(player);
        this.from = from;
        this.to = to;
        this.type = type;
    }

    /**
     * Where the player was.
     *
     * @return a copy of where they left
     * @since 1.0.0
     */
    public Location getFrom() {
        return from.clone();
    }

    /**
     * Where the player arrived.
     *
     * @return a copy of the destination
     * @since 1.0.0
     */
    public Location getTo() {
        return to.clone();
    }

    /**
     * What kind of teleport this was.
     *
     * @return its type
     * @since 1.0.0
     */
    public TeleportType getType() {
        return type;
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
