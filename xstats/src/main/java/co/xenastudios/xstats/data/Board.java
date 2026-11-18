package co.xenastudios.xstats.data;

import co.xenastudios.xstats.api.LeaderboardEntry;
import co.xenastudios.xstats.api.Period;
import co.xenastudios.xstats.stat.StatDef;
import java.util.ArrayList;
import java.util.List;

/**
 * One leaderboard, worked out at a save: every player with something for its stat and
 * period, best first, and who's left out. Never changed once made, so any thread reads it.
 *
 * @param key the period's day, week or month it was worked out for
 */
public record Board(StatDef stat, Period period, int key, PlayerRecord[] order, long[] values) {

    public int size() {
        return order.length;
    }

    /** The place of a value: one more than how many are above it, so ties share a place. */
    public int rankOf(long value) {
        if (value <= 0) {
            return 0;
        }
        // values is best first: find the first index whose value isn't above this one.
        int low = 0;
        int high = values.length;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (values[mid] > value) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low + 1;
    }

    /** The place shown beside entry {@code i}: the place of the first entry with its value. */
    public int rankAt(int i) {
        return rankOf(values[i]);
    }

    /** The first {@code count} places, skipping players {@code skipHidden} says to (those private, for the website). */
    public List<LeaderboardEntry> top(int count, boolean skipHidden) {
        List<LeaderboardEntry> entries = new ArrayList<>(Math.min(count, order.length));
        for (int i = 0; i < order.length && entries.size() < count; i++) {
            PlayerRecord player = order[i];
            if (skipHidden && player.hidden()) {
                continue;
            }
            entries.add(new LeaderboardEntry(rankAt(i), player.id, player.name(), values[i]));
        }
        return entries;
    }

    /** A page of places, from {@code from} (0-based). */
    public List<LeaderboardEntry> slice(int from, int count) {
        List<LeaderboardEntry> entries = new ArrayList<>(count);
        for (int i = from; i < order.length && i < from + count; i++) {
            entries.add(new LeaderboardEntry(rankAt(i), order[i].id, order[i].name(), values[i]));
        }
        return entries;
    }

    public PlayerRecord leader() {
        return order.length == 0 ? null : order[0];
    }
}
