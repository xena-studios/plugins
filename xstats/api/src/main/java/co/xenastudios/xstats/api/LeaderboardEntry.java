package co.xenastudios.xstats.api;

import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * One place on a leaderboard.
 *
 * @param rank the place, from 1; players with the same value share a place
 * @param player the player's uuid
 * @param name the player's last known name
 * @param value their value for the board's stat and period, in the stat's {@link StatUnit}
 * @since 1.0.0
 */
public record LeaderboardEntry(int rank, @NotNull UUID player, @NotNull String name, long value) {}
