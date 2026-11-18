package co.xenastudios.xbackup.server;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Which worlds a backup turned autosave off on, and whether it still wants them off. The
 * heart of {@link WorldPause}, kept apart from Bukkit so the races can be tested.
 *
 * <p>The pause is asked for on one thread and carried out on another (the main thread on
 * Paper), and the end can come from a third (the watchdog, or disable). So: the worlds are
 * only turned off if the pause is still wanted, checked under the same lock that ends it;
 * the worlds actually turned off are remembered, and the end always turns exactly those back
 * on, however many times it's called and whatever order things happen in. One lock for all
 * gates, so one backup's end can't land in the middle of the next one's start.
 *
 * @param <W> a world
 */
final class AutosaveGate<W> {

    /** Reads and sets a world's autosave. Setting it is a plain field write, safe from any thread. */
    interface Switch<W> {
        boolean isOn(W world);

        void set(W world, boolean on);
    }

    private static final Object LOCK = new Object();

    private final Switch<W> worlds;
    private boolean wanted;
    private final Set<W> off = new LinkedHashSet<>();

    AutosaveGate(Switch<W> worlds) {
        this.worlds = worlds;
    }

    /** A pause is wanted from now until {@link #close}. */
    void open() {
        synchronized (LOCK) {
            wanted = true;
        }
    }

    /**
     * Turns autosave off on each of these worlds that has it on, if the pause is still
     * wanted. Worlds whose owner turned autosave off already are left alone.
     *
     * @return the worlds this pause holds off, to flush; null if the pause already ended
     */
    List<W> turnOff(Iterable<W> candidates) {
        synchronized (LOCK) {
            if (!wanted) {
                return null;
            }
            for (W world : candidates) {
                if (worlds.isOn(world)) {
                    worlds.set(world, false);
                    off.add(world);
                }
            }
            return new ArrayList<>(off);
        }
    }

    /** Whether the pause is still wanted. */
    boolean wanted() {
        synchronized (LOCK) {
            return wanted;
        }
    }

    /** Ends the pause: autosave back on for every world it turned off. Any thread, any number of times. */
    void close() {
        synchronized (LOCK) {
            wanted = false;
            for (W world : off) {
                try {
                    worlds.set(world, true);
                } catch (RuntimeException e) {
                    // An unloaded world: nothing left to save.
                }
            }
            off.clear();
        }
    }

    /** The worlds held off right now. */
    List<W> held() {
        synchronized (LOCK) {
            return new ArrayList<>(off);
        }
    }
}
