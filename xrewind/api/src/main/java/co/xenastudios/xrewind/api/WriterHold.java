package co.xenastudios.xrewind.api;

/**
 * A hold on xRewind's database writes, from {@link XRewindApi#pause()}. While any hold
 * is held, nothing is written to the database: not the log, not a purge. Each caller's
 * hold is its own, so letting go of yours never ends another plugin's: writing carries
 * on once every hold is let go.
 *
 * <p>Let go of it as soon as you can, with {@link #release()} or try-with-resources. A
 * hold lets go by itself after 60 seconds, with a warning in the console.
 *
 * <p>Made by xRewind only: other plugins don't implement it.
 *
 * @since 1.1.0
 */
public interface WriterHold extends AutoCloseable {

    /**
     * Lets go of this hold. Does nothing if it's already gone. Any thread.
     *
     * @since 1.1.0
     */
    void release();

    /**
     * Whether this hold still stops writes: false once it's let go, or ran out.
     *
     * @return true while held
     * @since 1.1.0
     */
    boolean isHeld();

    /**
     * The same as {@link #release()}, for try-with-resources.
     *
     * @since 1.1.0
     */
    @Override
    void close();
}
