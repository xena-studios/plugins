package co.xenastudios.xvote.vote;

import co.xenastudios.xvote.api.Period;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Every voter's totals, in memory, and the leaderboards made from them. Safe from any
 * thread: voters are swapped whole, and a leaderboard is a list that's never changed.
 *
 * <p>A leaderboard is sorted the first time it's asked for after a vote, or after its
 * period rolls over, and kept until the next: votes are rare next to the scoreboards and
 * menus asking, so a vote costs one sort, not one per question.
 */
public final class Voters {

    /** A place on a leaderboard. */
    public record Ranked(int rank, Voter voter, int votes) {}

    private record Board(int periodKey, long version, List<Ranked> ranked) {}

    private final Map<UUID, Voter> byId = new ConcurrentHashMap<>();
    private final Map<String, UUID> byName = new ConcurrentHashMap<>();
    private final Map<Period, Board> boards = new ConcurrentHashMap<>();
    private final AtomicLong version = new AtomicLong();
    private volatile Periods periods;

    public Voters(Periods periods) {
        this.periods = periods;
    }

    public Periods periods() {
        return periods;
    }

    /** New period settings (a reload). Totals stay as read; leaderboards are sorted again. */
    public void periods(Periods periods) {
        this.periods = periods;
        version.incrementAndGet();
    }

    public void putAll(Map<UUID, Voter> voters) {
        voters.values().forEach(this::put);
    }

    /**
     * A voter as just read. One that's unchanged (a join with no new votes, read again)
     * leaves the leaderboards as they are, so they aren't sorted again for nothing.
     */
    public void put(Voter voter) {
        Voter before = byId.put(voter.id(), voter);
        if (voter.equals(before)) {
            return;
        }
        if (before != null && !before.name().equalsIgnoreCase(voter.name())) {
            byName.remove(before.name().toLowerCase(Locale.ROOT), voter.id());
        }
        byName.put(voter.name().toLowerCase(Locale.ROOT), voter.id());
        version.incrementAndGet();
    }

    /** Bumped whenever a leaderboard needs sorting again, for tests. */
    long version() {
        return version.get();
    }

    public Voter get(UUID id) {
        return byId.get(id);
    }

    /** A player who has voted, by the name they voted with, ignoring case. */
    public UUID byName(String name) {
        return byName.get(name.toLowerCase(Locale.ROOT));
    }

    public int size() {
        return byId.size();
    }

    /** A player's votes in a period, as of now. */
    public int votes(Voter voter, Period period, long now) {
        Periods current = periods;
        return switch (period) {
            case DAY -> voter.today(current, now);
            case WEEK -> voter.week(current, now);
            case MONTH -> voter.month(current, now);
            case ALL -> voter.total();
        };
    }

    /** Everyone with a vote in the period, most first; ties go to whoever got there first. */
    public List<Ranked> top(Period period, long now) {
        Periods current = periods;
        int key = switch (period) {
            case DAY -> current.dayKey(now);
            case WEEK -> current.weekKey(now);
            case MONTH -> current.monthKey(now);
            case ALL -> 0;
        };
        long seen = version.get();
        Board board = boards.get(period);
        if (board != null && board.periodKey() == key && board.version() == seen) {
            return board.ranked();
        }
        List<Ranked> unsorted = new ArrayList<>();
        for (Voter voter : byId.values()) {
            int votes = votes(voter, period, now);
            if (votes > 0) {
                unsorted.add(new Ranked(0, voter, votes));
            }
        }
        unsorted.sort(Comparator.comparingInt(Ranked::votes).reversed()
            .thenComparingLong(ranked -> ranked.voter().lastVote())
            .thenComparing(ranked -> ranked.voter().name(), String.CASE_INSENSITIVE_ORDER));
        List<Ranked> ranked = new ArrayList<>(unsorted.size());
        for (int i = 0; i < unsorted.size(); i++) {
            ranked.add(new Ranked(i + 1, unsorted.get(i).voter(), unsorted.get(i).votes()));
        }
        List<Ranked> result = List.copyOf(ranked);
        boards.put(period, new Board(key, seen, result));
        return result;
    }
}
