package co.xenastudios.xrewind.rollback;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.plugin.Plugin;

/**
 * Sets a plan's blocks without freezing the server: chunk by chunk, each loaded first
 * without waiting, a few milliseconds per tick. Blocks are set without physics, so
 * nothing cascades. What each block was before is kept, for undo.
 *
 * <p>On Paper one task works through the chunks in order, with the next few loading
 * ahead. On Folia each chunk is worked on its own region's thread, a few at a time.
 */
public final class Applier {

    /** Chunks loading ahead (Paper), or worked at once (Folia). */
    private static final int AHEAD = 8;

    /** How it went. */
    public record Result(int applied, int missingWorld, List<Plan.Change> previous, long nanos) {}

    private record Group(World world, int cx, int cz, List<Plan.Change> changes) {}

    private final Plugin plugin;
    private final boolean folia;
    private final long budgetNanos;
    private final Consumer<Result> done;
    private final List<Group> groups = new ArrayList<>();
    private final AtomicInteger applied = new AtomicInteger();
    private final ConcurrentLinkedQueue<Plan.Change> previous = new ConcurrentLinkedQueue<>();
    private final long start = System.nanoTime();
    private int missingWorld;
    private final java.util.concurrent.atomic.AtomicBoolean finished = new java.util.concurrent.atomic.AtomicBoolean();
    private final int total;

    public Applier(Plugin plugin, boolean folia, int msPerTick, Plan plan, Consumer<Result> done) {
        this.plugin = plugin;
        this.folia = folia;
        this.budgetNanos = TimeUnit.MILLISECONDS.toNanos(msPerTick);
        this.done = done;
        this.total = plan.changes().size();
        Map<String, Group> byChunk = new LinkedHashMap<>();
        for (Plan.Change change : plan.changes()) {
            World world = Bukkit.getWorld(change.world());
            if (world == null) {
                missingWorld++;
                continue;
            }
            int cx = change.x() >> 4;
            int cz = change.z() >> 4;
            byChunk.computeIfAbsent(change.world() + ':' + cx + ':' + cz, key -> new Group(world, cx, cz, new ArrayList<>()))
                .changes().add(change);
        }
        groups.addAll(byChunk.values());
    }

    /** Blocks set so far, of how many. Safe from any thread. */
    public int applied() {
        return applied.get();
    }

    public int total() {
        return total;
    }

    public void start() {
        if (groups.isEmpty()) {
            finish();
        } else if (folia) {
            new FoliaRun().start();
        } else {
            new PaperRun().start();
        }
    }

    // Something broke: say so, and finish with what was done, so the next rollback isn't blocked.
    private void failed(Throwable e) {
        plugin.getLogger().log(java.util.logging.Level.SEVERE, "A rollback stopped partway after " + applied.get() + " blocks", e);
        finish();
    }

    private void finish() {
        if (!finished.compareAndSet(false, true)) {
            return;
        }
        done.accept(new Result(applied.get(), missingWorld, List.copyOf(previous), System.nanoTime() - start));
    }

    // Sets blocks from index on until the deadline. Returns where it stopped.
    private int apply(Group group, int index, long deadline) {
        List<Plan.Change> changes = group.changes();
        while (index < changes.size()) {
            Plan.Change change = changes.get(index++);
            Block block = group.world().getBlockAt(change.x(), change.y(), change.z());
            BlockData before = block.getBlockData();
            if (!before.equals(change.target())) {
                block.setBlockData(change.target(), false);
            }
            previous.add(new Plan.Change(change.world(), change.x(), change.y(), change.z(), before));
            applied.incrementAndGet();
            if ((index & 63) == 0 && System.nanoTime() > deadline) {
                break;
            }
        }
        return index;
    }

    /** Paper: everything is the main thread, so one task per tick works through the chunks. */
    private final class PaperRun {
        private final Iterator<Group> next = groups.iterator();
        private final java.util.ArrayDeque<Map.Entry<Group, CompletableFuture<Chunk>>> loading = new java.util.ArrayDeque<>();
        private int index;
        private final java.util.Set<Group> ticketed = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

        void start() {
            try {
                fill();
            } catch (RuntimeException e) {
                failed(e);
                return;
            }
            Bukkit.getGlobalRegionScheduler().run(plugin, task -> tick());
        }

        private void fill() {
            while (loading.size() < AHEAD && next.hasNext()) {
                Group group = next.next();
                World world = group.world();
                // A loaded chunk needs no trip through the chunk system, nor a ticket to stay
                // loaded while it's worked on: something else is keeping it.
                if (world.isChunkLoaded(group.cx(), group.cz())) {
                    loading.add(Map.entry(group, CompletableFuture.completedFuture(world.getChunkAt(group.cx(), group.cz()))));
                } else {
                    loading.add(Map.entry(group, world.getChunkAtAsync(group.cx(), group.cz())));
                    ticketed.add(group);
                }
            }
        }

        private void tick() {
            try {
                step();
            } catch (RuntimeException | LinkageError e) {
                failed(e);
            }
        }

        private void step() {
            long deadline = System.nanoTime() + budgetNanos;
            while (!loading.isEmpty() && System.nanoTime() < deadline) {
                Map.Entry<Group, CompletableFuture<Chunk>> head = loading.peek();
                if (!head.getValue().isDone()) {
                    break; // Still loading: next tick.
                }
                Group group = head.getKey();
                Chunk chunk = head.getValue().join();
                if (index == 0 && ticketed.contains(group)) {
                    chunk.addPluginChunkTicket(plugin);
                }
                index = apply(group, index, deadline);
                if (index >= group.changes().size()) {
                    if (ticketed.remove(group)) {
                        chunk.removePluginChunkTicket(plugin);
                    }
                    loading.poll();
                    index = 0;
                    fill();
                }
            }
            if (loading.isEmpty()) {
                finish();
            } else {
                Bukkit.getGlobalRegionScheduler().runDelayed(plugin, task -> tick(), 1);
            }
        }
    }

    /** Folia: each chunk on its own region's thread, a few at a time. */
    private final class FoliaRun {
        private final Iterator<Group> next = groups.iterator();
        private final AtomicInteger running = new AtomicInteger();

        void start() {
            for (int i = 0; i < AHEAD; i++) {
                launch();
            }
        }

        private synchronized void launch() {
            if (!next.hasNext()) {
                if (running.get() == 0) {
                    finish();
                }
                return;
            }
            Group group = next.next();
            running.incrementAndGet();
            group.world().getChunkAtAsync(group.cx(), group.cz()).thenAccept(chunk ->
                Bukkit.getRegionScheduler().execute(plugin, group.world(), group.cx(), group.cz(), () -> {
                    chunk.addPluginChunkTicket(plugin);
                    work(group, chunk, 0);
                }));
        }

        private void work(Group group, Chunk chunk, int from) {
            int index;
            try {
                index = apply(group, from, System.nanoTime() + budgetNanos);
            } catch (RuntimeException | LinkageError e) {
                failed(e);
                return;
            }
            if (index < group.changes().size()) {
                Bukkit.getRegionScheduler().runDelayed(plugin, group.world(), group.cx(), group.cz(),
                    task -> work(group, chunk, index), 1);
                return;
            }
            chunk.removePluginChunkTicket(plugin);
            running.decrementAndGet();
            launch();
        }
    }
}
