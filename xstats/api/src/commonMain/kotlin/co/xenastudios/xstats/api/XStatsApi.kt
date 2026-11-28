package co.xenastudios.xstats.api

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.event.EventBus
import kotlin.concurrent.Volatile
import kotlin.jvm.JvmStatic

/**
 * xStats' developer API, the same on Paper, Folia, Spigot, Fabric and the proxy: players' stats,
 * ranks and leaderboards, stats of your own, sections on player profiles, and the stats events.
 * Players are [PlayerRef]s (from Java, `PlayerRefs.of(player.getUniqueId())`), so one API works
 * everywhere.
 *
 * Get it with [XStats.api]; on Bukkit it's also in the services manager. Every read answers from
 * memory at once, on any thread, as of xStats' last save (every few minutes), which is also when
 * leaderboards are worked out. On a server in network mode, and on the proxy, values are the
 * network's.
 *
 * @since 1.0.0
 */
public interface XStatsApi {
    /** xStats' version, such as `2.0.0`. @since 2.0.0 */
    public val version: String

    /**
     * Where xStats' events are heard ([co.xenastudios.xstats.api.event]): a plugin's change to a
     * stat and `/stats private` (both cancellable), each save, and a new leader. On Bukkit each is
     * also a Bukkit event in `co.xenastudios.xstats.api.bukkit`.
     *
     * @since 2.0.0
     */
    public val events: EventBus

    /** Whether this is xStats on the proxy, which reads the network's stats and counts none itself. @since 2.0.0 */
    public val proxy: Boolean

    /** Every stat, in the order the config lists them, then the ones plugins registered. */
    public fun stats(): List<StatInfo>

    /** One stat by its id, or `null`. */
    public fun stat(id: String): StatInfo?

    /** [player]'s value of [stat] over [period], in the stat's unit: 0 if they have none, or there's no such stat. */
    public fun value(player: PlayerRef, stat: String, period: Period): Long

    /**
     * [player]'s place on the leaderboard of [stat] over [period], from 1, or 0 if they have none:
     * players with nothing in it, players left out of leaderboards (`xstats.leaderboard.exclude`,
     * banned or vanished) and stats with no such board.
     */
    public fun rank(player: PlayerRef, stat: String, period: Period): Int

    /** Up to [count] places from the top of a leaderboard, best first; empty if there's no such board. */
    public fun top(stat: String, period: Period, count: Int): List<LeaderboardEntry>

    /**
     * Everything xStats knows about [player], from memory, or `null` if xStats has never seen them.
     * Settled at once.
     */
    public fun profile(player: PlayerRef): Pending<StatsProfile?>

    /** Whether [player] hid themselves from the website with `/stats private`. */
    public fun isPrivate(player: PlayerRef): Boolean

    /**
     * Adds a stat of your own, kept, saved, ranked and shown like xStats' own: on `/stats`,
     * `/leaderboard`, placeholders and the website, per day, week and month too. Its full id is
     * your plugin's name in lowercase, `_`, and [id] (`xvote_votes`). Registering it again returns
     * the same stat. Saved values stay when your plugin goes; [unregisterStats] takes the stats out
     * of use.
     *
     * @param owner your plugin's name, such as `xVote`
     * @param id 1 to 32 lowercase letters, digits and `_`
     * @param name its name as players read it, such as `Votes`
     * @param latest `true` for a value that's set, such as a balance (on a network, the latest any
     *   server set); `false` for a count, such as votes (on a network, every server's added up)
     * @param decimals places after the point its whole numbers hold: 2 for a balance in cents
     * @throws IllegalArgumentException if [id] isn't valid, or the config has a stat with the full id
     */
    public fun registerStat(owner: String, id: String, name: String, unit: StatUnit, latest: Boolean, decimals: Int): CustomStat

    /** [registerStat] with no decimals. */
    public fun registerStat(owner: String, id: String, name: String, unit: StatUnit, latest: Boolean): CustomStat =
        registerStat(owner, id, name, unit, latest, 0)

    /** [registerStat] for a count, with no decimals. */
    public fun registerStat(owner: String, id: String, name: String, unit: StatUnit): CustomStat =
        registerStat(owner, id, name, unit, false, 0)

    /** Takes [owner]'s stats out of use, as it's disabled; their saved values stay. @since 2.0.0 */
    public fun unregisterStats(owner: String)

    /**
     * Adds a section to player profiles, on `/stats`, the website and the web API, after the stats,
     * in the order sections were added. Registering the same [id] again replaces it.
     *
     * @param owner your plugin's name
     * @param id the section's id within your plugin; `<owner>_<id>` in the web API
     */
    public fun registerProfileSection(owner: String, id: String, title: String, section: ProfileSection)

    /** Takes a section away. Does nothing if there's none. */
    public fun unregisterProfileSection(owner: String, id: String)
}

/**
 * Where xStats' API is found, on every platform:
 *
 * ```java
 * XStatsApi api = XStats.api();
 * ```
 *
 * @since 2.0.0
 */
public object XStats {
    @Volatile
    private var running: XStatsApi? = null

    /**
     * xStats' API.
     *
     * @throws IllegalStateException if xStats isn't installed and enabled: add it to your plugin's
     *   `depend` (or `softdepend`, and check [isEnabled] first)
     */
    @JvmStatic
    public fun api(): XStatsApi =
        running ?: throw IllegalStateException("xStats isn't enabled. Add it to depend (or softdepend and check XStats.isEnabled() first).")

    /** Whether xStats is enabled, so [api] answers. */
    @JvmStatic
    public fun isEnabled(): Boolean = running != null

    /** Made available by xStats as it's enabled, and taken away as it's disabled: not for other plugins. */
    @JvmStatic
    public fun register(api: XStatsApi?) {
        running = api
    }
}
