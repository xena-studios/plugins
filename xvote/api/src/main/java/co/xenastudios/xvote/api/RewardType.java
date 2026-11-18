package co.xenastudios.xvote.api;

import org.bukkit.entity.Player;

/**
 * A kind of reward another plugin adds, registered with
 * {@link XVoteApi#registerRewardType}. A reward in xVote's config names it in its
 * {@code custom} list as {@code "<id> <argument>"}.
 *
 * @since 1.0.0
 */
@FunctionalInterface
public interface RewardType {

    /**
     * Gives the reward. Called on the player's own thread (the main thread on Paper),
     * while they're online.
     *
     * @param player who gets it
     * @param argument what the config wrote after the id, with {@code <player>},
     *     {@code <site>}, {@code <total>} and {@code <streak>} filled in; an empty string if nothing
     * @since 1.0.0
     */
    void give(Player player, String argument);
}
