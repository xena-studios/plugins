package co.xenastudios.xplots.api.event

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.event.CancellableEvent
import co.xenastudios.core.api.event.Event
import co.xenastudios.core.api.event.EventType
import co.xenastudios.xplots.api.EndReason
import co.xenastudios.xplots.api.PlotInfo
import co.xenastudios.xplots.api.RentKind
import kotlin.jvm.JvmField

/*
 * xPlots' events. Each change fires its pre-event first (cancellable: cancel it and nothing
 * changes, nothing is charged) and its post-event once it's made. Each is fired only when
 * something listens, on the thread making the change: the player's (a command, a click, a menu)
 * or the global thread (expiry, renewals, staff from the console). On Bukkit each is also a
 * Bukkit event in co.xenastudios.xplots.api.bukkit, so Skript and other plugins see them.
 */

/**
 * [player] is about to rent the free [plot] for [price] (0 when staff give it with `setowner`).
 * Cancel it to refuse: nothing is charged.
 *
 * @since 2.0.0
 */
public class PlotClaimEvent(
    public val player: PlayerRef,
    /** Their name. */
    public val name: String,
    /** The plot, as it is now: free. */
    public val plot: PlotInfo,
    public val price: Double,
) : CancellableEvent() {
    public companion object {
        @JvmField
        public val TYPE: EventType<PlotClaimEvent> = EventType.of("xplots:claim")
    }
}

/** [player] rented [plot]: the plot as it is now, theirs. @since 2.0.0 */
public class PlotClaimedEvent(
    public val player: PlayerRef,
    public val name: String,
    public val plot: PlotInfo,
    /** What they paid. */
    public val price: Double,
) : Event {
    public companion object {
        @JvmField
        public val TYPE: EventType<PlotClaimedEvent> = EventType.of("xplots:claimed")
    }
}

/**
 * The rent of [plot] is about to end, for [reason]. Cancel it to keep the plot rented: for
 * [EndReason.EXPIRED], extend it as well ([co.xenastudios.xplots.api.XPlotsApi.extend]), or
 * it's asked again at the next check.
 *
 * @since 2.0.0
 */
public class PlotUnclaimEvent(
    /** The plot as it is now, still rented. */
    public val plot: PlotInfo,
    public val reason: EndReason,
) : CancellableEvent() {
    public companion object {
        @JvmField
        public val TYPE: EventType<PlotUnclaimEvent> = EventType.of("xplots:unclaim")
    }
}

/** The rent of a plot ended: [plot] as it was just before, with its old owner and members. @since 2.0.0 */
public class PlotUnclaimedEvent(
    public val plot: PlotInfo,
    public val reason: EndReason,
) : Event {
    public companion object {
        @JvmField
        public val TYPE: EventType<PlotUnclaimedEvent> = EventType.of("xplots:unclaimed")
    }
}

/**
 * [member] is about to be added to [plot]'s members, by its owner or by staff ([byStaff], who
 * aren't held to its slots). Cancel it to refuse.
 *
 * @since 2.0.0
 */
public class MemberAddEvent(
    public val plot: PlotInfo,
    public val member: PlayerRef,
    public val name: String,
    public val byStaff: Boolean,
) : CancellableEvent() {
    public companion object {
        @JvmField
        public val TYPE: EventType<MemberAddEvent> = EventType.of("xplots:member_add")
    }
}

/** [member] was added: [plot] as it is now. @since 2.0.0 */
public class MemberAddedEvent(
    public val plot: PlotInfo,
    public val member: PlayerRef,
    public val name: String,
) : Event {
    public companion object {
        @JvmField
        public val TYPE: EventType<MemberAddedEvent> = EventType.of("xplots:member_added")
    }
}

/** [member] is about to be removed from [plot]'s members. Cancel it to keep them. @since 2.0.0 */
public class MemberRemoveEvent(
    public val plot: PlotInfo,
    public val member: PlayerRef,
    public val name: String,
    public val byStaff: Boolean,
) : CancellableEvent() {
    public companion object {
        @JvmField
        public val TYPE: EventType<MemberRemoveEvent> = EventType.of("xplots:member_remove")
    }
}

/** [member] was removed: [plot] as it is now. @since 2.0.0 */
public class MemberRemovedEvent(
    public val plot: PlotInfo,
    public val member: PlayerRef,
    public val name: String,
) : Event {
    public companion object {
        @JvmField
        public val TYPE: EventType<MemberRemovedEvent> = EventType.of("xplots:member_removed")
    }
}

/**
 * [player] is about to pay [price] for [kind] on [plot]: [periods] of rent ahead, a renewal
 * (the owner may be offline), or a member slot. Cancel it and nothing is charged or changed.
 * Renting a free plot fires [PlotClaimEvent] instead.
 *
 * @since 2.0.0
 */
public class RentPayEvent(
    public val player: PlayerRef,
    public val plot: PlotInfo,
    public val kind: RentKind,
    /** How many periods: 1 to 3 for [RentKind.PAY], 1 for a renewal, 0 for a slot. */
    public val periods: Int,
    public val price: Double,
) : CancellableEvent() {
    public companion object {
        @JvmField
        public val TYPE: EventType<RentPayEvent> = EventType.of("xplots:rent_pay")
    }
}

/** [player] paid [price] for [kind]: [plot] as it is now, with its new time or slots. @since 2.0.0 */
public class RentPaidEvent(
    public val player: PlayerRef,
    public val plot: PlotInfo,
    public val kind: RentKind,
    public val periods: Int,
    public val price: Double,
) : Event {
    public companion object {
        @JvmField
        public val TYPE: EventType<RentPaidEvent> = EventType.of("xplots:rent_paid")
    }
}
