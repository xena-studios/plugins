package co.xenastudios.xvote.vote;

import java.util.UUID;

/**
 * A counted vote: what decides its rewards. Worked out once, when the vote is matched to
 * a player, and stored with it, so whichever server pays it pays the same.
 *
 * @param number the player's total with this vote
 * @param streak their streak with this vote
 * @param firstToday whether it was their first vote that day
 */
public record Facts(long id, UUID player, String name, String site, int number, int streak, boolean firstToday, long at) {}
