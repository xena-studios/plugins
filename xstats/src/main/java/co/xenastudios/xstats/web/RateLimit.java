package co.xenastudios.xstats.web;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * At most so many requests a minute from one visitor: a count per address per minute.
 *
 * <p>Memory is bounded without ever forgetting a visitor mid-minute: past
 * {@link #MAX_TRACKED} addresses, those from earlier minutes are dropped (at most once a
 * minute), and while it's still full, addresses not seen yet this minute share one count.
 * A flood of made-up addresses then limits only itself, and resets nobody else's count.
 */
final class RateLimit {

    static final int MAX_TRACKED = 10_000;
    /** The count addresses share while the map is full. */
    static final String OVERFLOW = "*";

    private record Window(long minute, int count) {}

    private final int perMinute;
    private final int maxTracked;
    private final LongSupplier clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final AtomicLong swept = new AtomicLong(-1);

    RateLimit(int perMinute, LongSupplier clock) {
        this(perMinute, MAX_TRACKED, clock);
    }

    RateLimit(int perMinute, int maxTracked, LongSupplier clock) {
        this.perMinute = perMinute;
        this.maxTracked = maxTracked;
        this.clock = clock;
    }

    /** Counts a request, and says whether it's allowed. */
    boolean allow(String client) {
        long minute = clock.getAsLong() / 60_000;
        if (windows.size() >= maxTracked && swept.getAndSet(minute) != minute) {
            windows.values().removeIf(window -> window.minute() != minute);
        }
        String key = windows.size() >= maxTracked && !windows.containsKey(client) ? OVERFLOW : client;
        Window window = windows.compute(key, (k, old) ->
            old == null || old.minute() != minute ? new Window(minute, 1) : new Window(minute, old.count() + 1));
        return window.count() <= perMinute;
    }

    int tracked() {
        return windows.size();
    }

    /** Seconds until the minute is up, for Retry-After. */
    long retryAfter() {
        return 60 - (clock.getAsLong() / 1000) % 60;
    }
}
