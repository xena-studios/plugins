package co.xenastudios.xstats.api;

import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * Everything xStats knows about one player, as of its last save.
 *
 * @param player the player's uuid
 * @param name their last known name
 * @param firstJoin when they first joined, in epoch milliseconds, or 0 if unknown
 * @param lastSeen when they were last online, in epoch milliseconds, or 0 if unknown
 * @param hidden whether they hid themselves from the website with {@code /stats private}
 * @param values each period's values, by stat id; a stat missing from a map is 0
 * @since 1.0.0
 */
public record StatsProfile(
    @NotNull UUID player,
    @NotNull String name,
    long firstJoin,
    long lastSeen,
    boolean hidden,
    @NotNull Map<Period, Map<String, Long>> values) {

    /**
     * One stat's value for a period.
     *
     * @param stat the stat's id
     * @param period the period
     * @return the value, or 0 if there's none
     * @since 1.0.0
     */
    public long value(@NotNull String stat, @NotNull Period period) {
        Map<String, Long> map = values.get(period);
        Long value = map == null ? null : map.get(stat);
        return value == null ? 0 : value;
    }
}
