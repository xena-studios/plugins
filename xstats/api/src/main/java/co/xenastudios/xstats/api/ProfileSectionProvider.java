package co.xenastudios.xstats.api;

import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * Fills a section another plugin adds to player profiles with
 * {@link XStatsApi#registerProfileSection}: on {@code /stats}, the website and the web API.
 *
 * <p>Called off the server's threads, whenever a profile is shown, so it should answer from
 * memory. A provider that takes longer than half a second is skipped for that profile, and
 * one that throws is logged once and skipped; neither breaks the profile.
 *
 * @since 1.0.0
 */
@FunctionalInterface
public interface ProfileSectionProvider {

    /**
     * The section's lines for a player.
     *
     * @param player the player whose profile is shown
     * @param staff true when staff look in game (they have {@code xstats.staff}), false for
     *     the website, the web API and everyone else, so a section can show staff more
     * @return the lines, in order; empty to leave the section out of this profile
     * @since 1.0.0
     */
    @NotNull List<ProfileLine> lines(@NotNull UUID player, boolean staff);
}
