package co.xenastudios.xplots.api.bukkit

import co.xenastudios.xplots.api.event.MemberAddEvent
import co.xenastudios.xplots.api.event.MemberAddedEvent
import co.xenastudios.xplots.api.event.MemberRemoveEvent
import co.xenastudios.xplots.api.event.MemberRemovedEvent
import co.xenastudios.xplots.api.event.PlotClaimEvent
import co.xenastudios.xplots.api.event.PlotClaimedEvent
import co.xenastudios.xplots.api.event.PlotUnclaimEvent
import co.xenastudios.xplots.api.event.PlotUnclaimedEvent
import co.xenastudios.xplots.api.event.RentPaidEvent
import co.xenastudios.xplots.api.event.RentPayEvent
import org.bukkit.Bukkit
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

/*
 * xPlots' events as Bukkit events, for plugins and Skript scripts listening the Bukkit way: each
 * wraps the API's own event ([getEvent]), the same on every platform, and cancelling one cancels
 * that. Fired where xPlots fires its own: on the thread making the change, which may be off the
 * server thread (a payment the economy confirmed later), so each says whether it's async.
 */

/** [PlotClaimEvent] as a Bukkit event: a player is about to rent a free plot (or staff give one); cancelling it refuses, and nothing is charged. @since 2.0.0 */
public class BukkitPlotClaimEvent(private val event: PlotClaimEvent) : Event(!Bukkit.isPrimaryThread()), Cancellable {
    /** xPlots' own event: the plot, the player and the rest. */
    public fun getEvent(): PlotClaimEvent = event

    override fun isCancelled(): Boolean = event.isCancelled

    override fun setCancelled(cancel: Boolean) {
        event.isCancelled = cancel
    }

    override fun getHandlers(): HandlerList = HANDLERS

    public companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        public fun getHandlerList(): HandlerList = HANDLERS
    }
}

/** [PlotClaimedEvent] as a Bukkit event: a player rented a plot. @since 2.0.0 */
public class BukkitPlotClaimedEvent(private val event: PlotClaimedEvent) : Event(!Bukkit.isPrimaryThread()) {
    /** xPlots' own event: the plot, the player and the rest. */
    public fun getEvent(): PlotClaimedEvent = event

    override fun getHandlers(): HandlerList = HANDLERS

    public companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        public fun getHandlerList(): HandlerList = HANDLERS
    }
}

/** [PlotUnclaimEvent] as a Bukkit event: a plot's rent is about to end; cancelling it keeps it rented. @since 2.0.0 */
public class BukkitPlotUnclaimEvent(private val event: PlotUnclaimEvent) : Event(!Bukkit.isPrimaryThread()), Cancellable {
    /** xPlots' own event: the plot, the player and the rest. */
    public fun getEvent(): PlotUnclaimEvent = event

    override fun isCancelled(): Boolean = event.isCancelled

    override fun setCancelled(cancel: Boolean) {
        event.isCancelled = cancel
    }

    override fun getHandlers(): HandlerList = HANDLERS

    public companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        public fun getHandlerList(): HandlerList = HANDLERS
    }
}

/** [PlotUnclaimedEvent] as a Bukkit event: a plot's rent ended. @since 2.0.0 */
public class BukkitPlotUnclaimedEvent(private val event: PlotUnclaimedEvent) : Event(!Bukkit.isPrimaryThread()) {
    /** xPlots' own event: the plot, the player and the rest. */
    public fun getEvent(): PlotUnclaimedEvent = event

    override fun getHandlers(): HandlerList = HANDLERS

    public companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        public fun getHandlerList(): HandlerList = HANDLERS
    }
}

/** [MemberAddEvent] as a Bukkit event: a member is about to be added; cancelling it refuses. @since 2.0.0 */
public class BukkitMemberAddEvent(private val event: MemberAddEvent) : Event(!Bukkit.isPrimaryThread()), Cancellable {
    /** xPlots' own event: the plot, the player and the rest. */
    public fun getEvent(): MemberAddEvent = event

    override fun isCancelled(): Boolean = event.isCancelled

    override fun setCancelled(cancel: Boolean) {
        event.isCancelled = cancel
    }

    override fun getHandlers(): HandlerList = HANDLERS

    public companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        public fun getHandlerList(): HandlerList = HANDLERS
    }
}

/** [MemberAddedEvent] as a Bukkit event: a member was added. @since 2.0.0 */
public class BukkitMemberAddedEvent(private val event: MemberAddedEvent) : Event(!Bukkit.isPrimaryThread()) {
    /** xPlots' own event: the plot, the player and the rest. */
    public fun getEvent(): MemberAddedEvent = event

    override fun getHandlers(): HandlerList = HANDLERS

    public companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        public fun getHandlerList(): HandlerList = HANDLERS
    }
}

/** [MemberRemoveEvent] as a Bukkit event: a member is about to be removed; cancelling it keeps them. @since 2.0.0 */
public class BukkitMemberRemoveEvent(private val event: MemberRemoveEvent) : Event(!Bukkit.isPrimaryThread()), Cancellable {
    /** xPlots' own event: the plot, the player and the rest. */
    public fun getEvent(): MemberRemoveEvent = event

    override fun isCancelled(): Boolean = event.isCancelled

    override fun setCancelled(cancel: Boolean) {
        event.isCancelled = cancel
    }

    override fun getHandlers(): HandlerList = HANDLERS

    public companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        public fun getHandlerList(): HandlerList = HANDLERS
    }
}

/** [MemberRemovedEvent] as a Bukkit event: a member was removed. @since 2.0.0 */
public class BukkitMemberRemovedEvent(private val event: MemberRemovedEvent) : Event(!Bukkit.isPrimaryThread()) {
    /** xPlots' own event: the plot, the player and the rest. */
    public fun getEvent(): MemberRemovedEvent = event

    override fun getHandlers(): HandlerList = HANDLERS

    public companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        public fun getHandlerList(): HandlerList = HANDLERS
    }
}

/** [RentPayEvent] as a Bukkit event: rent is about to be paid ahead, renewed or a slot bought; cancelling it charges nothing. @since 2.0.0 */
public class BukkitRentPayEvent(private val event: RentPayEvent) : Event(!Bukkit.isPrimaryThread()), Cancellable {
    /** xPlots' own event: the plot, the player and the rest. */
    public fun getEvent(): RentPayEvent = event

    override fun isCancelled(): Boolean = event.isCancelled

    override fun setCancelled(cancel: Boolean) {
        event.isCancelled = cancel
    }

    override fun getHandlers(): HandlerList = HANDLERS

    public companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        public fun getHandlerList(): HandlerList = HANDLERS
    }
}

/** [RentPaidEvent] as a Bukkit event: rent was paid, renewed or a slot bought. @since 2.0.0 */
public class BukkitRentPaidEvent(private val event: RentPaidEvent) : Event(!Bukkit.isPrimaryThread()) {
    /** xPlots' own event: the plot, the player and the rest. */
    public fun getEvent(): RentPaidEvent = event

    override fun getHandlers(): HandlerList = HANDLERS

    public companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        public fun getHandlerList(): HandlerList = HANDLERS
    }
}
