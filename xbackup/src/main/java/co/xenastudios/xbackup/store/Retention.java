package co.xenastudios.xbackup.store;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Which snapshots to keep: the newest few, then the newest of each of the last so many
 * hours, days, weeks and months. "24 hourly, 7 daily" keeps a backup for each of the last
 * 24 hours that have one, and for each of the last 7 days that have one; a snapshot can
 * count for several. The newest snapshot and pinned ones are always kept.
 *
 * @param last the newest this many, whatever their age
 */
public record Retention(int last, int hourly, int daily, int weekly, int monthly) {

    /** The snapshots to keep, by id. */
    public Set<Hash> keep(List<Snapshot> snapshots, Set<Hash> pinned, ZoneId zone) {
        List<Snapshot> newestFirst = new ArrayList<>(snapshots);
        newestFirst.sort(Comparator.comparing(Snapshot::time).reversed());
        Set<Hash> keep = new HashSet<>();
        if (newestFirst.isEmpty()) {
            return keep;
        }
        keep.add(newestFirst.get(0).id());
        for (Snapshot snapshot : newestFirst) {
            if (pinned.contains(snapshot.id())) {
                keep.add(snapshot.id());
            }
        }
        for (int i = 0; i < Math.min(last, newestFirst.size()); i++) {
            keep.add(newestFirst.get(i).id());
        }
        bucket(newestFirst, hourly, keep, s -> at(s, zone).truncatedTo(ChronoUnit.HOURS));
        bucket(newestFirst, daily, keep, s -> at(s, zone).truncatedTo(ChronoUnit.DAYS));
        bucket(newestFirst, weekly, keep, s -> {
            ZonedDateTime time = at(s, zone);
            return time.get(IsoFields.WEEK_BASED_YEAR) * 100L + time.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        });
        bucket(newestFirst, monthly, keep, s -> at(s, zone).truncatedTo(ChronoUnit.DAYS)
            .with(TemporalAdjusters.firstDayOfMonth()));
        return keep;
    }

    private static ZonedDateTime at(Snapshot snapshot, ZoneId zone) {
        return snapshot.time().atZone(zone);
    }

    // Keeps the newest snapshot in each of the newest `count` buckets that have one.
    private static void bucket(List<Snapshot> newestFirst, int count, Set<Hash> keep, Function<Snapshot, Object> key) {
        Object last = null;
        int kept = 0;
        for (Snapshot snapshot : newestFirst) {
            if (kept >= count) {
                return;
            }
            Object bucket = key.apply(snapshot);
            if (!bucket.equals(last)) {
                keep.add(snapshot.id());
                last = bucket;
                kept++;
            }
        }
    }
}
