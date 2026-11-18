package co.xenastudios.xstats.data;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Events waiting for the next save. Adding is lock-free and safe from any thread.
 *
 * <ul>
 *   <li>Other plugins' changes are added up as they come: a thousand increments of one
 *       stat for one player between two saves wait as one, and several sets in a row wait
 *       as their first and last value and the rises between. A set and an increment of the
 *       same stat keep their order, and so do changes and a player's join or reading: once
 *       one of those is waiting, later changes wait behind it, never added into one ahead.
 *       (Another plugin's join listener can add to a stat before xStats hears of the join.)</li>
 *   <li>Each player's events are applied in the order they happened. If one can't be (their
 *       rows couldn't be read), it and every later one of theirs wait for the next save, in
 *       order; everyone else's carry on.</li>
 *   <li>Past {@link #WARN_AT} waiting, the console is told; past the limit, new events are
 *       dropped and counted rather than run the server out of memory.</li>
 * </ul>
 */
final class Inbox {

    static final int WARN_AT = 100_000;
    /** A few hundred MB at most: readings carry a number per stat. */
    static final int LIMIT = 1_000_000;

    /** Applies one event. On xStats' thread. */
    interface Apply {
        void apply(Event event) throws SQLException;
    }

    private sealed interface Slot permits Fixed, Pending {}

    private record Fixed(Event event) implements Slot {}

    /**
     * Increments or sets of one stat for one player, still being added to until a save takes
     * them. Its fields change only while {@link #tails} holds the player's entry locked.
     */
    private static final class Pending implements Slot {
        final UUID player;
        final String stat;
        final boolean set;
        /** The first value set (sets only). */
        final long first;
        long value;
        /** How much later sets went up from the ones before (sets only). */
        long rise;

        Pending(UUID player, String stat, boolean set, long value) {
            this.player = player;
            this.stat = stat;
            this.set = set;
            this.first = value;
            this.value = value;
        }
    }

    private final Logger logger;
    private final int limit;
    private final int warnAt;
    private final ConcurrentLinkedQueue<Slot> queue = new ConcurrentLinkedQueue<>();
    /**
     * The last waiting increment or set of each player's stats, while more can be added to
     * it, by player then stat. Everything about one player happens inside a {@code compute}
     * of their entry, so a join and an increment can't pass each other.
     */
    private final Map<UUID, Map<String, Pending>> tails = new ConcurrentHashMap<>();
    /** Waiting, held back and not yet applied. */
    private final AtomicInteger size = new AtomicInteger();
    private final AtomicLong dropped = new AtomicLong();
    // xStats' thread only: players whose events couldn't be applied, and theirs since, in order.
    private final Map<UUID, ArrayDeque<Event>> held = new LinkedHashMap<>();

    Inbox(Logger logger) {
        this(logger, LIMIT, WARN_AT);
    }

    Inbox(Logger logger, int limit, int warnAt) {
        this.logger = logger;
        this.limit = limit;
        this.warnAt = warnAt;
    }

    /** Events waiting, including any held back. */
    int size() {
        return size.get();
    }

    /** Events dropped because too many were waiting. */
    long dropped() {
        return dropped.get();
    }

    /** Players whose events are held back after an error. On xStats' thread. */
    int held() {
        return held.size();
    }

    void add(Event event) {
        switch (event) {
            case Event.Increment increment -> merge(increment.player(), increment.stat(), false, increment.amount());
            case Event.Set set when set.first() == set.value() && set.rise() == 0 ->
                merge(set.player(), set.stat(), true, set.value());
            default -> tails.compute(event.player(), (player, waiting) -> {
                // Queued while holding the player: changes after this wait behind it, in new slots.
                enqueue(new Fixed(event));
                return null;
            });
        }
    }

    private void merge(UUID player, String stat, boolean set, long value) {
        tails.compute(player, (id, waiting) -> {
            Map<String, Pending> stats = waiting == null ? new HashMap<>() : waiting;
            Pending tail = stats.get(stat);
            if (tail != null && tail.set == set) {
                // Atomic for this player: compute holds them, and a save takes the tail out the same way.
                if (set) {
                    tail.rise = saturatedAdd(tail.rise, Math.max(0, value - tail.value));
                    tail.value = value;
                } else {
                    tail.value = saturatedAdd(tail.value, value);
                }
                return stats;
            }
            Pending next = new Pending(id, stat, set, value);
            if (enqueue(next)) {
                stats.put(stat, next);
            }
            return stats.isEmpty() ? null : stats;
        });
    }

    private static long saturatedAdd(long a, long b) {
        long sum = a + b;
        return ((a ^ sum) & (b ^ sum)) < 0 ? Long.MAX_VALUE : sum;
    }

    private boolean enqueue(Slot slot) {
        int waiting = size.incrementAndGet();
        if (waiting > limit) {
            size.decrementAndGet();
            if (dropped.getAndIncrement() == 0) {
                logger.severe(limit + " stat changes are waiting to be saved, so new ones are being dropped."
                    + " Check the database; /xstats status shows how many were missed.");
            }
            return false;
        }
        queue.add(slot);
        if (waiting == warnAt) {
            logger.warning(warnAt + " stat changes are waiting to be saved: the database is slow or can't be reached.");
        }
        return true;
    }

    /** The event a slot stands for, closing a pending one to further adds. */
    private Event take(Slot slot) {
        return switch (slot) {
            case Fixed fixed -> fixed.event();
            case Pending pending -> {
                Event[] closed = new Event[1];
                // Read under the player's lock, once no more can be added to it.
                tails.compute(pending.player, (id, stats) -> {
                    if (stats != null && stats.get(pending.stat) == pending) {
                        stats.remove(pending.stat);
                    }
                    closed[0] = pending.set ? new Event.Set(id, pending.stat, pending.first, pending.value, pending.rise)
                        : new Event.Increment(id, pending.stat, pending.value);
                    return stats == null || stats.isEmpty() ? null : stats;
                });
                yield closed[0];
            }
        };
    }

    /**
     * Applies every waiting event, those held back from the last save first. On xStats' thread.
     *
     * @return the first error, or null if every event was applied
     */
    SQLException drain(Apply apply) {
        SQLException failed = null;
        Iterator<Map.Entry<UUID, ArrayDeque<Event>>> players = held.entrySet().iterator();
        while (players.hasNext()) {
            ArrayDeque<Event> waiting = players.next().getValue();
            try {
                while (!waiting.isEmpty()) {
                    apply.apply(waiting.peekFirst());
                    waiting.pollFirst();
                    size.decrementAndGet();
                }
                players.remove();
            } catch (SQLException e) {
                failed = failed == null ? e : failed;
            }
        }
        Slot slot;
        while ((slot = queue.poll()) != null) {
            Event event = take(slot);
            ArrayDeque<Event> waiting = held.get(event.player());
            if (waiting != null) {
                // Behind one of theirs that couldn't be applied: after it, never before.
                waiting.addLast(event);
                continue;
            }
            try {
                apply.apply(event);
                size.decrementAndGet();
            } catch (SQLException e) {
                held.computeIfAbsent(event.player(), id -> new ArrayDeque<>()).addLast(event);
                failed = failed == null ? e : failed;
            }
        }
        return failed;
    }
}
