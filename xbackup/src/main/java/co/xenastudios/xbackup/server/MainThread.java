package co.xenastudios.xbackup.server;

import co.xenastudios.core.task.Tasks;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Runs one of xBackup's few steps that need the server's main thread (the global thread on
 * Folia) and waits for its answer. Through {@link Tasks#nextTick}, which keeps running while
 * an empty Paper server is paused, so a backup on an empty server doesn't time out waiting.
 */
final class MainThread {

    private MainThread() {}

    /** Runs on the main thread and waits for the answer. Not from the main thread. */
    static <T> T call(Tasks tasks, long timeoutSeconds, Supplier<T> work) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        tasks.nextTick(() -> {
            try {
                result.complete(work.get());
            } catch (Throwable e) {
                result.completeExceptionally(e);
            }
        });
        return result.get(timeoutSeconds, TimeUnit.SECONDS);
    }
}
