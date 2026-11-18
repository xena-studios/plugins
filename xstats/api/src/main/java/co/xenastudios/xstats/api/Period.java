package co.xenastudios.xstats.api;

import java.util.Locale;
import java.util.Optional;
import org.jetbrains.annotations.NotNull;

/**
 * A stretch of time a stat is counted over. Days, weeks and months follow the server's
 * calendar (the {@code periods.timezone} setting), so "daily" starts at midnight there.
 *
 * @since 1.0.0
 */
public enum Period {

    /**
     * Today.
     *
     * @since 1.0.0
     */
    DAILY,

    /**
     * This week, from the day the config's {@code periods.week-starts} names.
     *
     * @since 1.0.0
     */
    WEEKLY,

    /**
     * This calendar month.
     *
     * @since 1.0.0
     */
    MONTHLY,

    /**
     * Everything, since the player first joined (or since the history xStats imported).
     *
     * @since 1.0.0
     */
    ALL;

    /**
     * The name used in commands, placeholders and the web API: {@code daily},
     * {@code weekly}, {@code monthly} or {@code all}.
     *
     * @return the lowercase name
     * @since 1.0.0
     */
    public @NotNull String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * The period with this name, ignoring case. {@code alltime} and {@code total} also mean
     * {@link #ALL}.
     *
     * @param name a name such as {@code monthly}
     * @return the period, or empty if the name isn't one
     * @since 1.0.0
     */
    public static @NotNull Optional<Period> parse(@NotNull String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (Period period : values()) {
            if (period.id().equals(lower)) {
                return Optional.of(period);
            }
        }
        return lower.equals("alltime") || lower.equals("total") ? Optional.of(ALL) : Optional.empty();
    }
}
