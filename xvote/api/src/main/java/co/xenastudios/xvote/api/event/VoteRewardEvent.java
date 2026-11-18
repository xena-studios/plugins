package co.xenastudios.xvote.api.event;

import java.util.List;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

/**
 * A player is about to be rewarded for a vote. Fired on the player's own thread (the
 * main thread on Paper), and only built when something listens. Cancelling it pays
 * nothing for this vote; the vote still counts toward totals, streaks and the party.
 *
 * @since 1.0.0
 */
public final class VoteRewardEvent extends PlayerEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String site;
    private final List<String> rewards;
    private boolean cancelled;

    /**
     * Made by xVote.
     *
     * @param player who is rewarded
     * @param site the site's service name
     * @param rewards the names of the rewards from xVote's config about to be given; a
     *     listener may remove some
     * @since 1.0.0
     */
    public VoteRewardEvent(Player player, String site, List<String> rewards) {
        super(player);
        this.site = site;
        this.rewards = rewards;
    }

    /**
     * The site the vote was for, by its service name.
     *
     * @return the service name
     * @since 1.0.0
     */
    public String getSite() {
        return site;
    }

    /**
     * The rewards about to be given, by their names in xVote's config. Removing one
     * leaves it out; each still rolls its own chance.
     *
     * @return the reward names, modifiable
     * @since 1.0.0
     */
    public List<String> getRewards() {
        return rewards;
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
