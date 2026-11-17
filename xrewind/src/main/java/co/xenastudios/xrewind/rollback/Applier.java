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
import co.xenastudios.xrewind.log.Contents;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.inventory.ItemStack;
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
    private volatile boolean aborted;
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

    // Something broke: say so, and stop everything else, so the next rollback isn't blocked
    // and nothing changes after it's reported finished. Paper finishes at once (one thread);
    // Folia once every chunk in flight has stopped.
    private void failed(Throwable e) {
        if (!aborted) {
            aborted = true;
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "A rollback stopped partway after " + applied.get() + " blocks", e);
        }
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
        while (index < changes.size() && !aborted) {
            Plan.Change change = changes.get(index++);
            Block block = group.world().getBlockAt(change.x(), change.y(), change.z());
            if (change.isItems()) {
                items(block, change);
            } else {
                BlockData before = block.getBlockData();
                Contents held = Contents.canHold(before.getMaterial()) ? Contents.of(block) : null;
                if (!before.equals(change.target())) {
                    if (held != null) {
                        // Set without physics, a container would spill what it held: empty it first.
                        Contents.clear(block);
                    }
                    block.setBlockData(change.target(), false);
                }
                if (change.contents() != null) {
                    Contents.restore(block, change.contents());
                }
                previous.add(Plan.Change.block(change.world(), change.x(), change.y(), change.z(), before, held));
            }
            applied.incrementAndGet();
            if ((index & 63) == 0 && System.nanoTime() > deadline) {
                break;
            }
        }
        return index;
    }

    // Adds or takes items; undo does the opposite with what really moved.
    private void items(Block block, Plan.Change change) {
        if (!(block.getState(false) instanceof Container container)) {
            return;
        }
        ItemStack stack = change.item().clone();
        int amount = Math.abs(change.delta());
        stack.setAmount(amount);
        Map<Integer, ItemStack> left = change.delta() > 0 ? container.getInventory().addItem(stack) : container.getInventory().removeItem(stack);
        int missed = 0;
        for (ItemStack rest : left.values()) {
            missed += rest.getAmount();
        }
        int moved = amount - missed;
        if (moved > 0) {
            previous.add(Plan.Change.items(change.world(), change.x(), change.y(), change.z(), change.item(),
                change.delta() > 0 ? -moved : moved));
        }
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
                finish();
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
                release();
                finish();
            }
        }

        // Lets go of every chunk it was keeping loaded.
        private void release() {
            for (Map.Entry<Group, CompletableFuture<Chunk>> waiting : loading) {
                if (ticketed.remove(waiting.getKey()) && waiting.getValue().isDone() && !waiting.getValue().isCompletedExceptionally()) {
                    waiting.getValue().join().removePluginChunkTicket(plugin);
                }
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
                if (head.getValue().isCompletedExceptionally()) {
                    // Couldn't be loaded: leave its blocks and go on.
                    ticketed.remove(group);
                    loading.poll();
                    index = 0;
                    fill();
                    continue;
                }
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
            if (aborted || !next.hasNext()) {
                if (running.get() == 0) {
                    finish();
                }
                return;
            }
            Group group = next.next();
            running.incrementAndGet();
            // whenComplete, not thenAccept: a chunk that fails to load still counts as done.
            group.world().getChunkAtAsync(group.cx(), group.cz()).whenComplete((chunk, error) -> {
                if (error != null || chunk == null) {
                    done();
                    return;
                }
                Bukkit.getRegionScheduler().execute(plugin, group.world(), group.cx(), group.cz(), () -> {
                    chunk.addPluginChunkTicket(plugin);
                    work(group, chunk, 0);
                });
            });
        }

        private void work(Group group, Chunk chunk, int from) {
            int index = from;
            try {
                index = apply(group, from, System.nanoTime() + budgetNanos);
            } catch (RuntimeException | LinkageError e) {
                failed(e);
            }
            if (index < group.changes().size() && !aborted) {
                int at = index;
                Bukkit.getRegionScheduler().runDelayed(plugin, group.world(), group.cx(), group.cz(),
                    task -> work(group, chunk, at), 1);
                return;
            }
            chunk.removePluginChunkTicket(plugin);
            done();
        }

        private void done() {
            running.decrementAndGet();
            launch();
        }
    }
}
