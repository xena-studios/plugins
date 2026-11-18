package co.xenastudios.xbackup.server;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * Runs xBackup's few steps that need the server's main thread (the global thread on Folia).
 *
 * <p>On Paper this goes through the Bukkit scheduler, not the global region scheduler. With
 * nobody online, Paper 1.21.2+ pauses the server after {@code pause-when-empty-seconds}
 * (60 by default). Read from Paper 1.21.11 and 26.3's {@code MinecraftServer#tickServer}: the
 * paused tick still runs {@code CraftScheduler#mainThreadHeartbeat}, but the global region
 * scheduler only ticks in {@code tickChildren}, which the paused tick skips. So on an empty
 * server a global-region task waits until someone joins, and a backup waiting for one would
 * time out. Folia doesn't pause when empty, and has no Bukkit scheduler.
 */
final class MainThread {

    private MainThread() {}

    static void run(Plugin plugin, boolean folia, Runnable work) {
        if (folia) {
            Bukkit.getGlobalRegionScheduler().execute(plugin, work);
        } else {
            Bukkit.getScheduler().runTask(plugin, work);
        }
    }

    /** Runs on the main thread and waits for the answer. Not from the main thread. */
    static <T> T call(Plugin plugin, boolean folia, long timeoutSeconds, Supplier<T> work) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        run(plugin, folia, () -> {
            try {
                result.complete(work.get());
            } catch (Throwable e) {
                result.completeExceptionally(e);
            }
        });
        return result.get(timeoutSeconds, TimeUnit.SECONDS);
    }
}
