package co.xenastudios.xstats.web;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * At most so many requests a minute from one visitor: a count per address per minute.
 * Memory is bounded: past 10,000 addresses, those from earlier minutes are forgotten.
 */
final class RateLimit {

    static final int MAX_TRACKED = 10_000;

    private record Window(long minute, int count) {}

    private final int perMinute;
    private final LongSupplier clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    RateLimit(int perMinute, LongSupplier clock) {
        this.perMinute = perMinute;
        this.clock = clock;
    }

    /** Counts a request, and says whether it's allowed. */
    boolean allow(String client) {
        long minute = clock.getAsLong() / 60_000;
        if (windows.size() > MAX_TRACKED) {
            windows.values().removeIf(window -> window.minute() != minute);
            if (windows.size() > MAX_TRACKED) {
                windows.clear();
            }
        }
        Window window = windows.compute(client, (key, old) ->
            old == null || old.minute() != minute ? new Window(minute, 1) : new Window(minute, old.count() + 1));
        return window.count() <= perMinute;
    }

    /** Seconds until the minute is up, for Retry-After. */
    long retryAfter() {
        return 60 - (clock.getAsLong() / 1000) % 60;
    }
}
