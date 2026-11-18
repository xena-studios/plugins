package co.xenastudios.xstats.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * xStats saved: players' stats are in the database, and leaderboards and the website's
 * pages are worked out again. For plugins that read stats or leaderboards and want them
 * as soon as they change, rather than asking on a timer of their own.
 *
 * <p>Fired asynchronously, on xStats' own thread, after each save. Only built when
 * something listens for it.
 *
 * @since 1.0.0
 */
public final class StatsSavedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final int players;
    private final long millis;

    /**
     * Made by xStats; other plugins listen for it.
     *
     * @param players how many players' stats changed in this save
     * @param millis how long the save took, in milliseconds, from the first read to the
     *     leaderboards being ready
     * @since 1.0.0
     */
    public StatsSavedEvent(int players, long millis) {
        super(true);
        this.players = players;
        this.millis = millis;
    }

    /**
     * How many players' stats changed in this save.
     *
     * @return the count
     * @since 1.0.0
     */
    public int getPlayers() {
        return players;
    }

    /**
     * How long the save took, from the first read to the leaderboards being ready. The
     * reads are spread across ticks on purpose, so most of it is waiting.
     *
     * @return milliseconds
     * @since 1.0.0
     */
    public long getMillis() {
        return millis;
    }

    /**
     * This event's listeners.
     *
     * @return the handlers
     * @since 1.0.0
     */
    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    /**
     * Every listener for this event, as Bukkit needs.
     *
     * @return the handlers
     * @since 1.0.0
     */
    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
