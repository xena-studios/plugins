package co.xenastudios.xvote.vote;

import java.util.Map;
import java.util.UUID;

/**
 * One player's votes, as last read from the ledger. Never changed: a new vote makes a new
 * one, swapped in whole, so any thread can read it.
 *
 * <p>The day, week and month totals are kept with the key of the period they were counted
 * in; read through {@link #today}, {@link #week} and {@link #month}, a total from a period
 * that's over is 0.
 *
 * @param lastBySite when they last voted on each site, by service name in lower case, for
 *     the last couple of days
 */
public record Voter(UUID id, String name, int total, int streak, int bestStreak, int lastDay, long lastVote,
                    int dayKey, int day, int weekKey, int week, int monthKey, int month, Map<String, Long> lastBySite) {

    public int today(Periods periods, long now) {
        return periods.dayKey(now) == dayKey ? day : 0;
    }

    public int week(Periods periods, long now) {
        return periods.weekKey(now) == weekKey ? week : 0;
    }

    public int month(Periods periods, long now) {
        return periods.monthKey(now) == monthKey ? month : 0;
    }

    /** The streak as it stands: 0 once a whole day has passed without a vote. */
    public int streakNow(Periods periods, long now) {
        return periods.day(now) - lastDay <= 1 ? streak : 0;
    }

    /** When they last voted on a site, or 0. */
    public long lastOn(String service) {
        return lastBySite.getOrDefault(service.toLowerCase(java.util.Locale.ROOT), 0L);
    }
}
