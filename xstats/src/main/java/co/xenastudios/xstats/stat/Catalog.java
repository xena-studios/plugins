package co.xenastudios.xstats.stat;

import co.xenastudios.xstats.api.Period;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every stat xStats keeps right now: the config's, then other plugins'. Replaced whole on
 * reload or registration, so readers on any thread see one consistent set.
 *
 * <p>Indexes are handed out by id and never reused while the server runs: a stat dropped
 * on reload keeps its slot, so it can come back with its numbers, and other stats never move.
 */
public final class Catalog {

    private final Map<String, Integer> indexes = new ConcurrentHashMap<>();
    private volatile Snapshot current = new Snapshot(List.of(), Map.of(), Map.of());

    /**
     * One set of stats, with lookups for placeholders: {@code kills}, {@code kills_daily}.
     *
     * @param byParam stat and period by the text placeholders write them as
     */
    public record Snapshot(List<StatDef> stats, Map<String, StatDef> byId, Map<String, StatPeriod> byParam) {

        public Optional<StatDef> get(String id) {
            return Optional.ofNullable(byId.get(id));
        }

        /** The highest index in use, plus one: how long players' arrays must be. */
        public int width() {
            int width = 0;
            for (StatDef stat : stats) {
                width = Math.max(width, stat.index() + 1);
            }
            return width;
        }
    }

    /** A stat and a period, as placeholders and commands name them together. */
    public record StatPeriod(StatDef stat, Period period) {}

    /** The index for an id: the same one every time while the server runs. */
    public synchronized int index(String id) {
        return indexes.computeIfAbsent(id, key -> indexes.size());
    }

    public Snapshot snapshot() {
        return current;
    }

    /** Swaps in a new set. Ids must be unique. */
    public void set(List<StatDef> stats) {
        Map<String, StatDef> byId = new LinkedHashMap<>();
        Map<String, StatPeriod> byParam = new HashMap<>();
        for (StatDef stat : stats) {
            byId.put(stat.id(), stat);
            byParam.put(stat.id(), new StatPeriod(stat, Period.ALL));
            byParam.put(stat.id() + "_all", new StatPeriod(stat, Period.ALL));
            if (stat.periods()) {
                for (Period period : List.of(Period.DAILY, Period.WEEKLY, Period.MONTHLY)) {
                    byParam.put(stat.id() + "_" + period.id(), new StatPeriod(stat, period));
                }
            }
        }
        current = new Snapshot(Collections.unmodifiableList(new ArrayList<>(stats)), Map.copyOf(byId), Map.copyOf(byParam));
    }
}
