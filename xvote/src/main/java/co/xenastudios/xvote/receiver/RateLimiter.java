package co.xenastudios.xvote.receiver;

import java.net.InetAddress;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Connections allowed per address per minute. A list sends one connection per vote, so
 * even a busy one stays far below the limit; a flood from one address is turned away
 * before anything is read or decrypted.
 */
final class RateLimiter {

    static final long WINDOW_MS = 60_000;
    /** Addresses kept before old ones are swept out. */
    static final int SWEEP_AT = 10_000;

    private final int perMinute;
    private final ConcurrentHashMap<InetAddress, Window> windows = new ConcurrentHashMap<>();

    RateLimiter(int perMinute) {
        this.perMinute = perMinute;
    }

    private static final class Window {
        long start;
        int count;
    }

    /** Counts a connection, and says whether it's within the limit. */
    boolean allow(InetAddress address, long now) {
        if (windows.size() > SWEEP_AT) {
            windows.values().removeIf(window -> {
                synchronized (window) {
                    return now - window.start >= WINDOW_MS;
                }
            });
        }
        Window window = windows.computeIfAbsent(address, key -> new Window());
        synchronized (window) {
            if (now - window.start >= WINDOW_MS) {
                window.start = now;
                window.count = 0;
            }
            return ++window.count <= perMinute;
        }
    }
}
