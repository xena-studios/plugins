package co.xenastudios.xstats.data;

import co.xenastudios.xstats.api.Period;
import java.util.Arrays;
import java.util.UUID;

/**
 * Everything xStats keeps in memory for one player. Values are indexed by
 * {@link co.xenastudios.xstats.stat.StatDef#index()}.
 *
 * <p>Only xStats' own thread changes it. Other threads (placeholders, commands, the web
 * server) read it without locking: a read can be one save behind, never wrong in kind,
 * since a long is written in one go and arrays only grow by being replaced.
 */
public final class PlayerRecord {

    private static final long[] NONE = new long[0];

    /**
     * A player's name: Java names, and Bedrock players' through Floodgate, which start with
     * its prefix (a dot unless the owner changed it), such as {@code .Steve}.
     */
    public static final java.util.regex.Pattern NAME = java.util.regex.Pattern.compile("[\\w.*+~-]{1,16}");

    public final UUID id;
    /** The row id in the players table, or 0 until the first save stores them. */
    volatile int dbId;
    volatile String name;
    volatile boolean hidden;
    volatile boolean excluded;
    volatile long firstJoin;
    volatile long lastSeen;
    /** All-time values: this server's, or the network's in network mode. */
    volatile long[] values = NONE;
    volatile Bucket day = new Bucket(-1, NONE);
    volatile Bucket week = new Bucket(-1, NONE);
    volatile Bucket month = new Bucket(-1, NONE);

    // xStats' thread only.
    /** This server's own values (its row in the totals table), and the last vanilla reading of each. */
    long[] local;
    long[] raw;
    boolean localLoaded;
    /** What changed since the last save: rows to write. */
    boolean rowChanged;
    final java.util.BitSet changed = new java.util.BitSet();
    long[] pendingDaily = NONE;
    // AFK-aware playtime.
    float yaw;
    float pitch;
    boolean looked;
    long lastSample;
    /** When their values here were last read or set: a network's latest value is the one read last. */
    long lastRead;
    /** /stats private couldn't be saved at once: the next save writes it. */
    boolean hidePending;
    volatile boolean online;
    long playMillis;

    public PlayerRecord(UUID id, String name) {
        this.id = id;
        this.name = name;
    }

    public String name() {
        return name;
    }

    public int dbId() {
        return dbId;
    }

    public boolean hidden() {
        return hidden;
    }

    public boolean excluded() {
        return excluded;
    }

    public long firstJoin() {
        return firstJoin;
    }

    public long lastSeen() {
        return lastSeen;
    }

    /** Whether they were online at the last save. */
    public boolean online() {
        return online;
    }

    /** All-time value, or 0. Any thread. */
    public long value(int index) {
        long[] all = values;
        return index < all.length ? all[index] : 0;
    }

    /** A period's value, or 0 if they have none this period. Any thread. */
    public long value(int index, Period period, Calendar calendar, int today) {
        return switch (period) {
            case ALL -> value(index);
            case DAILY -> day.get(calendar.key(period, today), index);
            case WEEKLY -> week.get(calendar.key(period, today), index);
            case MONTHLY -> month.get(calendar.key(period, today), index);
        };
    }

    // ---- xStats' thread ----

    void ensureWidth(int width) {
        if (values.length < width) {
            values = Arrays.copyOf(values, width);
        }
        if (local != null && local.length < width) {
            local = Arrays.copyOf(local, width);
            long[] grown = Arrays.copyOf(raw, width);
            Arrays.fill(grown, raw.length, width, -1);
            raw = grown;
        }
        if (pendingDaily.length < width) {
            pendingDaily = Arrays.copyOf(pendingDaily, width);
        }
    }

    /** Starts this server's values: none read yet. */
    void startLocal(int width) {
        local = new long[width];
        raw = new long[width];
        Arrays.fill(raw, -1);
        localLoaded = true;
    }

    /** Adds to a period's bucket, starting a new one when the day, week or month changed. */
    void addPeriod(Period period, int key, int index, long delta, int width) {
        Bucket bucket = switch (period) {
            case DAILY -> day;
            case WEEKLY -> week;
            case MONTHLY -> month;
            case ALL -> throw new IllegalArgumentException();
        };
        long[] values = bucket.key() == key ? bucket.values() : new long[width];
        if (values.length <= index) {
            values = Arrays.copyOf(values, width);
        }
        values[index] += delta;
        if (bucket.key() != key || bucket.values() != values) {
            Bucket next = new Bucket(key, values);
            switch (period) {
                case DAILY -> day = next;
                case WEEKLY -> week = next;
                case MONTHLY -> month = next;
                default -> { }
            }
        }
    }

    void setBucket(Period period, Bucket bucket) {
        switch (period) {
            case DAILY -> day = bucket;
            case WEEKLY -> week = bucket;
            case MONTHLY -> month = bucket;
            default -> throw new IllegalArgumentException();
        }
    }
}
