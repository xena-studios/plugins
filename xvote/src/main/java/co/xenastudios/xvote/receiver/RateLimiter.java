package co.xenastudios.xvote.receiver;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Connections allowed per address per minute. A list sends one connection per vote, so
 * even a busy one stays far below the limit; a flood from one address is turned away
 * before anything is read or decrypted. 0 turns the limit off.
 *
 * <p>IPv6 addresses are counted by their /64, the block one machine usually gets, so a
 * host can't pick a new address for every connection.
 */
final class RateLimiter {

    static final long WINDOW_MS = 60_000;
    /** Addresses kept before old ones are swept out. */
    static final int SWEEP_AT = 10_000;
    /** Sweeps at most this often, so a flood from many addresses doesn't sweep on every connection. */
    static final long SWEEP_EVERY_MS = 1_000;

    private final int perMinute;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final AtomicLong lastSweep = new AtomicLong();

    RateLimiter(int perMinute) {
        this.perMinute = perMinute;
    }

    private static final class Window {
        long start;
        int count;
    }

    /** What an address is counted as: itself for IPv4, its /64 for IPv6. */
    static String key(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (address instanceof Inet6Address && bytes.length == 16) {
            return HexFormat.of().formatHex(bytes, 0, 8) + "/64";
        }
        return address.getHostAddress();
    }

    /** Counts a connection, and says whether it's within the limit. */
    boolean allow(String key, long now) {
        if (perMinute <= 0) {
            return true;
        }
        if (windows.size() > SWEEP_AT) {
            long last = lastSweep.get();
            if (now - last >= SWEEP_EVERY_MS && lastSweep.compareAndSet(last, now)) {
                windows.values().removeIf(window -> {
                    synchronized (window) {
                        return now - window.start >= WINDOW_MS;
                    }
                });
            }
        }
        Window window = windows.computeIfAbsent(key, unused -> new Window());
        synchronized (window) {
            if (now - window.start >= WINDOW_MS) {
                window.start = now;
                window.count = 0;
            }
            return ++window.count <= perMinute;
        }
    }

    /** Addresses being counted, for tests. */
    int size() {
        return windows.size();
    }
}
