package co.xenastudios.xstats.api.event;

import co.xenastudios.xstats.api.Period;
import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A leaderboard has a new leader: someone else is first for a stat and period. For
 * announcements and rewards. Not fired when a new day, week or month empties a board,
 * nor for the boards worked out when xStats starts.
 *
 * <p>Fired asynchronously, on xStats' own thread, after the save that changed the board.
 * Only built when something listens for it.
 *
 * @since 1.0.0
 */
public final class LeaderboardChangeEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String stat;
    private final Period period;
    private final UUID previous;
    private final UUID leader;
    private final String leaderName;
    private final long value;

    /**
     * Made by xStats; other plugins listen for it.
     *
     * @param stat the stat's id
     * @param period the board's period
     * @param previous who led before, or null if nobody did
     * @param leader who leads now
     * @param leaderName their name
     * @param value their value, in the stat's unit
     * @since 1.0.0
     */
    public LeaderboardChangeEvent(@NotNull String stat, @NotNull Period period, @Nullable UUID previous,
                                  @NotNull UUID leader, @NotNull String leaderName, long value) {
        super(true);
        this.stat = stat;
        this.period = period;
        this.previous = previous;
        this.leader = leader;
        this.leaderName = leaderName;
        this.value = value;
    }

    /**
     * The stat whose board changed.
     *
     * @return its id
     * @since 1.0.0
     */
    public @NotNull String getStat() {
        return stat;
    }

    /**
     * The board's period.
     *
     * @return the period
     * @since 1.0.0
     */
    public @NotNull Period getPeriod() {
        return period;
    }

    /**
     * Who led before.
     *
     * @return their uuid, or null if the board was empty
     * @since 1.0.0
     */
    public @Nullable UUID getPrevious() {
        return previous;
    }

    /**
     * Who leads now.
     *
     * @return their uuid
     * @since 1.0.0
     */
    public @NotNull UUID getLeader() {
        return leader;
    }

    /**
     * The new leader's name.
     *
     * @return the name
     * @since 1.0.0
     */
    public @NotNull String getLeaderName() {
        return leaderName;
    }

    /**
     * The new leader's value.
     *
     * @return the value, in the stat's unit
     * @since 1.0.0
     */
    public long getValue() {
        return value;
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
