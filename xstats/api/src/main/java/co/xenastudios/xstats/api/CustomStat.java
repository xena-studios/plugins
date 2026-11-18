package co.xenastudios.xstats.api;

import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * A stat another plugin registered with {@link XStatsApi#registerStat}. Changes are
 * queued and saved with xStats' next save, with the stats it reads itself; until then,
 * reads return the value from the last save. Every method may be called from any thread
 * and returns immediately.
 *
 * @since 1.0.0
 */
public interface CustomStat {

    /**
     * The stat's id, as in placeholders and the web API: the plugin's name in lowercase,
     * an underscore and the id it registered, such as {@code xvote_votes}.
     *
     * @return the id
     * @since 1.0.0
     */
    @NotNull String id();

    /**
     * Adds to a player's value. Counts towards today, this week and this month as well as
     * all time. Any thread.
     *
     * @param player the player's uuid; they needn't be online, but must have joined once
     * @param amount how much to add, above 0
     * @throws IllegalArgumentException if {@code amount} isn't above 0
     * @since 1.0.0
     */
    void increment(@NotNull UUID player, long amount);

    /**
     * Sets a player's all-time value, such as a balance. Only a rise counts towards the
     * periods; a fall changes the all-time value alone. Any thread.
     *
     * @param player the player's uuid; they needn't be online, but must have joined once
     * @param value the new value, 0 or more
     * @throws IllegalArgumentException if {@code value} is below 0
     * @since 1.0.0
     */
    void set(@NotNull UUID player, long value);
}
