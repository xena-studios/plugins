package co.xenastudios.xvote.api;

/**
 * Where a vote came from.
 *
 * @since 1.0.0
 */
public enum VoteSource {
    /** A server list, through Votifier's original protocol (an RSA-encrypted block). */
    VOTIFIER_V1,
    /** A server list, through Votifier's second protocol (signed with a token). */
    VOTIFIER_V2,
    /** {@code /vote test}. */
    TEST,
    /** Another plugin, through {@link XVoteApi#submitVote}. */
    API
}
