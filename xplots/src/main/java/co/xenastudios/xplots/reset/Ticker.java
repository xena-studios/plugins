package co.xenastudios.xplots.reset;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.IntSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Runs long work a few milliseconds per tick, one job after another, so saving a big
 * field's plots or putting plots back never freezes the server. Runs only while there's
 * work. Main thread.
 */
public final class Ticker {

    /** Work done in steps. */
    public interface Job {
        /**
         * Does some work, stopping once {@code System.nanoTime()} passes the deadline.
         *
         * @return true once the job is finished
         */
        boolean step(long deadline);

        /** Called if the plugin stops before the job finishes. */
        default void abandon() {}
    }

    private final Plugin plugin;
    private final Logger logger;
    private final IntSupplier msPerTick;
    private final Deque<Job> jobs = new ArrayDeque<>();
    private BukkitTask task;

    public Ticker(Plugin plugin, IntSupplier msPerTick) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.msPerTick = msPerTick;
    }

    public void add(Job job) {
        jobs.add(job);
        if (task == null) {
            task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1, 1);
        }
    }

    public boolean isIdle() {
        return jobs.isEmpty();
    }

    private void tick() {
        long deadline = System.nanoTime() + msPerTick.getAsInt() * 1_000_000L;
        while (!jobs.isEmpty() && System.nanoTime() < deadline) {
            Job job = jobs.peek();
            boolean done;
            try {
                done = job.step(deadline);
            } catch (RuntimeException e) {
                logger.log(Level.SEVERE, "A plot job failed, and was stopped", e);
                done = true;
            }
            if (done) {
                jobs.poll();
            } else {
                break; // Waiting on something, such as a chunk loading: try again next tick.
            }
        }
        if (jobs.isEmpty()) {
            task.cancel();
            task = null;
        }
    }

    /** Stops, abandoning what's left. */
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        jobs.forEach(Job::abandon);
        jobs.clear();
    }
}
