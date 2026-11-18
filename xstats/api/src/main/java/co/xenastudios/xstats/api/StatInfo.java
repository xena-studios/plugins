package co.xenastudios.xstats.api;

import org.jetbrains.annotations.NotNull;

/**
 * A stat xStats keeps.
 *
 * @param id its id, as in placeholders and the web API: {@code kills}, {@code xvote_votes}
 * @param name its name, as players see it
 * @param unit what its numbers measure
 * @param decimals how many decimal places its whole numbers hold (0 for most stats)
 * @param periods whether it's counted per day, week and month, not only all time
 * @param shown whether players and the website may see it
 * @param owner who provides it: {@code minecraft}, {@code xstats}, {@code placeholder}, or the
 *     name of the plugin that registered it
 * @since 1.0.0
 */
public record StatInfo(
    @NotNull String id,
    @NotNull String name,
    @NotNull StatUnit unit,
    int decimals,
    boolean periods,
    boolean shown,
    @NotNull String owner) {}
