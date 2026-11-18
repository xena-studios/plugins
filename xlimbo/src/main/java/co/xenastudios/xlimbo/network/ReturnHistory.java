package co.xenastudios.xlimbo.network;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * When each player arrived and when they were last sent back. Kept by the plugin, not
 * the return queue, so it outlives the queue being restarted on reload: changing
 * server-check doesn't give a player the main server keeps refusing three more tries.
 * Main thread only.
 */
public final class ReturnHistory {

    /** Automatic tries per player in {@link #TRIES_WINDOW_MILLIS}, so a player the main server keeps refusing isn't bounced forever. */
    static final int MAX_TRIES = 3;
    static final long TRIES_WINDOW_MILLIS = 300_000;
    /** A player sent back who's still here this long after has been bounced back. */
    static final long BOUNCE_MILLIS = 15_000;

    private final Map<UUID, Long> arrived = new HashMap<>();
    // Kept when a player leaves: being sent back is leaving, and a refused player comes back.
    private final Map<UUID, Deque<Long>> tries = new HashMap<>();

    public void arrived(UUID player, long now) {
        arrived.put(player, now);
    }

    /** Records an arrival for a player already here that nothing has recorded yet. */
    void arrivedIfUnknown(UUID player, long now) {
        arrived.putIfAbsent(player, now);
    }

    /** How long the player has been here, or 0 if their arrival wasn't seen. */
    long here(UUID player, long now) {
        return now - arrived.getOrDefault(player, now);
    }

    public void left(UUID player) {
        arrived.remove(player);
    }

    /**
     * Records a player sent back while the return queue is off (by /join or the API), so
     * coming back moments later still counts as refused. Prunes too: without the queue,
     * nothing else does.
     */
    public void sent(UUID player, long now) {
        prune(now);
        tried(player, now);
    }

    void tried(UUID player, long now) {
        tries.computeIfAbsent(player, key -> new ArrayDeque<>()).addLast(now);
    }

    /** Whether the player was sent back moments ago, so being here again means the main server refused them. */
    public boolean bounced(UUID player, long now) {
        return recentlySent(player, now);
    }

    boolean recentlySent(UUID player, long now) {
        Deque<Long> times = tries.get(player);
        return times != null && !times.isEmpty() && now - times.peekLast() < BOUNCE_MILLIS;
    }

    boolean triedTooOften(UUID player, long now) {
        Deque<Long> times = tries.get(player);
        if (times == null) {
            return false;
        }
        int recent = 0;
        for (long time : times) {
            if (now - time <= TRIES_WINDOW_MILLIS) {
                recent++;
            }
        }
        return recent >= MAX_TRIES;
    }

    /** Forgets tries older than the window, and players with none left. */
    void prune(long now) {
        tries.values().removeIf(times -> {
            while (!times.isEmpty() && now - times.peekFirst() > TRIES_WINDOW_MILLIS) {
                times.pollFirst();
            }
            return times.isEmpty();
        });
    }
}
