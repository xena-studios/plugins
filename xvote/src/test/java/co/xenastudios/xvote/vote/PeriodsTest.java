package co.xenastudios.xvote.vote;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.DayOfWeek;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PeriodsTest {

    static long at(String when, String zone) {
        return ZonedDateTime.parse(when + "[" + zone + "]").toInstant().toEpochMilli();
    }

    @Test
    void periodsStartAtMidnightInTheOwnersZone() {
        Periods london = new Periods(ZoneId.of("Europe/London"), DayOfWeek.MONDAY);
        // 23:30 on Sunday 29 November in New York is already Monday in London.
        long late = at("2026-11-29T23:30-05:00", "America/New_York");
        assertEquals(at("2026-11-30T00:00Z", "Europe/London"), london.dayStart(late));
        assertEquals(at("2026-11-30T00:00Z", "Europe/London"), london.weekStart(late));
        assertEquals(at("2026-11-01T00:00Z", "Europe/London"), london.monthStart(late));
        Periods newYork = new Periods(ZoneId.of("America/New_York"), DayOfWeek.SUNDAY);
        assertEquals(at("2026-11-29T00:00-05:00", "America/New_York"), newYork.weekStart(late));
    }

    @Test
    void aDayWhenClocksChangeIsStillOneDay() {
        Periods london = new Periods(ZoneId.of("Europe/London"), DayOfWeek.MONDAY);
        long morning = at("2026-10-25T09:00Z", "Europe/London");
        assertEquals(25 * 3_600_000L, london.dayEnd(morning) - london.dayStart(morning));
    }

    @Test
    void keysChangeWhenANewPeriodStarts() {
        Periods utc = new Periods(ZoneId.of("UTC"), DayOfWeek.MONDAY);
        long monday = at("2026-11-16T10:00Z", "UTC");
        long sunday = at("2026-11-22T23:59Z", "UTC");
        assertEquals(utc.weekKey(monday), utc.weekKey(sunday));
        assertEquals(utc.weekKey(monday) + 7, utc.weekKey(sunday + 60_000));
        assertEquals(utc.monthKey(monday), utc.monthKey(at("2026-11-30T23:59Z", "UTC")));
        assertEquals(utc.monthKey(monday) + 1, utc.monthKey(at("2026-12-01T00:00Z", "UTC")));
    }

    @Test
    void streaks() {
        assertEquals(1, Periods.nextStreak(0, 0, 100), "a first vote");
        assertEquals(3, Periods.nextStreak(100, 3, 100), "the same day keeps it");
        assertEquals(4, Periods.nextStreak(100, 3, 101), "the next day adds one");
        assertEquals(1, Periods.nextStreak(100, 3, 102), "a missed day starts again");
        assertEquals(3, Periods.nextStreak(100, 3, 98), "a vote counted late doesn't change it");
    }

    @Test
    void aBadZoneFallsBackToTheServers() {
        List<String> warnings = new ArrayList<>();
        assertEquals(ZoneId.systemDefault(), Periods.zone("Mars/Olympus", warnings::add));
        assertEquals(1, warnings.size());
        assertEquals(ZoneId.of("Europe/Paris"), Periods.zone("Europe/Paris", warnings::add));
        assertEquals(ZoneId.systemDefault(), Periods.zone("server", warnings::add));
    }
}
