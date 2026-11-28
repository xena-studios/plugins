package co.xenastudios.xvote.api.event

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.event.CancellableEvent
import co.xenastudios.core.api.event.Event
import co.xenastudios.core.api.event.EventType
import co.xenastudios.xvote.api.VoteSource
import kotlin.jvm.JvmField

/**
 * A vote has arrived and is about to be stored, on the server or proxy that received it. Cancel it
 * to drop the vote: it isn't stored, counted or rewarded. Fired off the server's threads, only when
 * something listens. On Bukkit it's also `co.xenastudios.xvote.api.bukkit.BukkitVoteReceivedEvent`,
 * which replaces 1.x's `VoteReceivedEvent`.
 *
 * @since 1.0.0
 */
public class VoteReceivedEvent(
    /** The site's service name, as the list sent it. */
    public val site: String,
    /** The name the list sent. */
    public val username: String,
    /** The voter's address, as the list sent it; may be empty. xVote never stores it. */
    public val address: String,
    /** The list's own timestamp, as it sent it; may be empty. */
    public val timestamp: String,
    public val source: VoteSource,
) : CancellableEvent() {
    public companion object {
        @JvmField
        public val TYPE: EventType<VoteReceivedEvent> = EventType.of("xvote:received")
    }
}

/**
 * A vote was counted for a player: stored with their uuid, their totals and streak moved on. Fired
 * once per vote on the whole network, by the server or proxy that counted it (where it arrived, or
 * where its name first joined), off the server's threads, only when something listens. For stats
 * plugins (xStats). On Bukkit it's also `co.xenastudios.xvote.api.bukkit.BukkitVoteCountedEvent`.
 *
 * @since 2.0.0
 */
public class VoteCountedEvent(
    public val player: PlayerRef,
    /** The name it was counted under. */
    public val name: String,
    /** The site's service name. */
    public val site: String,
    /** The player's total with this vote. */
    public val total: Int,
    /** Their streak with this vote. */
    public val streak: Int,
    /** Whether it was their first vote that day. */
    public val firstToday: Boolean,
    /** When it arrived, in epoch milliseconds. */
    public val at: Long,
) : Event {
    public companion object {
        @JvmField
        public val TYPE: EventType<VoteCountedEvent> = EventType.of("xvote:counted")
    }
}

/**
 * A player is about to be rewarded for a vote, on the server they're on. Remove names from
 * [rewards] to leave those out, or cancel it to pay nothing for this vote (it still counts toward
 * totals, streaks and the party). On the player's thread, only when something listens. On Bukkit
 * it's also `co.xenastudios.xvote.api.bukkit.BukkitVoteRewardEvent`.
 *
 * @since 1.0.0
 */
public class VoteRewardEvent(
    public val player: PlayerRef,
    /** The site's service name. */
    public val site: String,
    /** The names of the rewards from xVote's config about to be given; change it to change that. */
    public val rewards: MutableList<String>,
) : CancellableEvent() {
    public companion object {
        @JvmField
        public val TYPE: EventType<VoteRewardEvent> = EventType.of("xvote:reward")
    }
}

/**
 * A player was given a vote's rewards, here. On the player's thread, only when something listens.
 * On Bukkit it's also `co.xenastudios.xvote.api.bukkit.BukkitVoteRewardedEvent`.
 *
 * @since 2.0.0
 */
public class VoteRewardedEvent(
    public val player: PlayerRef,
    /** The site's service name. */
    public val site: String,
    /** The rewards given (each still rolled its own chance). */
    public val rewards: List<String>,
) : Event {
    public companion object {
        @JvmField
        public val TYPE: EventType<VoteRewardedEvent> = EventType.of("xvote:rewarded")
    }
}

/**
 * The vote party is about to reward [players] on this server: fired on every server of a network,
 * each for its own players. Take players out to leave them out, or cancel it to give this server's
 * players nothing; the party's count starts again either way. On the global thread, only when
 * something listens. On Bukkit it's also `co.xenastudios.xvote.api.bukkit.BukkitVotePartyEvent`.
 *
 * @since 1.0.0
 */
public class VotePartyEvent(
    /** Who is about to be rewarded: everyone online, or everyone online who voted since the last party. */
    public val players: MutableList<PlayerRef>,
) : CancellableEvent() {
    public companion object {
        @JvmField
        public val TYPE: EventType<VotePartyEvent> = EventType.of("xvote:party")
    }
}
