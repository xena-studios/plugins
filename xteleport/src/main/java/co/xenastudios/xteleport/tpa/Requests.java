package co.xenastudios.xteleport.tpa;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Pending teleport requests, by who they're for. A player can have several at once, one
 * from each sender. Nothing runs on a timer: a request older than the expiry is dropped
 * whenever the list it's in is read or added to, so a request that runs out costs nothing
 * until someone looks. A player's list goes when they leave.
 *
 * <p>Each list is replaced whole on every change, so reading one never sees it half done.
 * Safe from any thread.
 */
public final class Requests {

    /**
     * One request.
     *
     * @param here true for {@code /tpahere}: the target goes to the sender
     */
    public record Request(UUID from, String fromName, boolean here, long sentAt) {}

    private final Map<UUID, List<Request>> inbox = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public Requests() {
        this(System::currentTimeMillis);
    }

    Requests(LongSupplier clock) {
        this.clock = clock;
    }

    public long now() {
        return clock.getAsLong();
    }

    private static List<Request> fresh(List<Request> list, long oldest) {
        List<Request> kept = new ArrayList<>(list.size());
        for (Request request : list) {
            if (request.sentAt() >= oldest) {
                kept.add(request);
            }
        }
        return kept;
    }

    /**
     * Adds a request, unless the sender already has one pending with this target: asking
     * again doesn't notify them again, so nobody can be spammed.
     *
     * @return false if there was already one, which is kept as it was
     */
    public boolean add(UUID to, Request request, long expireMillis) {
        long oldest = clock.getAsLong() - expireMillis;
        boolean[] added = new boolean[1];
        inbox.compute(to, (id, list) -> {
            List<Request> kept = list == null ? new ArrayList<>() : fresh(list, oldest);
            if (kept.stream().noneMatch(old -> old.from().equals(request.from()))) {
                kept.add(request);
                added[0] = true;
            }
            return kept.isEmpty() ? null : List.copyOf(kept);
        });
        return added[0];
    }

    /** Whether the sender has a request to this player still pending. */
    public boolean asked(UUID to, UUID from, long expireMillis) {
        return pending(to, expireMillis).stream().anyMatch(request -> request.from().equals(from));
    }

    /** A player's pending requests, oldest first, dropping the ones that ran out. */
    public List<Request> pending(UUID to, long expireMillis) {
        long oldest = clock.getAsLong() - expireMillis;
        List<Request> now = inbox.computeIfPresent(to, (id, list) -> {
            List<Request> kept = fresh(list, oldest);
            return kept.isEmpty() ? null : List.copyOf(kept);
        });
        return now == null ? List.of() : now;
    }

    /**
     * Takes a request out: the one from {@code from}, or the newest if {@code from} is null.
     *
     * @return the request, or empty if there's no such request still pending
     */
    public Optional<Request> take(UUID to, UUID from, long expireMillis) {
        List<Request> pending = pending(to, expireMillis);
        Request chosen = null;
        for (Request request : pending) {
            if (from == null || request.from().equals(from)) {
                chosen = request; // The last match is the newest.
            }
        }
        if (chosen == null) {
            return Optional.empty();
        }
        Request taken = chosen;
        inbox.computeIfPresent(to, (id, list) -> {
            List<Request> kept = new ArrayList<>(list);
            kept.remove(taken);
            return kept.isEmpty() ? null : List.copyOf(kept);
        });
        return Optional.of(taken);
    }

    /**
     * Takes back requests a player sent: to {@code to}, or to everyone if it's null.
     *
     * @return who the requests were for
     */
    public List<UUID> cancel(UUID from, UUID to, long expireMillis) {
        long oldest = clock.getAsLong() - expireMillis;
        List<UUID> targets = new ArrayList<>();
        for (UUID target : List.copyOf(inbox.keySet())) {
            if (to != null && !to.equals(target)) {
                continue;
            }
            inbox.computeIfPresent(target, (id, list) -> {
                List<Request> kept = fresh(list, oldest);
                if (kept.removeIf(request -> request.from().equals(from))) {
                    targets.add(target);
                }
                return kept.isEmpty() ? null : List.copyOf(kept);
            });
        }
        return targets;
    }

    /** Forgets requests for a player who left. Ones they sent are dropped when read. */
    public void forget(UUID player) {
        inbox.remove(player);
    }

    /** Forgets every request. */
    public void clear() {
        inbox.clear();
    }

    /** How many players have a list, for tests. */
    int lists() {
        return inbox.size();
    }
}
