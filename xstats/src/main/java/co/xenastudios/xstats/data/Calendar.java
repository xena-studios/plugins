package co.xenastudios.xstats.data;

import co.xenastudios.xstats.api.Period;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;

/**
 * Days, weeks and months as numbers, in the config's time zone: a day is its epoch day, a
 * week the epoch day it starts on, a month {@code year * 12 + month - 1}. Daily rows in the
 * database are keyed by day, monthly ones by month.
 */
public record Calendar(ZoneId zone, DayOfWeek weekStarts) {

    public int today(long millis) {
        return (int) LocalDate.ofInstant(java.time.Instant.ofEpochMilli(millis), zone).toEpochDay();
    }

    public static int week(int day, DayOfWeek weekStarts) {
        return (int) LocalDate.ofEpochDay(day).with(TemporalAdjusters.previousOrSame(weekStarts)).toEpochDay();
    }

    public int week(int day) {
        return week(day, weekStarts);
    }

    public static int month(int day) {
        LocalDate date = LocalDate.ofEpochDay(day);
        return date.getYear() * 12 + date.getMonthValue() - 1;
    }

    /** The first day of a month number. */
    public static int firstDay(int month) {
        return (int) LocalDate.of(month / 12, month % 12 + 1, 1).toEpochDay();
    }

    /** The key a period's bucket has on a day: the day, its week or its month. */
    public int key(Period period, int day) {
        return switch (period) {
            case DAILY -> day;
            case WEEKLY -> week(day);
            case MONTHLY -> month(day);
            case ALL -> 0;
        };
    }

    /** The earliest day the current week or month needs, for loading them from daily rows. */
    public int earliestNeeded(int day) {
        return Math.min(week(day), firstDay(month(day)));
    }
}
