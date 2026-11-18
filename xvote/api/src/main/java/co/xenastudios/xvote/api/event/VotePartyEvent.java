package co.xenastudios.xvote.api.event;

import java.util.List;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * The vote party is about to reward players on this server. Fired on the global thread
 * (the main thread on Paper) of every server in a network, each for its own players, and
 * only built when something listens. Cancelling it gives this server's players nothing;
 * the party's count starts again either way.
 *
 * @since 1.0.0
 */
public final class VotePartyEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final List<Player> players;
    private boolean cancelled;

    /**
     * Made by xVote.
     *
     * @param players who is about to be rewarded; a listener may remove some
     * @since 1.0.0
     */
    public VotePartyEvent(List<Player> players) {
        this.players = players;
    }

    /**
     * Who is about to be rewarded: everyone online, or everyone online who voted since
     * the last party, as the config says.
     *
     * @return the players, modifiable
     * @since 1.0.0
     */
    public List<Player> getPlayers() {
        return players;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    /**
     * Bukkit's handler list for this event.
     *
     * @return the handler list
     * @since 1.0.0
     */
    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
