package co.xenastudios.xteleport.teleport;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * When each player may use each command again, in memory. Kept through a quit, so leaving
 * and rejoining doesn't skip a cooldown; entries that ran out are dropped as players leave.
 */
public final class Cooldowns {

    private final Map<UUID, Map<String, Long>> until = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public Cooldowns() {
        this(System::currentTimeMillis);
    }

    Cooldowns(LongSupplier clock) {
        this.clock = clock;
    }

    /** Time left on a cooldown, or zero. Any thread. */
    public Duration remaining(UUID player, String key) {
        Map<String, Long> keys = until.get(player);
        Long end = keys == null ? null : keys.get(key);
        long left = end == null ? 0 : end - clock.getAsLong();
        return left > 0 ? Duration.ofMillis(left) : Duration.ZERO;
    }

    /** Starts a cooldown. Nothing for zero seconds or a blank key. */
    public void start(UUID player, String key, int seconds) {
        if (seconds > 0 && !key.isEmpty()) {
            until.computeIfAbsent(player, id -> new ConcurrentHashMap<>()).put(key, clock.getAsLong() + seconds * 1000L);
        }
    }

    /** Drops every cooldown that ran out, as players leave, so the map can't grow forever. */
    public void prune() {
        long now = clock.getAsLong();
        until.values().forEach(keys -> keys.values().removeIf(end -> end <= now));
        until.values().removeIf(Map::isEmpty);
    }

    /** How many players have a cooldown, for tests. */
    int players() {
        return until.size();
    }
}
