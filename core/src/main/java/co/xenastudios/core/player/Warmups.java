package co.xenastudios.core.player;

import co.xenastudios.core.text.Message;
import co.xenastudios.core.text.Placeholders;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.plugin.Plugin;

/**
 * Something that happens after standing still for a few seconds, such as a teleport:
 * moving half a block (or, if asked, taking damage) cancels it, and the seconds left can
 * count down on the action bar.
 *
 * <p>Each waiting player is checked four times a second by a short task on their own
 * thread (Paper's entity scheduler, so it's Folia-safe), which ends with the warmup. A
 * damage listener is registered only while a warmup that damage cancels is running.
 */
public final class Warmups {

    /** Why a warmup stopped without finishing. */
    public enum Cancel {
        /** Moved half a block, or to another world. */
        MOVED,
        /** Took damage, for a warmup {@link Warmup#cancelOnDamage}. */
        DAMAGED,
        /** Another warmup started for them. */
        REPLACED,
        /** {@link #cancel} was called. */
        CANCELLED,
        /** Logged out, or the entity was removed. */
        GONE
    }

    /**
     * A warmup: how long, and what then.
     *
     * @param seconds how long to stand still
     * @param then what happens after, on the entity's thread
     * @param cancelled what happens if it stops early, on the entity's thread, except
     *     {@link Cancel#GONE}, which may come on another thread
     * @param countdown shown on the action bar each second, with {@code <seconds>} left;
     *     empty for none
     * @param cancelOnDamage whether taking damage cancels it
     */
    public record Warmup<E extends Entity>(int seconds, Consumer<? super E> then, BiConsumer<? super E, Cancel> cancelled,
                                           Message countdown, boolean cancelOnDamage) {

        /** A warmup of {@code seconds} that runs {@code then}, which moving cancels and nothing more. */
        public static <E extends Entity> Warmup<E> of(int seconds, Consumer<? super E> then) {
            return new Warmup<>(seconds, then, (entity, reason) -> {}, Message.empty(), false);
        }

        public Warmup<E> onCancel(BiConsumer<? super E, Cancel> cancelled) {
            return new Warmup<>(seconds, then, cancelled, countdown, cancelOnDamage);
        }

        public Warmup<E> countdown(Message countdown) {
            return new Warmup<>(seconds, then, cancelled, countdown, cancelOnDamage);
        }

        public Warmup<E> cancelOnDamage(boolean cancelOnDamage) {
            return new Warmup<>(seconds, then, cancelled, countdown, cancelOnDamage);
        }
    }

    /** Checks per second. */
    static final int CHECKS = 4;
    /** How far, squared, counts as moving: half a block. */
    static final double MOVED = 0.25;

    private final Plugin plugin;
    private final Map<UUID, Running<?>> running = new ConcurrentHashMap<>();
    private final Listener damage = new Listener() {};
    private int damageWatched; // Guarded by this.

    public Warmups(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Starts a warmup, replacing any the entity already has. Call it on the entity's
     * thread (the main thread on Paper). A warmup of 0 seconds or less runs at once.
     */
    public <E extends Entity> void start(E entity, Warmup<E> warmup) {
        Running<?> before = running.get(entity.getUniqueId());
        if (before != null) {
            before.stop(Cancel.REPLACED);
        }
        if (warmup.seconds() <= 0) {
            warmup.then().accept(entity);
            return;
        }
        Running<E> now = new Running<>(entity, warmup, entity.getLocation(), warmup.seconds() * CHECKS);
        running.put(entity.getUniqueId(), now);
        if (warmup.cancelOnDamage()) {
            watchDamage(true);
        }
        now.show(warmup.seconds());
        ScheduledTask task = entity.getScheduler().runAtFixedRate(plugin, t -> now.check(), () -> now.stop(Cancel.GONE),
            20 / CHECKS, 20 / CHECKS);
        if (task == null) {
            now.stop(Cancel.GONE);
        } else {
            now.task = task;
            if (running.get(entity.getUniqueId()) != now) {
                task.cancel(); // Stopped while the task was being scheduled.
            }
        }
    }

    /** Whether the entity has a warmup running. Any thread. */
    public boolean waiting(UUID entity) {
        return running.containsKey(entity);
    }

    /** Cancels the entity's warmup, if it has one, telling it {@link Cancel#CANCELLED}. */
    public void cancel(UUID entity) {
        Running<?> warmup = running.get(entity);
        if (warmup != null) {
            warmup.stop(Cancel.CANCELLED);
        }
    }

    /** Stops every warmup without telling anyone, as when the plugin is disabled. */
    public void clear() {
        for (Running<?> warmup : List.copyOf(running.values())) {
            warmup.end();
        }
    }

    /** Whether the damage listener is registered, for tests. */
    public synchronized boolean listening() {
        return damageWatched > 0;
    }

    private synchronized void watchDamage(boolean watch) {
        damageWatched += watch ? 1 : -1;
        if (watch && damageWatched == 1) {
            Bukkit.getPluginManager().registerEvent(EntityDamageEvent.class, damage, EventPriority.MONITOR,
                (listener, event) -> damaged(event), plugin, true);
        } else if (!watch && damageWatched == 0) {
            HandlerList.unregisterAll(damage);
        }
    }

    private void damaged(Event event) {
        if (event instanceof EntityDamageEvent hurt) {
            Running<?> warmup = running.get(hurt.getEntity().getUniqueId());
            if (warmup != null && warmup.warmup.cancelOnDamage()) {
                warmup.stop(Cancel.DAMAGED);
            }
        }
    }

    private final class Running<E extends Entity> {

        final E entity;
        final Warmup<E> warmup;
        final Location start;
        int checks;
        volatile ScheduledTask task;

        Running(E entity, Warmup<E> warmup, Location start, int checks) {
            this.entity = entity;
            this.warmup = warmup;
            this.start = start;
            this.checks = checks;
        }

        /** On the entity's thread, four times a second. */
        void check() {
            Location now = entity.getLocation();
            if (now.getWorld() != start.getWorld() || now.distanceSquared(start) > MOVED) {
                stop(Cancel.MOVED);
                return;
            }
            if (--checks <= 0) {
                if (end()) {
                    warmup.then().accept(entity);
                }
            } else if (checks % CHECKS == 0) {
                show(checks / CHECKS);
            }
        }

        void show(int seconds) {
            if (!warmup.countdown().isEmpty()) {
                entity.sendActionBar(warmup.countdown().render(Placeholders.text("seconds", seconds)));
            }
        }

        void stop(Cancel reason) {
            if (end()) {
                warmup.cancelled().accept(entity, reason);
            }
        }

        /** Takes it out of the running; true for whoever did, so it ends once. */
        boolean end() {
            if (!running.remove(entity.getUniqueId(), this)) {
                return false;
            }
            ScheduledTask scheduled = task;
            if (scheduled != null) {
                scheduled.cancel();
            }
            if (warmup.cancelOnDamage()) {
                watchDamage(false);
            }
            if (!warmup.countdown().isEmpty()) {
                entity.sendActionBar(Component.empty());
            }
            return true;
        }
    }
}
