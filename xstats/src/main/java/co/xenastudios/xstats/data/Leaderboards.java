package co.xenastudios.xstats.data;

import co.xenastudios.xstats.api.Period;
import co.xenastudios.xstats.stat.StatDef;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Every leaderboard, worked out during a save and swapped in whole, so placeholders,
 * {@code /top} and the website read them from memory and never ask the database.
 *
 * <p>Only boards whose stat changed in a save, or whose day, week or month just turned
 * over, are worked out again; the rest are kept as they were.
 */
public final class Leaderboards {

    private record Entry(PlayerRecord player, long value) {}

    private static final Comparator<Entry> BEST_FIRST = Comparator.comparingLong(Entry::value).reversed()
        .thenComparing(entry -> entry.player().name() == null ? "" : entry.player().name(), String.CASE_INSENSITIVE_ORDER);

    private volatile Map<String, Board> boards = Map.of();
    private volatile Map<Integer, Long> totals = Map.of();
    private volatile Set<UUID> leftOut = Set.of();

    static String key(String stat, Period period) {
        return stat + ":" + period.id();
    }

    /** A board, or null if there's none. Any thread. */
    public Board get(String stat, Period period) {
        return boards.get(key(stat, period));
    }

    public Collection<Board> all() {
        return boards.values();
    }

    /** Every player's all-time value of a stat added up, by stat index, as of the last save. */
    public long total(int index) {
        return totals.getOrDefault(index, 0L);
    }

    /**
     * Works out boards again. Call on xStats' thread.
     *
     * @param changed indexes of stats whose values changed since the last time
     * @param everything work out every board, as when who's left out changed
     * @param leftOut players never on a board: banned, or with the exclude permission
     * @param newLeader told of each board with a new leader, with the one before (null if none)
     */
    public void compute(Collection<PlayerRecord> players, List<StatDef> stats, Calendar calendar, int today,
                        java.util.BitSet changed, boolean everything, Set<UUID> leftOut,
                        BiConsumer<Board, PlayerRecord> newLeader) {
        Map<String, Board> old = boards;
        boolean exclusionsChanged = !leftOut.equals(this.leftOut);
        Map<String, Board> next = new HashMap<>();
        Map<Integer, Long> sums = new HashMap<>(totals);
        for (StatDef stat : stats) {
            List<Period> periods = stat.periods() ? List.of(Period.values()) : List.of(Period.ALL);
            for (Period period : periods) {
                int key = calendar.key(period, today);
                Board before = old.get(key(stat.id(), period));
                boolean fresh = everything || exclusionsChanged || before == null || before.key() != key
                    || changed.get(stat.index()) || before.stat() != stat;
                if (!fresh) {
                    next.put(key(stat.id(), period), before);
                    continue;
                }
                Board board = build(players, stat, period, calendar, today, key, leftOut, sums);
                next.put(key(stat.id(), period), board);
                PlayerRecord leader = board.leader();
                PlayerRecord previous = before == null ? null : before.leader();
                // A new leader on the same board: not a new day emptying it, nor the first boards after a start.
                if (before != null && before.key() == key && leader != null && (previous == null || !previous.id.equals(leader.id))) {
                    newLeader.accept(board, previous);
                }
            }
        }
        this.leftOut = Set.copyOf(leftOut);
        totals = Map.copyOf(sums);
        boards = Map.copyOf(next);
    }

    private static Board build(Collection<PlayerRecord> players, StatDef stat, Period period, Calendar calendar, int today,
                               int key, Set<UUID> leftOut, Map<Integer, Long> sums) {
        List<Entry> entries = new ArrayList<>();
        long sum = 0;
        for (PlayerRecord player : players) {
            long value = player.value(stat.index(), period, calendar, today);
            if (period == Period.ALL) {
                sum += value;
            }
            if (value > 0 && player.name() != null && !player.excluded() && !leftOut.contains(player.id)) {
                entries.add(new Entry(player, value));
            }
        }
        if (period == Period.ALL) {
            sums.put(stat.index(), sum);
        }
        Entry[] sorted = entries.toArray(Entry[]::new);
        Arrays.sort(sorted, BEST_FIRST);
        PlayerRecord[] order = new PlayerRecord[sorted.length];
        long[] values = new long[sorted.length];
        for (int i = 0; i < sorted.length; i++) {
            order[i] = sorted[i].player();
            values[i] = sorted[i].value();
        }
        return new Board(stat, period, key, order, values);
    }
}
