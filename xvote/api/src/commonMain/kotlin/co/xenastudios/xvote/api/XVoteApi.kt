package co.xenastudios.xvote.api

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.event.EventBus
import kotlin.concurrent.Volatile
import kotlin.jvm.JvmStatic

/**
 * xVote's developer API, the same on Paper, Folia, Spigot, Fabric and the proxies: players' vote
 * totals and streaks, the leaderboards, the vote party, reward types other plugins add, votes
 * that arrive some other way, and the vote events. Players are [PlayerRef]s (from Java,
 * `PlayerRefs.of(player.getUniqueId())`), so one API works everywhere.
 *
 * Get it with [XVote.api]; on Bukkit it's also in the services manager. Every read answers from
 * memory at once, on any thread; [submitVote] returns a [Pending] (a `CompletableFuture` on the
 * JVM).
 *
 * @since 1.0.0
 */
public interface XVoteApi {
    /** xVote's version, such as `2.0.0`. @since 2.0.0 */
    public val version: String

    /**
     * Where xVote's events are heard ([co.xenastudios.xvote.api.event]): a vote received
     * (cancellable), counted, about to be rewarded (cancellable) and rewarded, and the vote party
     * (cancellable). On Bukkit each is also a Bukkit event in `co.xenastudios.xvote.api.bukkit`.
     *
     * @since 2.0.0
     */
    public val events: EventBus

    /** A player's totals, streak and last vote on each site; all zeros for a player who has never voted. From memory. */
    public fun stats(player: PlayerRef): VoterStats

    /** Up to [limit] (at least 1) of the top voters for [period], most votes first; players with no votes in it are left out. From memory. */
    public fun top(period: Period, limit: Int): List<TopVoter>

    /** How far the vote party has got. From memory. */
    public fun party(): PartyProgress

    /**
     * Adds a kind of reward server owners can use in xVote's config, such as a crate key: a
     * reward's `custom` list names it as `"<id> <argument>"`. [owner] is your plugin's name, for
     * the console. Registering an id again replaces it.
     *
     * @throws IllegalArgumentException if [id] isn't 1 to 64 letters, digits, `-` and `_`
     * @since 2.0.0
     */
    public fun registerRewardType(owner: String, id: String, type: RewardType)

    /** Takes a reward type away, as your plugin is disabled; rewards naming it are skipped with a warning. @since 2.0.0 */
    public fun unregisterRewardType(id: String)

    /**
     * Counts a vote that arrived some other way, such as a website's webhook or a Discord bot,
     * through the same ledger, checks and rewards as one from a server list. [site] is the site's
     * service name, as a list would send it; [address] the voter's address, or an empty string.
     * Completes off the server's threads once the vote is stored or dropped.
     */
    public fun submitVote(site: String, username: String, address: String): Pending<VoteResult>
}

/**
 * Where xVote's API is found, on every platform:
 *
 * ```java
 * XVoteApi api = XVote.api();
 * ```
 *
 * @since 2.0.0
 */
public object XVote {
    @Volatile
    private var running: XVoteApi? = null

    /**
     * xVote's API.
     *
     * @throws IllegalStateException if xVote isn't installed and enabled: add it to your
     *   plugin's `depend` (or `softdepend`, and check [isEnabled] first)
     */
    @JvmStatic
    public fun api(): XVoteApi =
        running ?: throw IllegalStateException("xVote isn't enabled. Add it to depend (or softdepend and check XVote.isEnabled() first).")

    /** Whether xVote is enabled, so [api] answers. */
    @JvmStatic
    public fun isEnabled(): Boolean = running != null

    /** Made available by xVote as it's enabled, and taken away as it's disabled: not for other plugins. */
    @JvmStatic
    public fun register(api: XVoteApi?) {
        running = api
    }
}
