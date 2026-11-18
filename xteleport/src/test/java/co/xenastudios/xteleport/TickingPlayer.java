package co.xenastudios.xteleport;

import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.function.Consumer;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * A mock player whose own scheduler's tasks can be cancelled, which MockBukkit's can't yet,
 * so a warmup can run to its end. Its tasks run on the mock server's ticks.
 */
final class TickingPlayer extends PlayerMock {

    private final ServerMock server;

    TickingPlayer(ServerMock server, String name) {
        super(server, name);
        this.server = server;
    }

    private final class Task implements ScheduledTask {
        private final Plugin plugin;
        private final boolean repeating;
        BukkitTask task;
        volatile boolean cancelled;

        Task(Plugin plugin, boolean repeating) {
            this.plugin = plugin;
            this.repeating = repeating;
        }

        @Override
        public Plugin getOwningPlugin() {
            return plugin;
        }

        @Override
        public boolean isRepeatingTask() {
            return repeating;
        }

        @Override
        public CancelledState cancel() {
            if (cancelled) {
                return CancelledState.CANCELLED_ALREADY;
            }
            cancelled = true;
            task.cancel();
            return CancelledState.CANCELLED_BY_CALLER;
        }

        @Override
        public ExecutionState getExecutionState() {
            return cancelled ? ExecutionState.CANCELLED : ExecutionState.IDLE;
        }
    }

    private final EntityScheduler scheduler = new EntityScheduler() {
        @Override
        public boolean execute(Plugin plugin, Runnable run, Runnable retired, long delay) {
            server.getScheduler().runTaskLater(plugin, run, Math.max(1, delay));
            return true;
        }

        @Override
        public ScheduledTask run(Plugin plugin, Consumer<ScheduledTask> work, Runnable retired) {
            return runDelayed(plugin, work, retired, 1);
        }

        @Override
        public ScheduledTask runDelayed(Plugin plugin, Consumer<ScheduledTask> work, Runnable retired, long delay) {
            Task task = new Task(plugin, false);
            task.task = server.getScheduler().runTaskLater(plugin, () -> work.accept(task), Math.max(1, delay));
            return task;
        }

        @Override
        public ScheduledTask runAtFixedRate(Plugin plugin, Consumer<ScheduledTask> work, Runnable retired, long delay, long period) {
            Task task = new Task(plugin, true);
            task.task = server.getScheduler().runTaskTimer(plugin, () -> work.accept(task), Math.max(1, delay), period);
            return task;
        }
    };

    @Override
    public EntityScheduler getScheduler() {
        return scheduler;
    }
}
