package co.xenastudios.xbackup.store;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * A bandwidth limit: callers say how many bytes they're about to move and are slowed to
 * keep the average under the limit. Shared by every reader of one kind (disk, upload), so
 * the limit holds however many threads there are.
 */
public final class Throttle {

    /** No limit. */
    public static final Throttle NONE = new Throttle(0);

    private final long bytesPerSecond;
    private long available;
    private long last = System.nanoTime();

    /** @param bytesPerSecond 0 for no limit */
    public Throttle(long bytesPerSecond) {
        this.bytesPerSecond = bytesPerSecond;
        this.available = bytesPerSecond;
    }

    public long bytesPerSecond() {
        return bytesPerSecond;
    }

    /** Waits until {@code bytes} more fit under the limit. */
    public void acquire(long bytes) throws IOException {
        if (bytesPerSecond <= 0 || bytes <= 0) {
            return;
        }
        long sleepNanos;
        synchronized (this) {
            long now = System.nanoTime();
            available = refill(available, now - last, bytesPerSecond);
            last = now;
            available -= bytes;
            sleepNanos = available >= 0 ? 0 : -available * 1_000_000_000L / bytesPerSecond;
        }
        if (sleepNanos > 0) {
            try {
                Thread.sleep(sleepNanos / 1_000_000, (int) (sleepNanos % 1_000_000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new java.io.InterruptedIOException("stopped");
            }
        }
    }

    /**
     * What's available after {@code elapsedNanos}: never more than one second's worth. The
     * elapsed time is capped at a second before multiplying, so a long idle can't overflow
     * (100 s at 100 MB/s is past {@code Long.MAX_VALUE}) and turn into a long stall.
     */
    static long refill(long available, long elapsedNanos, long bytesPerSecond) {
        long elapsed = Math.max(0, Math.min(elapsedNanos, 1_000_000_000L));
        return Math.min(bytesPerSecond, available + elapsed * bytesPerSecond / 1_000_000_000L);
    }

    /** The stream, slowed to this limit. */
    public InputStream wrap(InputStream in) {
        if (bytesPerSecond <= 0) {
            return in;
        }
        return new FilterInputStream(in) {
            @Override
            public int read() throws IOException {
                acquire(1);
                return super.read();
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                int n = super.read(b, off, Math.min(len, 64 * 1024));
                acquire(n);
                return n;
            }
        };
    }
}
