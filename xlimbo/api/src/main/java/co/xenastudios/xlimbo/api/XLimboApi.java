package co.xenastudios.xlimbo.api;

import java.util.OptionalInt;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * xLimbo's developer API: whether the main server is up, the queue of players waiting to
 * go back to it, and sending players back.
 *
 * <p>Get it with {@link #get()}, or from Bukkit's services manager. It's registered
 * while xLimbo is enabled. Every method may be called from any thread and returns
 * immediately. xLimbo runs on Paper only, not Folia.
 *
 * @since 2.1.0
 */
public interface XLimboApi {

    /**
     * xLimbo's API, from Bukkit's services manager.
     *
     * @return the API
     * @throws IllegalStateException if xLimbo isn't installed and enabled
     * @since 2.1.0
     */
    static XLimboApi get() {
        XLimboApi api = Bukkit.getServicesManager().load(XLimboApi.class);
        if (api == null) {
            throw new IllegalStateException("xLimbo isn't enabled. Add it to depend (or softdepend and check first).");
        }
        return api;
    }

    /**
     * Whether {@code server-check} is on, so xLimbo asks the main server for its status
     * and {@link #isMainServerUp()} means something. Any thread, answers from memory.
     *
     * @return true while server-check is on
     * @since 2.1.0
     */
    boolean isServerCheckEnabled();

    /**
     * Whether the main server answered xLimbo's last status check. Any thread, answers
     * from memory: the check itself runs in the background, every
     * {@code server-check.interval-seconds}.
     *
     * @return true if it answered; false if it didn't, if it hasn't been asked yet, or if
     *     server-check is off (xLimbo doesn't know)
     * @since 2.1.0
     */
    boolean isMainServerUp();

    /**
     * A player's place in the queue to go back to the main server. Any thread, answers
     * from memory (a snapshot, updated every second).
     *
     * @param player the player
     * @return their place, from 1; empty if they aren't waiting
     * @since 2.1.0
     */
    OptionalInt getQueuePosition(Player player);

    /**
     * How many players are waiting to go back to the main server. Any thread, answers
     * from memory (a snapshot, updated every second).
     *
     * @return the queue's length; 0 while the queue is off
     * @since 2.1.0
     */
    int getQueueSize();

    /**
     * Sends a player back to the main server. While the queue is on (auto-join or
     * server-check), they join the end of it and go in turn, a few per second; otherwise
     * they're sent at once. While server-check says the main server is down, nobody is
     * sent: everyone goes back when it comes up. Fires
     * {@link co.xenastudios.xlimbo.api.event.PlayerReturnEvent} just before they're sent.
     *
     * <p>Any thread: done on the main thread, at once if called from it, otherwise on
     * the next tick.
     *
     * @param player an online player
     * @since 2.1.0
     */
    void sendBack(Player player);

    /**
     * {@link #sendBack(Player)} for every player in limbo. Any thread, as there.
     *
     * @since 2.1.0
     */
    void sendBackAll();
}
