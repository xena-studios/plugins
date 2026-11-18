package co.xenastudios.xvote.vote;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;

/**
 * Where days, weeks and months start, in the owner's time zone. Each period has a key (a
 * number that changes when a new one starts), so a total kept with its key is known to be
 * stale once the key moves on, and no task has to reset totals at midnight.
 */
public final class Periods {

    private final ZoneId zone;
    private final DayOfWeek weekStart;

    public Periods(ZoneId zone, DayOfWeek weekStart) {
        this.zone = zone;
        this.weekStart = weekStart;
    }

    /** {@code server} or an id like Europe/London; the server's zone if it isn't one, with the reason. */
    public static ZoneId zone(String text, java.util.function.Consumer<String> warn) {
        if (text == null || text.isBlank() || text.equalsIgnoreCase("server")) {
            return ZoneId.systemDefault();
        }
        try {
            return ZoneId.of(text.strip());
        } catch (DateTimeException e) {
            warn.accept("counting.time-zone (\"" + text + "\") isn't a time zone, such as Europe/London; using the server's.");
            return ZoneId.systemDefault();
        }
    }

    public ZoneId zone() {
        return zone;
    }

    /** Whether days and weeks start at the same moments under both. */
    public boolean sameAs(Periods other) {
        return zone.equals(other.zone) && weekStart == other.weekStart;
    }

    /** The day number (days since 1970) of a moment, in this zone. */
    public int day(long millis) {
        return (int) date(millis).toEpochDay();
    }

    /** The day's start, in epoch milliseconds. */
    public long dayStart(long millis) {
        return start(date(millis));
    }

    /** The next day's start: not always 24 hours on, where clocks change. */
    public long dayEnd(long millis) {
        return start(date(millis).plusDays(1));
    }

    public long weekStart(long millis) {
        return start(date(millis).with(TemporalAdjusters.previousOrSame(weekStart)));
    }

    public long monthStart(long millis) {
        return start(date(millis).withDayOfMonth(1));
    }

    /** Keys that change when a new day, week or month starts. */
    public int dayKey(long millis) {
        return day(millis);
    }

    public int weekKey(long millis) {
        return (int) date(millis).with(TemporalAdjusters.previousOrSame(weekStart)).toEpochDay();
    }

    public int monthKey(long millis) {
        LocalDate date = date(millis);
        return date.getYear() * 12 + date.getMonthValue() - 1;
    }

    private LocalDate date(long millis) {
        return Instant.ofEpochMilli(millis).atZone(zone).toLocalDate();
    }

    private long start(LocalDate date) {
        return date.atStartOfDay(zone).toInstant().toEpochMilli();
    }

    /**
     * A streak after a vote on {@code day}: the same day keeps it, the next day adds one,
     * a later day starts again at 1. A vote counted late for an earlier day (a name matched
     * after the fact) leaves it as it is.
     */
    public static int nextStreak(int lastDay, int streak, int day) {
        if (streak <= 0) {
            return 1;
        }
        if (day <= lastDay) {
            return streak;
        }
        return day == lastDay + 1 ? streak + 1 : 1;
    }
}
