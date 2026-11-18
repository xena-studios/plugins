package co.xenastudios.xplots.reset;

import co.xenastudios.xplots.model.Box;
import co.xenastudios.xplots.model.Places;
import co.xenastudios.xplots.model.Plot;
import co.xenastudios.xplots.storage.Store;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Minecart;
import org.bukkit.plugin.Plugin;

/**
 * Plot snapshots: saved when a plot is made, and put back when its rent ends. Both run on
 * the {@link Ticker}, a few milliseconds per tick, loading chunks in the background first.
 * A plot being put back can't be built in or rented until it's done. Main thread.
 */
public final class Resets {

    private final Plugin plugin;
    private final Logger logger;
    private final Store store;
    private final Ticker ticker;
    private final Set<Plot> busy = new HashSet<>();

    public Resets(Plugin plugin, Store store, Ticker ticker) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.store = store;
        this.ticker = ticker;
    }

    /** Whether the plot is being put back, so nobody may build in or rent it yet. */
    public boolean isBusy(Plot plot) {
        return busy.contains(plot);
    }

    /** Saves each plot's blocks as they are now, as what it's put back to. */
    public void capture(Collection<Plot> plots, Runnable then) {
        ticker.add(new Capture(new ArrayDeque<>(plots), then));
    }

    /**
     * Puts the plot back as it was saved, then runs {@code then}. A plot with no snapshot
     * is left as it is.
     */
    public void reset(Plot plot, Runnable then) {
        if (!busy.add(plot)) {
            return;
        }
        store.loadSnapshot(plot, data -> {
            Snapshot snapshot = null;
            if (data != null) {
                try {
                    snapshot = Snapshot.decode(data);
                } catch (IOException e) {
                    logger.severe("Couldn't read " + plot.id() + "'s saved blocks (" + e.getMessage() + "), so it was left as it is.");
                }
            }
            Snapshot loaded = snapshot;
            main(() -> {
                World world = Bukkit.getWorld(plot.field().world());
                if (loaded == null || world == null || !matches(loaded, plot.box())) {
                    if (loaded != null && world != null) {
                        logger.warning(plot.id() + "'s saved blocks are a different size from the plot, so it was left as it is.");
                    }
                    busy.remove(plot);
                    then.run();
                    return;
                }
                ticker.add(new Restore(world, plot, loaded, then));
            });
        });
    }

    private static boolean matches(Snapshot snapshot, Box box) {
        return snapshot.width() == box.width() && snapshot.height() == box.height() && snapshot.depth() == box.depth();
    }

    private void main(Runnable work) {
        if (plugin.isEnabled()) {
            try {
                Bukkit.getScheduler().runTask(plugin, work);
            } catch (RuntimeException ignored) {
                // Stopping: the scheduler no longer takes xPlots' tasks.
            }
        }
    }

    /** Asks for each of the box's chunks that isn't loaded. True once they all are. */
    static boolean loaded(World world, Box box) {
        boolean all = true;
        for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++) {
            for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
                if (!world.isChunkLoaded(cx, cz)) {
                    world.getChunkAtAsync(cx, cz);
                    all = false;
                }
            }
        }
        return all;
    }

    private final class Capture implements Ticker.Job {
        private final Deque<Plot> plots;
        private final Runnable then;

        Capture(Deque<Plot> plots, Runnable then) {
            this.plots = plots;
            this.then = then;
        }

        @Override
        public boolean step(long deadline) {
            while (!plots.isEmpty()) {
                Plot plot = plots.peek();
                World world = Bukkit.getWorld(plot.field().world());
                if (world == null) {
                    plots.poll();
                    continue;
                }
                if (!loaded(world, plot.box())) {
                    return false;
                }
                store.saveSnapshot(plot, Snapshot.capture(world, plot.box()).encode());
                plots.poll();
                if (System.nanoTime() > deadline) {
                    return plots.isEmpty() && finish();
                }
            }
            return finish();
        }

        private boolean finish() {
            then.run();
            return true;
        }
    }

    private final class Restore implements Ticker.Job {
        private final World world;
        private final Plot plot;
        private final Snapshot snapshot;
        private final BlockData[] states;
        private final boolean[] tiles;
        private final Runnable then;
        private boolean cleared;
        private int next;

        Restore(World world, Plot plot, Snapshot snapshot, Runnable then) {
            this.world = world;
            this.plot = plot;
            this.snapshot = snapshot;
            this.states = snapshot.states();
            this.tiles = new boolean[states.length];
            for (int i = 0; i < states.length; i++) {
                tiles[i] = states[i] != null && states[i].createBlockState() instanceof TileState;
            }
            this.then = then;
        }

        @Override
        public boolean step(long deadline) {
            Box box = plot.box();
            if (!loaded(world, box)) {
                return false;
            }
            if (!cleared) {
                clearOut(box);
                cleared = true;
            }
            int[] indices = snapshot.indices();
            int width = box.width();
            int area = width * box.depth();
            while (next < indices.length) {
                int i = next++;
                BlockData target = states[indices[i]];
                if (target == null) {
                    continue;
                }
                int x = box.minX() + i % width;
                int z = box.minZ() + (i % area) / width;
                int y = box.minY() + i / area;
                if (x == plot.signX() && y == plot.signY() && z == plot.signZ()) {
                    continue; // The plot's own sign stays.
                }
                BlockData current = world.getBlockData(x, y, z);
                if (!current.equals(target)) {
                    world.getBlockAt(x, y, z).setBlockData(target, false);
                } else if (tiles[indices[i]]) {
                    // The same chest or sign, but what's in it isn't: make it anew, empty.
                    Block block = world.getBlockAt(x, y, z);
                    block.setType(org.bukkit.Material.AIR, false);
                    block.setBlockData(target, false);
                }
                if ((i & 63) == 0 && System.nanoTime() > deadline) {
                    return false;
                }
            }
            busy.remove(plot);
            then.run();
            return true;
        }

        // Players are moved out to the path; things left lying around go.
        private void clearOut(Box box) {
            org.bukkit.util.BoundingBox bounds = new org.bukkit.util.BoundingBox(
                box.minX(), box.minY(), box.minZ(), box.maxX() + 1, box.maxY() + 1, box.maxZ() + 1);
            Location front = null;
            for (Entity entity : world.getNearbyEntities(bounds)) {
                if (entity instanceof Player player) {
                    if (front == null) {
                        front = Places.front(world, plot);
                    }
                    player.teleport(front);
                } else if (entity instanceof Item || entity instanceof Hanging || entity instanceof ArmorStand
                    || entity instanceof FallingBlock || entity instanceof ExperienceOrb || entity instanceof TNTPrimed
                    || ((entity instanceof Minecart || entity instanceof Boat) && entity.getPassengers().isEmpty())) {
                    // Not mobs: a pet standing in the plot is someone's.
                    entity.remove();
                }
            }
        }

        // Stopping: a small plot takes a moment, so finish it rather than leave it half put back.
        @Override
        public void abandon() {
            if (busy.contains(plot) && loaded(world, plot.box())) {
                step(Long.MAX_VALUE);
            }
            busy.remove(plot);
        }
    }
}
