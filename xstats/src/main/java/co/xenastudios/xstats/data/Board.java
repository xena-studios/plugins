package co.xenastudios.xstats.data;

import co.xenastudios.xstats.api.LeaderboardEntry;
import co.xenastudios.xstats.api.Period;
import co.xenastudios.xstats.stat.StatDef;
import java.util.ArrayList;
import java.util.List;

/**
 * One leaderboard, worked out at a save: every player with something for its stat and
 * period, best first, and who's left out. Its places never change once made, so any
 * thread reads it.
 */
public final class Board {

    /** How many private players are above each place, for the website's places, as of one hidden version. */
    private record Hidden(int version, int[] above) {}

    private final StatDef stat;
    private final Period period;
    private final int key;
    private final PlayerRecord[] order;
    private final long[] values;
    private volatile Hidden hidden;

    /** @param key the period's day, week or month it was worked out for */
    public Board(StatDef stat, Period period, int key, PlayerRecord[] order, long[] values) {
        this.stat = stat;
        this.period = period;
        this.key = key;
        this.order = order;
        this.values = values;
    }

    public StatDef stat() {
        return stat;
    }

    public Period period() {
        return period;
    }

    public int key() {
        return key;
    }

    public PlayerRecord[] order() {
        return order;
    }

    public long[] values() {
        return values;
    }

    public int size() {
        return order.length;
    }

    /** The place of a value: one more than how many are above it, so ties share a place. */
    public int rankOf(long value) {
        if (value <= 0) {
            return 0;
        }
        return above(value) + 1;
    }

    /** How many places have a value above this one: values is best first. */
    private int above(long value) {
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
        return low;
    }

    /**
     * The place of a value among players the website shows: private players above it don't
     * count, so a place never gives away that someone private is there.
     *
     * @param version the engine's hidden version, so a hide since the board was made counts
     */
    public int visibleRankOf(long value, int version) {
        if (value <= 0) {
            return 0;
        }
        int above = above(value);
        return above - hiddenAbove(version)[above] + 1;
    }

    /** {@code [i]} is how many private players are in the first {@code i} places. Made once per hide. */
    private int[] hiddenAbove(int version) {
        Hidden known = hidden;
        if (known != null && known.version() == version) {
            return known.above();
        }
        int[] above = new int[order.length + 1];
        for (int i = 0; i < order.length; i++) {
            above[i + 1] = above[i] + (order[i].hidden() ? 1 : 0);
        }
        hidden = new Hidden(version, above);
        return above;
    }

    /** The place shown beside entry {@code i}: the place of the first entry with its value. */
    public int rankAt(int i) {
        return rankOf(values[i]);
    }

    /**
     * The first {@code count} places. With {@code skipHidden} (for the website), private
     * players are left out and places are counted among those shown.
     */
    public List<LeaderboardEntry> top(int count, boolean skipHidden) {
        List<LeaderboardEntry> entries = new ArrayList<>(Math.min(count, order.length));
        int rank = 0;
        long last = Long.MIN_VALUE;
        for (int i = 0; i < order.length && entries.size() < count; i++) {
            PlayerRecord player = order[i];
            if (skipHidden && player.hidden()) {
                continue;
            }
            if (!skipHidden) {
                rank = rankAt(i);
            } else if (values[i] != last) {
                // Ties share a place; the next value's place is one more than how many are shown above it.
                rank = entries.size() + 1;
                last = values[i];
            }
            entries.add(new LeaderboardEntry(rank, player.id, player.name(), values[i]));
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
