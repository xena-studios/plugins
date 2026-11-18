package co.xenastudios.xvote.api;

/**
 * What happened to a vote handed to {@link XVoteApi#submitVote}.
 *
 * @since 1.0.0
 */
public enum VoteResult {
    /** Stored and counted; its rewards are paid now if the player is online, or when they join. */
    COUNTED,
    /** Stored, but no player with that name has joined yet; it's counted and paid when they first do. */
    WAITING,
    /** Dropped: the player already voted on that site within its cooldown, or the vote was a repeat. */
    DUPLICATE,
    /** Dropped: the name can't be a Minecraft name. */
    INVALID,
    /** Dropped by a plugin that cancelled its {@code VoteReceivedEvent}. */
    CANCELLED,
    /** Couldn't be stored: the database isn't reachable. The console says why. */
    FAILED
}
