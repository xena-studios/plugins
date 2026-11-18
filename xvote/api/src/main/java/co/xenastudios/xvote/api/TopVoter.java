package co.xenastudios.xvote.api;

import java.util.UUID;

/**
 * One place on a leaderboard.
 *
 * @param rank the place, from 1
 * @param player the player's uuid
 * @param name the name they last voted with
 * @param votes their votes in the leaderboard's period
 * @since 1.0.0
 */
public record TopVoter(int rank, UUID player, String name, int votes) {}
