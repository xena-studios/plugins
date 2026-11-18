package co.xenastudios.xbackup.api;

/**
 * Keeps a plugin's files still while xBackup reads them. Both methods are called on
 * xBackup's own thread, never a game thread, one after the other.
 *
 * @since 1.0.0
 */
public interface QuietMoment {

    /**
     * Finish any write in progress and hold new ones until {@link #resume()}. Return once
     * the files are still. Keep it short: xBackup waits for it.
     *
     * @throws Exception if the plugin can't pause; its folder is then read anyway
     * @since 1.0.0
     */
    void pause() throws Exception;

    /**
     * Carry on writing. Always called after {@link #pause()}, even if that threw.
     *
     * @since 1.0.0
     */
    void resume();
}
