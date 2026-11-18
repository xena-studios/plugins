package co.xenastudios.xvote.api;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * xVote's developer API: players' vote totals and streaks, the leaderboards, the vote
 * party, reward types other plugins add, and votes that arrive some other way.
 *
 * <p>Get it with {@link #get()}, or from Bukkit's services manager. Every read answers
 * from memory and returns at once, on any thread; {@link #submitVote} returns a future.
 *
 * @since 1.0.0
 */
public interface XVoteApi {

    /**
     * xVote's API, from Bukkit's services manager.
     *
     * @return the API
     * @throws IllegalStateException if xVote isn't enabled
     * @since 1.0.0
     */
    static XVoteApi get() {
        XVoteApi api = Bukkit.getServicesManager().load(XVoteApi.class);
        if (api == null) {
            throw new IllegalStateException("xVote isn't enabled. Add it to your plugin's depend or softdepend, and check it's installed.");
        }
        return api;
    }

    /**
     * A player's totals, streak and last vote on each site. Any thread, from memory,
     * returns at once.
     *
     * @param player the player's uuid
     * @return their stats; all zeros for a player who has never voted
     * @since 1.0.0
     */
    VoterStats stats(UUID player);

    /**
     * The top voters for a period, most votes first. Any thread, from memory, returns at once.
     *
     * @param period which totals to rank by
     * @param limit the most to return, at least 1
     * @return up to {@code limit} voters, ranked; players with no votes in the period are left out
     * @since 1.0.0
     */
    List<TopVoter> top(Period period, int limit);

    /**
     * How far the vote party has got. Any thread, from memory, returns at once.
     *
     * @return the votes counted toward the next party, and how many it needs
     * @since 1.0.0
     */
    PartyProgress party();

    /**
     * Adds a kind of reward server owners can use in xVote's config, such as a crate
     * key: a reward's {@code custom} list names it, as {@code "<id> <argument>"}. Any
     * thread. Registering an id again replaces it. A type whose plugin is disabled is
     * skipped, with a warning.
     *
     * @param plugin the plugin adding it
     * @param id what the config calls it: letters, digits, {@code -} and {@code _}, any case
     * @param type what giving it does
     * @throws IllegalArgumentException if the id has other characters
     * @since 1.0.0
     */
    void registerRewardType(Plugin plugin, String id, RewardType type);

    /**
     * Counts a vote that arrived some other way, such as a website's webhook or a
     * Discord bot, through the same ledger, checks and rewards as one from a server
     * list. Any thread.
     *
     * @param site the site the vote is for, as a list would name itself (its service name)
     * @param username the voter's Minecraft name
     * @param address the voter's IP address if known, or an empty string
     * @return a future completed off the server's threads once the vote is stored, or dropped
     * @since 1.0.0
     */
    CompletableFuture<VoteResult> submitVote(String site, String username, String address);
}
