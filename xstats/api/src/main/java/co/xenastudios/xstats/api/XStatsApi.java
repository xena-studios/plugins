package co.xenastudios.xstats.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * xStats' developer API: players' stats, ranks and leaderboards, stats of your own, and
 * sections on player profiles.
 *
 * <p>Get it with {@link #get()}, or from Bukkit's services manager; it's registered while
 * xStats is enabled. Unless a method says otherwise it may be called from any thread,
 * answers from memory and returns immediately. Values are as of xStats' last save (every
 * few minutes), which is also when leaderboards are worked out.
 *
 * @since 1.0.0
 */
public interface XStatsApi {

    /**
     * xStats' API, from Bukkit's services manager.
     *
     * @return the API
     * @throws IllegalStateException if xStats isn't installed and enabled
     * @since 1.0.0
     */
    static @NotNull XStatsApi get() {
        XStatsApi api = Bukkit.getServicesManager().load(XStatsApi.class);
        if (api == null) {
            throw new IllegalStateException("xStats isn't enabled. Add it to depend (or softdepend and check first).");
        }
        return api;
    }

    /**
     * Every stat xStats keeps, in the order the config lists them, then stats other
     * plugins registered. Any thread, from memory.
     *
     * @return the stats
     * @since 1.0.0
     */
    @NotNull List<StatInfo> getStats();

    /**
     * One stat. Any thread, from memory.
     *
     * @param stat the stat's id, such as {@code kills}
     * @return the stat, or empty if there's no such stat
     * @since 1.0.0
     */
    @NotNull Optional<StatInfo> getStatInfo(@NotNull String stat);

    /**
     * A player's value for a stat over a period, as of the last save. Any thread, from memory.
     *
     * @param player the player's uuid
     * @param stat the stat's id
     * @param period the period; a stat without periods only has {@link Period#ALL}
     * @return the value in the stat's unit, or 0 if there's none (or no such stat)
     * @since 1.0.0
     */
    long getStat(@NotNull UUID player, @NotNull String stat, @NotNull Period period);

    /**
     * A player's place on a leaderboard, as of the last save. Players left out of
     * leaderboards (staff with {@code xstats.leaderboard.exclude}, banned players) and
     * players with nothing for that period have no place. Any thread, from memory.
     *
     * @param player the player's uuid
     * @param stat the stat's id
     * @param period the period
     * @return the place, from 1, or 0 if they have none
     * @since 1.0.0
     */
    int getRank(@NotNull UUID player, @NotNull String stat, @NotNull Period period);

    /**
     * The top of a leaderboard, as worked out at the last save. Any thread, from memory.
     *
     * @param stat the stat's id
     * @param period the period
     * @param count how many places at most; leaderboards keep {@code leaderboards.size} places
     * @return the places, best first; empty if there's no such stat or nobody on it
     * @since 1.0.0
     */
    @NotNull List<LeaderboardEntry> getTop(@NotNull String stat, @NotNull Period period, int count);

    /**
     * Everything xStats knows about a player. Answered from memory when the player is
     * known, otherwise read from the database.
     *
     * @param player the player's uuid
     * @return a future, completed off the server's threads (or already complete); empty if
     *     xStats has never seen the player
     * @since 1.0.0
     */
    @NotNull CompletableFuture<Optional<StatsProfile>> getProfile(@NotNull UUID player);

    /**
     * Whether a player hid themselves from the website with {@code /stats private}. Any
     * thread, from memory.
     *
     * @param player the player's uuid
     * @return true if they're hidden
     * @since 1.0.0
     */
    boolean isPrivate(@NotNull UUID player);

    /**
     * Adds a stat of your own, kept, saved, ranked and shown like xStats' own: on
     * {@code /stats}, {@code /top}, placeholders and the website. It's counted per day,
     * week and month too. Registering the same id again returns the same stat. Registered
     * stats are forgotten when your plugin is disabled; their saved values stay.
     *
     * @param plugin your plugin
     * @param id the stat's id within your plugin: 1 to 32 lowercase letters, digits and
     *     underscores, such as {@code votes}. Its full id is your plugin's name in lowercase,
     *     an underscore and this, such as {@code xvote_votes}.
     * @param displayName its name, as players see it, such as {@code Votes}
     * @param unit what its numbers measure
     * @return the stat, to change players' values with
     * @throws IllegalArgumentException if the id isn't valid, or the config already has a
     *     stat with the full id
     * @since 1.0.0
     */
    @NotNull CustomStat registerStat(@NotNull Plugin plugin, @NotNull String id, @NotNull String displayName, @NotNull StatUnit unit);

    /**
     * Adds a section to player profiles: on {@code /stats}, the website and the web API.
     * Sections show in the order they were registered, after the stats. Registering the
     * same id again replaces the section. Sections are dropped when their plugin is disabled.
     *
     * @param plugin your plugin
     * @param id the section's id within your plugin, such as {@code votes}; it's
     *     {@code <plugin>_<id>} in the web API
     * @param title the section's heading, as players see it
     * @param provider what fills it, called off the server's threads
     * @since 1.0.0
     */
    void registerProfileSection(@NotNull Plugin plugin, @NotNull String id, @NotNull String title,
                                @NotNull ProfileSectionProvider provider);

    /**
     * Removes a section added with {@link #registerProfileSection}. Does nothing if there's none.
     *
     * @param plugin the plugin that added it
     * @param id the id it was added with
     * @since 1.0.0
     */
    void unregisterProfileSection(@NotNull Plugin plugin, @NotNull String id);
}
