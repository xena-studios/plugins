package co.xenastudios.xvote.api

import co.xenastudios.core.api.PlayerRef

/**
 * A stretch of time votes are totalled over. Days, weeks and months start at midnight in the time
 * zone the server owner set, and weeks on the day they chose.
 *
 * @since 1.0.0
 */
public enum class Period {
    /** Today. */
    DAY,

    /** This week. */
    WEEK,

    /** This month. */
    MONTH,

    /** Every vote ever counted. */
    ALL,
}

/**
 * What happened to a vote.
 *
 * @since 1.0.0
 */
public enum class VoteResult {
    /** Stored and counted; its rewards are paid now if the player is online, or when they next join, on whichever server. */
    COUNTED,

    /** Stored, but no player with that name has joined yet; it's counted and paid when they first do. */
    WAITING,

    /** Dropped: the player already voted on that site within its cooldown, or the vote was a repeat. */
    DUPLICATE,

    /** Dropped: the name can't be a Minecraft name. */
    INVALID,

    /** Dropped by a plugin that cancelled its [co.xenastudios.xvote.api.event.VoteReceivedEvent]. */
    CANCELLED,

    /** Couldn't be stored: the database isn't reachable. The console says why. */
    FAILED,
}

/**
 * Where a vote came from.
 *
 * @since 1.0.0
 */
public enum class VoteSource {
    /** A server list, through Votifier's original protocol (an RSA-encrypted block). */
    VOTIFIER_V1,

    /** A server list, through Votifier's second protocol (signed with a token). */
    VOTIFIER_V2,

    /** `/vote test`. */
    TEST,

    /** Another plugin, through [XVoteApi.submitVote]. */
    API,

    /** NuVotifier on the proxy, forwarding a vote it received by plugin message. @since 2.0.0 */
    FORWARDED,
}

/**
 * One player's votes.
 *
 * @since 1.0.0
 */
public class VoterStats(
    public val player: PlayerRef,
    /** The name they last voted with, or an empty string if they've never voted. */
    public val name: String,
    /** Every vote counted for them. */
    public val total: Int,
    public val today: Int,
    public val week: Int,
    public val month: Int,
    /** Days in a row, up to today or yesterday, with at least one vote. */
    public val streak: Int,
    public val bestStreak: Int,
    /** When they last voted on each site (epoch milliseconds), by the site's service name in lower case, for the sites voted on recently. */
    public val lastVotes: Map<String, Long>,
) {
    /** The total for [period]. */
    public fun votes(period: Period): Int = when (period) {
        Period.DAY -> today
        Period.WEEK -> week
        Period.MONTH -> month
        Period.ALL -> total
    }

    override fun toString(): String = "VoterStats($player, $name, total $total)"
}

/**
 * One place on a leaderboard.
 *
 * @since 1.0.0
 */
public class TopVoter(
    /** The place, from 1. */
    public val rank: Int,
    public val player: PlayerRef,
    /** The name they last voted with. */
    public val name: String,
    /** Their votes in the leaderboard's period. */
    public val votes: Int,
) {
    override fun toString(): String = "TopVoter(#$rank $name $votes)"
}

/**
 * How far the vote party has got.
 *
 * @since 1.0.0
 */
public class PartyProgress(
    /** Votes counted toward the next party. */
    public val votes: Int,
    /** Votes a party needs, or 0 when the vote party is off. */
    public val needed: Int,
) {
    /** Votes still needed for the next party, at least 0. */
    public val left: Int get() = maxOf(0, needed - votes)

    override fun toString(): String = "PartyProgress($votes of $needed)"
}

/**
 * A kind of reward another plugin adds with [XVoteApi.registerRewardType]: a reward in xVote's
 * config names it in its `custom` list as `"<id> <argument>"`.
 *
 * @since 1.0.0
 */
public fun interface RewardType {
    /**
     * Gives the reward to [player], who is online on this server. Called on the player's own
     * thread (the main thread on Paper and Fabric, their region's on Folia). [argument] is what
     * the config wrote after the id, with `<player>`, `<uuid>`, `<site>`, `<total>` and `<streak>`
     * filled in; empty if nothing.
     */
    public fun give(player: PlayerRef, argument: String)
}
