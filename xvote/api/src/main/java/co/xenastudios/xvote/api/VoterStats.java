package co.xenastudios.xvote.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One player's votes.
 *
 * @param player the player's uuid
 * @param name the name they last voted with, or an empty string if they've never voted
 * @param total every vote counted for them
 * @param today votes today
 * @param week votes this week
 * @param month votes this month
 * @param streak days in a row, up to today or yesterday, with at least one vote
 * @param bestStreak their longest streak
 * @param lastVotes when they last voted on each site, by the site's service name, for
 *     the sites voted on recently; unmodifiable
 * @since 1.0.0
 */
public record VoterStats(UUID player, String name, int total, int today, int week, int month,
                         int streak, int bestStreak, Map<String, Instant> lastVotes) {

    /**
     * The total for a period.
     *
     * @param period which total
     * @return that many votes
     * @since 1.0.0
     */
    public int votes(Period period) {
        return switch (period) {
            case DAY -> today;
            case WEEK -> week;
            case MONTH -> month;
            case ALL -> total;
        };
    }
}
