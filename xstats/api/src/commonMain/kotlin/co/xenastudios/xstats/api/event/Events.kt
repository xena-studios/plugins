package co.xenastudios.xstats.api.event

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.event.CancellableEvent
import co.xenastudios.core.api.event.Event
import co.xenastudios.core.api.event.EventType
import co.xenastudios.xstats.api.Period
import kotlin.jvm.JvmField

/**
 * A plugin is about to change one of its stats for a player ([co.xenastudios.xstats.api.CustomStat]).
 * Cancel it to drop the change; change [amount] to count something else. Fired on the thread that
 * made the change, only when something listens. On Bukkit it's also
 * `co.xenastudios.xstats.api.bukkit.BukkitStatChangeEvent`.
 *
 * @since 2.0.0
 */
public class StatChangeEvent(
    public val player: PlayerRef,
    /** The stat's full id, such as `xvote_votes`. */
    public val stat: String,
    /** How much is added, or with [set] the value set. */
    public var amount: Long,
    /** Whether the value is set outright rather than added to. */
    public val set: Boolean,
) : CancellableEvent() {
    public companion object {
        @JvmField
        public val TYPE: EventType<StatChangeEvent> = EventType.of("xstats:stat_change")
    }
}

/**
 * A player is about to hide their stats from the website with `/stats private`, or show them again.
 * Cancel it to leave them as they are. Fired on xStats' database thread, only when something
 * listens. On Bukkit it's also `co.xenastudios.xstats.api.bukkit.BukkitPrivacyChangeEvent`.
 *
 * @since 2.0.0
 */
public class PrivacyChangeEvent(
    public val player: PlayerRef,
    /** `true` when they're going private. */
    public val hidden: Boolean,
) : CancellableEvent() {
    public companion object {
        @JvmField
        public val TYPE: EventType<PrivacyChangeEvent> = EventType.of("xstats:privacy_change")
    }
}

/**
 * A save finished: what changed is in the database and every leaderboard was worked out again. Fired
 * off the server's threads, only when something listens. On Bukkit it's also
 * `co.xenastudios.xstats.api.bukkit.BukkitStatsSavedEvent`, which replaces 1.x's `StatsSavedEvent`.
 *
 * @since 1.0.0
 */
public class StatsSavedEvent(
    /** Players whose stats changed and were written. */
    public val players: Int,
    /** How long the save took, from its first reading to its last write. */
    public val millis: Long,
) : Event {
    public companion object {
        @JvmField
        public val TYPE: EventType<StatsSavedEvent> = EventType.of("xstats:saved")
    }
}

/**
 * A leaderboard has a new leader, worked out at a save. Not for a new day, week or month emptying a
 * board, nor the first boards after a start. Fired off the server's threads, only when something
 * listens. On Bukkit it's also `co.xenastudios.xstats.api.bukkit.BukkitLeaderboardChangeEvent`, which
 * replaces 1.x's `LeaderboardChangeEvent`.
 *
 * @since 1.0.0
 */
public class LeaderboardChangeEvent(
    /** The stat's id. */
    public val stat: String,
    public val period: Period,
    public val leader: PlayerRef,
    public val leaderName: String,
    /** The leader before, or `null` if the board was empty. */
    public val previous: PlayerRef?,
    /** The leader's value. */
    public val value: Long,
) : Event {
    public companion object {
        @JvmField
        public val TYPE: EventType<LeaderboardChangeEvent> = EventType.of("xstats:leaderboard_change")
    }
}
