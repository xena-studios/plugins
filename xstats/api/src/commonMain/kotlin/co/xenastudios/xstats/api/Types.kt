package co.xenastudios.xstats.api

import co.xenastudios.core.api.PlayerRef
import kotlin.jvm.JvmStatic

/**
 * What a stat's numbers measure, which decides how they're written: `1,234`, `3h 12m`, `1.2 km`,
 * `4.5 hearts`.
 *
 * @since 1.0.0
 */
public enum class StatUnit {
    /** A plain number: kills, blocks, votes. */
    COUNT,

    /** Seconds. */
    TIME,

    /** Centimetres, as Minecraft counts distances. */
    DISTANCE,

    /** Tenths of a health point, as Minecraft counts damage. */
    DAMAGE,
}

/**
 * The stretch of time a value covers. Days, weeks and months start at midnight in the time zone the
 * server owner set (`periods.timezone`), and weeks on the day they chose.
 *
 * @since 1.0.0
 */
public enum class Period(
    /** How commands, placeholders and the web API name it: `daily`, `weekly`, `monthly`, `all`. */
    public val id: String,
) {
    DAILY("daily"),
    WEEKLY("weekly"),
    MONTHLY("monthly"),
    ALL("all"),
    ;

    public companion object {
        /** The period [text] names (`daily`, `week`, `all-time`...), ignoring case, or `null`. */
        @JvmStatic
        public fun parse(text: String): Period? = when (text.trim().lowercase()) {
            "daily", "day", "today" -> DAILY
            "weekly", "week" -> WEEKLY
            "monthly", "month" -> MONTHLY
            "all", "alltime", "all-time", "all_time", "total" -> ALL
            else -> null
        }
    }
}

/**
 * One stat xStats keeps: from the config, or added by a plugin through [XStatsApi.registerStat].
 *
 * @since 1.0.0
 */
public class StatInfo(
    /** Its id: what placeholders, commands and the web API name it by, such as `kills` or `xvote_votes`. */
    public val id: String,
    /** Its name, as players read it. */
    public val name: String,
    public val unit: StatUnit,
    /** Places after the point its whole numbers hold: 2 for a balance kept in cents. */
    public val decimals: Int,
    /** Whether it's counted per day, week and month too, so it has those leaderboards. */
    public val periods: Boolean,
    /** Whether it shows on `/stats` and `/leaderboard`. */
    public val shown: Boolean,
    /** Who keeps it: `minecraft`, `xstats`, `placeholder`, or the name of the plugin that registered it. */
    public val owner: String,
    /** Whether it shows on the website and in the web API. @since 2.0.0 */
    public val website: Boolean = true,
) {
    override fun toString(): String = "StatInfo($id, $owner)"
}

/**
 * One place on a leaderboard. Ties share a place.
 *
 * @since 1.0.0
 */
public class LeaderboardEntry(
    /** The place, from 1. */
    public val rank: Int,
    public val player: PlayerRef,
    public val name: String,
    /** The value, in the stat's unit. */
    public val value: Long,
) {
    override fun toString(): String = "#$rank $name $value"
}

/**
 * Everything xStats knows about a player.
 *
 * @since 1.0.0
 */
public class StatsProfile(
    public val player: PlayerRef,
    public val name: String?,
    /** When they first joined, in milliseconds since 1970, or 0. */
    public val firstJoin: Long,
    /** When they were last seen, in milliseconds since 1970, or 0. */
    public val lastSeen: Long,
    /** Whether they hid themselves from the website with `/stats private`. */
    public val hidden: Boolean,
    /** Their values by period, then stat id. */
    public val values: Map<Period, Map<String, Long>>,
) {
    /** Their value of [stat] over [period], or 0. */
    public fun value(stat: String, period: Period): Long = values[period]?.get(stat) ?: 0L

    override fun toString(): String = "StatsProfile($player, $name)"
}

/**
 * One line of a section a plugin adds to profiles.
 *
 * @since 1.0.0
 */
public class ProfileLine(public val label: String, public val value: String) {
    override fun toString(): String = "$label: $value"
}

/**
 * What fills a section a plugin adds to profiles ([XStatsApi.registerProfileSection]): asked off the
 * server's threads each time a profile is shown, with half a second to answer.
 *
 * @since 1.0.0
 */
public fun interface ProfileSection {
    /**
     * The section's lines for [player], or none to leave it out. [staff] is whether the reader may
     * see more (`xstats.staff`), such as a full punishment history.
     */
    public fun lines(player: PlayerRef, staff: Boolean): List<ProfileLine>
}

/**
 * A stat a plugin registered ([XStatsApi.registerStat]), to change players' values with. Every
 * change waits in memory and is saved with the next save, so calling these on every event costs a
 * map update, never a database write. Any thread.
 *
 * @since 1.0.0
 */
public interface CustomStat {
    /** Its full id, such as `xvote_votes`. */
    public val id: String

    /** Adds [amount] to [player]'s value (and to today's, this week's and this month's). */
    public fun increment(player: PlayerRef, amount: Long)

    /**
     * Sets [player]'s value outright, as for a balance. A rise counts towards today, this week and
     * this month.
     */
    public fun set(player: PlayerRef, value: Long)
}
