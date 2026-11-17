package co.xenastudios.xrewind.storage;

/**
 * Times as xRewind stores them: whole seconds since the start of 2026, UTC. A signed
 * 32-bit number of those reaches back to 1958 and forward to 2094, so a time costs four
 * bytes, not eight, on every row.
 */
public final class Clock {

    /** 2026-01-01T00:00:00Z in Unix seconds. */
    static final long EPOCH = 1_767_225_600L;

    private Clock() {}

    /** Now, as stored. */
    public static int now() {
        return fromMillis(System.currentTimeMillis());
    }

    public static int fromMillis(long unixMillis) {
        return Math.toIntExact(Math.floorDiv(unixMillis, 1000) - EPOCH);
    }

    /** Unix seconds, as stored. */
    public static int fromUnixSeconds(long seconds) {
        return Math.toIntExact(seconds - EPOCH);
    }

    public static long toMillis(int stored) {
        return (stored + EPOCH) * 1000;
    }
}
