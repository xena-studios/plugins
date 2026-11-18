package co.xenastudios.xvote.api;

/**
 * How far the vote party has got.
 *
 * @param votes votes counted toward the next party
 * @param needed votes a party needs, or 0 when the vote party is off
 * @since 1.0.0
 */
public record PartyProgress(int votes, int needed) {

    /**
     * Votes still needed for the next party.
     *
     * @return at least 0
     * @since 1.0.0
     */
    public int left() {
        return Math.max(0, needed - votes);
    }
}
