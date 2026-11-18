package co.xenastudios.xstats.collect;

import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * xStats' own thread. It owns every stat in memory and does all of xStats' database
 * work, one thing at a time, so nothing in memory needs a lock and the server's threads
 * never wait on the database.
 */
public final class Worker {

    private final ScheduledThreadPoolExecutor executor;
    private final Logger logger;
    private volatile Thread thread;

    public Worker(Logger logger) {
        this.logger = logger;
        this.executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread made = new Thread(runnable, "xStats");
            made.setDaemon(true);
            thread = made;
            return made;
        });
        executor.setRemoveOnCancelPolicy(true);
    }

    public boolean onThread() {
        return Thread.currentThread() == thread;
    }

    /** Runs on xStats' thread, soon. An error is logged, never thrown at the caller. */
    public Future<?> execute(Runnable work) {
        return executor.submit(guarded(work));
    }

    public void schedule(Runnable work, long millis) {
        executor.schedule(guarded(work), millis, TimeUnit.MILLISECONDS);
    }

    private Runnable guarded(Runnable work) {
        return () -> {
            try {
                work.run();
            } catch (RuntimeException | LinkageError e) {
                logger.log(Level.SEVERE, "Something went wrong on xStats' thread", e);
            }
        };
    }

    /** Lets queued work finish, for at most {@code seconds}, then stops. */
    public void stop(int seconds) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(seconds, TimeUnit.SECONDS)) {
                logger.warning("xStats' thread didn't finish within " + seconds + " seconds; stopping it.");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
