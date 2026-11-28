package co.xenastudios.xplots

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.onSettled
import co.xenastudios.core.api.pendingOf
import co.xenastudios.core.common.command.CommandSource
import co.xenastudios.core.common.config.Config
import co.xenastudios.core.common.i18n.MessageKey
import co.xenastudios.core.common.locked
import co.xenastudios.core.common.time.Durations
import co.xenastudios.xplots.api.ChangeResult
import co.xenastudios.xplots.api.EndReason
import co.xenastudios.xplots.api.RentKind
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
import co.xenastudios.xplots.config.XPlotsConfig
import co.xenastudios.xplots.model.Lease
import co.xenastudios.xplots.model.Plot
import co.xenastudios.xplots.model.PlotSettings
import co.xenastudios.xplots.model.Settings
import co.xenastudios.xplots.model.info
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.minutes

/** Where a reply goes: a player, or whoever ran a command. */
public fun interface Teller {
    public fun tell(message: MessageKey, vararg placeholders: Pair<String, Any>)
}

/** Replies to [player], in their language. */
internal class PlayerTeller(private val plugin: XPlotsPlugin, private val player: PlayerRef) : Teller {
    override fun tell(message: MessageKey, vararg placeholders: Pair<String, Any>) = plugin.texts.send(player, message, *placeholders)
}

/** Replies to whoever ran a command. */
internal class SourceTeller(private val plugin: XPlotsPlugin, private val source: CommandSource) : Teller {
    override fun tell(message: MessageKey, vararg placeholders: Pair<String, Any>) = plugin.texts.reply(source, message, *placeholders)
}

/**
 * What rent is paid with: the server's economy (xMoney's or Vault's) or an item, as the config
 * says now. Item currency is taken from and given to the player's inventory, on their thread.
 */
public class Wallet internal constructor(private val plugin: XPlotsPlugin) {
    private val config: Config get() = plugin.config

    /** The item, when `currency.type` is `item`. */
    public fun item(): ItemCurrency? {
        if (config[XPlotsConfig.currency.type] != XPlotsConfig.CurrencyType.ITEM) return null
        val item = XPlotsConfig.currency.item
        return ItemCurrency(config[item.material], config[item.itemName], config[item.customModelData])
    }

    /** The economy, when rent is paid with one and one is installed. */
    public fun economy(): Economy? = if (item() != null) null else plugin.hooks.economy()

    /** Whether payments can be taken now. */
    public fun ready(): Boolean = item() != null || economy() != null

    /** Why payments can't be taken, for the console, or `null` if they can. */
    public fun problem(): String? = if (ready()) null else
        "currency.type is economy, but no economy is installed: install xMoney, or Vault and an economy plugin (such as EssentialsX), or set currency.type: item."

    /** Whether an owner who's offline can be charged: an economy's balances are by UUID, an item is in an inventory. */
    public fun chargesOffline(): Boolean = economy() != null

    /** [amount] as players see it: `$128.00`, `128 Coins`. */
    public fun format(amount: Double): String {
        if (item() != null) {
            val n = whole(amount)
            val item = XPlotsConfig.currency.item
            return "$n ${if (n == 1) config[item.singular] else config[item.plural]}"
        }
        return economy()?.format(amount) ?: Settings.strip(amount)
    }

    /** Takes [amount] from [player], settling with whether it was taken. On the player's thread. */
    public fun take(player: PlayerRef, amount: Double, reason: String): Pending<Boolean> {
        if (amount <= 0) return pendingOf(true)
        val item = item()
        if (item != null) return pendingOf(plugin.server.takeItems(player, item, whole(amount)))
        return economy()?.withdraw(player, amount, reason) ?: pendingOf(false)
    }

    /** Gives [amount] back to [player], as for a refund. */
    public fun give(player: PlayerRef, amount: Double, reason: String) {
        if (amount <= 0) return
        val item = item()
        if (item != null) {
            // Whole items only, rounded down: a refund never makes money.
            plugin.server.giveItems(player, item, kotlin.math.floor(amount).toInt())
            return
        }
        economy()?.deposit(player, amount, reason)
    }

    private fun whole(amount: Double): Int = if (amount <= 0) 0 else kotlin.math.ceil(amount - 1e-9).coerceAtMost(Int.MAX_VALUE.toDouble()).toInt()
}

/**
 * Everything that changes who has a plot: renting, paying, member slots, members, abandoning,
 * staff's changes, and rent running out. Each change is made in memory under the plugin's lock,
 * saved in the background, and shown on the sign. A payment is taken before the change and given
 * back if the change can't be made by the time it's taken (someone else rented the plot first).
 */
public class Rentals internal constructor(private val plugin: XPlotsPlugin) {
    public val wallet: Wallet = Wallet(plugin)

    @Volatile
    private var lastProblem: String? = null

    @Volatile
    private var defaults: Pair<Config, PlotSettings>? = null

    /** The config's `plots`, read once per config. */
    public fun defaults(): PlotSettings {
        val config = plugin.config
        defaults?.let { if (it.first === config) return it.second }
        return XPlotsConfig.plots.read(config).also { defaults = config to it }
    }

    public fun settings(plot: Plot): PlotSettings = plot.field.settings(defaults())

    /** What [player] pays for [kind] on [plot]: the field's price, times [periods], then the add-ons'. */
    public fun price(player: PlayerRef, plot: Plot, kind: RentKind, periods: Int): Double {
        val s = settings(plot)
        val base = when (kind) {
            RentKind.RENT, RentKind.RENEW -> s.price
            RentKind.PAY -> s.price * periods
            RentKind.SLOT -> s.slotPrice
        }
        return plugin.addonsIfMade?.price(player, plot, kind, base) ?: base
    }

    /** A length of time to the minute: `2d 4h`, `45m`, `<1m`. Signs show this, so they only change once a minute. */
    public fun time(millis: Long): String {
        val minutes = millis / 60_000
        return if (minutes < 1) "<1m" else Durations.format(minutes.minutes)
    }

    /** Every detail of [plot] as messages, signs and menus show it, for [viewer] (prices after their discount). */
    public fun placeholders(plot: Plot, viewer: PlayerRef?): Array<Pair<String, Any>> {
        val s = settings(plot)
        val lease = plot.lease
        val price = if (viewer != null) price(viewer, plot, RentKind.RENT, 1) else s.price
        return arrayOf(
            "plot" to plot.id,
            "price" to wallet.format(price),
            "period" to time(s.period.inWholeMilliseconds),
            "owner" to (lease?.ownerName ?: ""),
            "time" to (lease?.let { time(it.remaining(plugin.now())) } ?: ""),
            "slots" to (lease?.members?.size ?: 0).toString(),
            "max-slots" to (lease?.slots ?: s.startingSlots).toString(),
            "size" to plot.box.size,
            "members" to (lease?.members?.values?.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: plugin.texts.word(viewer, XPlotsMessages.noMembers)),
        )
    }

    /** How many plots [player] may rent at once: `max-plots`, or their highest `xplots.limit.<n>`. On their thread. */
    public fun limit(player: PlayerRef): Int = maxOf(plugin.config[XPlotsConfig.maxPlots], plugin.server.highestNumbered(player, XPlotsPlugin.LIMIT_PREFIX))

    public fun owns(player: PlayerRef, plot: Plot): Boolean = plot.lease?.owner == player

    private fun walletReady(tell: Teller, amount: Double): Boolean {
        if (amount <= 0 || wallet.ready()) return true
        val problem = wallet.problem()
        if (problem != null && problem != lastProblem) plugin.log.warn(problem)
        lastProblem = problem
        tell.tell(XPlotsMessages.unavailable)
        return false
    }

    private fun ready(tell: Teller): Boolean {
        if (plugin.isReady) return true
        tell.tell(XPlotsMessages.loading)
        return false
    }

    // ---- players ----

    /** Rents the free [plot] to [player] for one period. On their thread. */
    public fun rent(player: PlayerRef, name: String, plot: Plot) {
        val tell = PlayerTeller(plugin, player)
        if (!ready(tell)) return
        if (!plot.isFree || plugin.isResetting(plot)) {
            tell.tell(if (plot.isFree) XPlotsMessages.unavailable else XPlotsMessages.taken)
            return
        }
        val limit = limit(player)
        if (plugin.plots.owned(player).size >= limit) {
            tell.tell(XPlotsMessages.limitReached, "max" to (limit.toString() + if (limit == 1) " plot" else " plots"))
            return
        }
        val price = price(player, plot, RentKind.RENT, 1)
        if (!walletReady(tell, price)) return
        if (plugin.fire(PlotClaimEvent.TYPE) { PlotClaimEvent(player, name, info(plot, false), price) }?.isCancelled == true) {
            tell.tell(XPlotsMessages.unavailable)
            return
        }
        wallet.take(player, price, "rent for ${plot.id}").onSettled { taken, _ ->
            if (taken != true) {
                tell.tell(XPlotsMessages.cantAfford, "price" to wallet.format(price))
                return@onSettled
            }
            val rented = locked(plugin.lock) {
                if (!plot.isFree || plugin.isResetting(plot) || plugin.plots.plot(plot.id) !== plot) return@locked false
                val s = settings(plot)
                val now = plugin.now()
                val expires = now + s.period.inWholeMilliseconds
                plugin.plots.lease(plot, Lease(player, name, expires, s.startingSlots, Lease.warningFor(expires - now), emptyMap()))
                true
            }
            if (!rented) {
                wallet.give(player, price, "refund for ${plot.id}")
                tell.tell(XPlotsMessages.taken)
                return@onSettled
            }
            plugin.renewals.rented(plot)
            saved(plot)
            tell.tell(XPlotsMessages.rented, *placeholders(plot, player), "price" to wallet.format(price))
            plugin.fire(PlotClaimedEvent.TYPE) { PlotClaimedEvent(player, name, info(plot, false), price) }
        }
    }

    /** Whether paying [periods] more would stay within `max-prepaid` (a minute's grace, so a click right at the cap counts). */
    public fun canPay(plot: Plot, periods: Int): Boolean {
        val lease = plot.lease ?: return false
        val s = settings(plot)
        val now = plugin.now()
        val after = maxOf(lease.expires, now) + periods * s.period.inWholeMilliseconds
        return after - now <= s.maxPrepaid.inWholeMilliseconds + 60_000
    }

    /** The owner pays [periods] of rent ahead. On their thread. */
    public fun payRent(player: PlayerRef, plot: Plot, periods: Int, then: (() -> Unit)? = null) {
        val tell = PlayerTeller(plugin, player)
        if (!owns(player, plot)) return tell.tell(XPlotsMessages.notYours)
        val s = settings(plot)
        if (!canPay(plot, periods)) return tell.tell(XPlotsMessages.prepaidFull, "max" to time(s.maxPrepaid.inWholeMilliseconds))
        val price = price(player, plot, RentKind.PAY, periods)
        if (!walletReady(tell, price)) return
        if (plugin.fire(RentPayEvent.TYPE) { RentPayEvent(player, info(plot, false), RentKind.PAY, periods, price) }?.isCancelled == true) return tell.tell(XPlotsMessages.unavailable)
        wallet.take(player, price, "rent for ${plot.id}").onSettled { taken, _ ->
            if (taken != true) {
                tell.tell(XPlotsMessages.cantAfford, "price" to wallet.format(price))
                then?.invoke()
                return@onSettled
            }
            val paid = locked(plugin.lock) {
                val lease = plot.lease
                if (lease == null || lease.owner != player) return@locked false
                val now = plugin.now()
                val expires = maxOf(lease.expires, now) + periods * s.period.inWholeMilliseconds
                plugin.plots.lease(plot, lease.with(expires = expires, warned = Lease.warningFor(expires - now)))
                true
            }
            if (!paid) {
                wallet.give(player, price, "refund for ${plot.id}")
                tell.tell(XPlotsMessages.notYours)
            } else {
                saved(plot)
                tell.tell(XPlotsMessages.paid, *placeholders(plot, player), "price" to wallet.format(price))
                plugin.fire(RentPaidEvent.TYPE) { RentPaidEvent(player, info(plot, false), RentKind.PAY, periods, price) }
            }
            then?.invoke()
        }
    }

    /** The owner buys one more member slot. On their thread. */
    public fun buySlot(player: PlayerRef, plot: Plot, then: (() -> Unit)? = null) {
        val tell = PlayerTeller(plugin, player)
        if (!owns(player, plot)) return tell.tell(XPlotsMessages.notYours)
        val s = settings(plot)
        if ((plot.lease?.slots ?: 0) >= s.maxSlots) return tell.tell(XPlotsMessages.maxSlots, "plot" to plot.id)
        val price = price(player, plot, RentKind.SLOT, 0)
        if (!walletReady(tell, price)) return
        if (plugin.fire(RentPayEvent.TYPE) { RentPayEvent(player, info(plot, false), RentKind.SLOT, 0, price) }?.isCancelled == true) return tell.tell(XPlotsMessages.unavailable)
        wallet.take(player, price, "member slot for ${plot.id}").onSettled { taken, _ ->
            if (taken != true) {
                tell.tell(XPlotsMessages.cantAfford, "price" to wallet.format(price))
                then?.invoke()
                return@onSettled
            }
            val slots = locked(plugin.lock) {
                val lease = plot.lease
                if (lease == null || lease.owner != player || lease.slots >= s.maxSlots) return@locked -1
                plugin.plots.lease(plot, lease.with(slots = lease.slots + 1))
                lease.slots + 1
            }
            if (slots < 0) {
                wallet.give(player, price, "refund for ${plot.id}")
                tell.tell(XPlotsMessages.maxSlots, "plot" to plot.id)
            } else {
                saved(plot)
                tell.tell(XPlotsMessages.slotBought, "plot" to plot.id, "slots" to slots.toString())
                plugin.fire(RentPaidEvent.TYPE) { RentPaidEvent(player, info(plot, false), RentKind.SLOT, 0, price) }
            }
            then?.invoke()
        }
    }

    /**
     * Adds [member] ([name]) to [plot]. Staff skip the slot check. [tell] is told how it went; the
     * new member is told if they're online.
     */
    public fun addMember(tell: Teller, by: PlayerRef?, plot: Plot, member: PlayerRef, name: String, staff: Boolean): ChangeResult {
        val lease = plot.lease ?: return ChangeResult.NOT_RENTED.also { tell.tell(XPlotsMessages.notYours) }
        if (lease.owner == member) return ChangeResult.ALREADY.also { tell.tell(XPlotsMessages.notYourself) }
        if (member in lease.members) return ChangeResult.ALREADY.also { tell.tell(XPlotsMessages.alreadyMember, "player" to name) }
        if (!staff && lease.members.size >= lease.slots) return ChangeResult.NOT_RENTED.also { tell.tell(XPlotsMessages.slotsFull, "plot" to plot.id) }
        if (plugin.fire(MemberAddEvent.TYPE) { MemberAddEvent(info(plot, false), member, name, staff) }?.isCancelled == true) {
            tell.tell(XPlotsMessages.unavailable)
            return ChangeResult.CANCELLED
        }
        val added = locked(plugin.lock) {
            val now = plot.lease
            if (now == null || now.owner != lease.owner || member in now.members) return@locked false
            plugin.plots.lease(plot, now.with(members = now.members + (member to name)))
            true
        }
        if (!added) return ChangeResult.ALREADY.also { tell.tell(XPlotsMessages.alreadyMember, "player" to name) }
        saved(plot)
        tell.tell(XPlotsMessages.memberAdded, "player" to name, "plot" to plot.id)
        if (member != by && plugin.server.isOnline(member)) {
            plugin.texts.send(member, XPlotsMessages.addedYou, "owner" to lease.ownerName, "plot" to plot.id)
        }
        plugin.fire(MemberAddedEvent.TYPE) { MemberAddedEvent(info(plot, false), member, name) }
        return ChangeResult.DONE
    }

    public fun removeMember(tell: Teller, by: PlayerRef?, plot: Plot, member: PlayerRef, staff: Boolean): ChangeResult {
        val lease = plot.lease
        val name = lease?.members?.get(member)
        if (lease == null || name == null) {
            tell.tell(XPlotsMessages.notMember, "player" to (plugin.server.name(member) ?: member.toString()))
            return ChangeResult.NOT_MEMBER
        }
        if (plugin.fire(MemberRemoveEvent.TYPE) { MemberRemoveEvent(info(plot, false), member, name, staff) }?.isCancelled == true) {
            tell.tell(XPlotsMessages.unavailable)
            return ChangeResult.CANCELLED
        }
        locked(plugin.lock) {
            val now = plot.lease ?: return@locked
            plugin.plots.lease(plot, now.with(members = now.members - member))
        }
        saved(plot)
        tell.tell(XPlotsMessages.memberRemoved, "player" to name, "plot" to plot.id)
        if (member != by && plugin.server.isOnline(member)) plugin.texts.send(member, XPlotsMessages.removedYou, "plot" to plot.id)
        plugin.fire(MemberRemovedEvent.TYPE) { MemberRemovedEvent(info(plot, false), member, name) }
        return ChangeResult.DONE
    }

    /** The owner gives the plot up, getting back the refund the field allows. */
    public fun abandon(player: PlayerRef, plot: Plot) {
        val tell = PlayerTeller(plugin, player)
        val lease = plot.lease
        if (lease == null || lease.owner != player) return tell.tell(XPlotsMessages.notYours)
        val s = settings(plot)
        val unused = lease.remaining(plugin.now()) / s.period.inWholeMilliseconds.toDouble()
        val refund = kotlin.math.floor(unused * s.price * s.abandonRefund / 100.0 * 100) / 100
        if (!end(plot, EndReason.ABANDONED)) return tell.tell(XPlotsMessages.unavailable)
        tell.tell(XPlotsMessages.abandoned, "plot" to plot.id)
        if (refund > 0 && wallet.ready()) {
            wallet.give(player, refund, "refund for ${plot.id}")
            tell.tell(XPlotsMessages.refunded, "price" to wallet.format(refund))
        }
    }

    /**
     * Ends the plot's rent now: it's free, its members are gone, and it's put back if the field
     * says so. `false` if a listener kept it rented, or it already was free.
     */
    public fun end(plot: Plot, reason: EndReason): Boolean {
        val before = plot.lease ?: return false
        val shown = info(plot, plugin.isResetting(plot))
        if (plugin.fire(PlotUnclaimEvent.TYPE) { PlotUnclaimEvent(shown, reason) }?.isCancelled == true) return false
        val ended = locked(plugin.lock) {
            if (plot.lease !== before) return@locked false
            plugin.plots.lease(plot, null)
            true
        }
        if (!ended) return false
        plugin.renewals.ended(plot)
        saved(plot)
        if (settings(plot).reset) plugin.resets.reset(plot, SignUpdate(plugin, plot))
        plugin.fire(PlotUnclaimedEvent.TYPE) { PlotUnclaimedEvent(shown, reason) }
        return true
    }

    /** Rent ran out: ends it and tells the owner, if they're online. */
    public fun expire(plot: Plot) {
        val owner = plot.lease?.owner ?: return
        if (end(plot, EndReason.EXPIRED) && plugin.server.isOnline(owner)) plugin.texts.send(owner, XPlotsMessages.expired, "plot" to plot.id)
    }

    /**
     * Staff give a plot to a player. A free plot is rented to them for one period, at no cost
     * (with the claim events); a rented one is handed over with its time, slots and members.
     */
    public fun setOwner(plot: Plot, owner: PlayerRef, name: String): ChangeResult {
        if (!plugin.isReady) return ChangeResult.NOT_READY
        val old = plot.lease
        if (old == null && plugin.fire(PlotClaimEvent.TYPE) { PlotClaimEvent(owner, name, info(plot, false), 0.0) }?.isCancelled == true) return ChangeResult.CANCELLED
        locked(plugin.lock) {
            val now = plot.lease
            val lease = if (now == null) {
                val s = settings(plot)
                val time = plugin.now()
                val expires = time + s.period.inWholeMilliseconds
                Lease(owner, name, expires, s.startingSlots, Lease.warningFor(expires - time), emptyMap())
            } else {
                Lease(owner, name, now.expires, now.slots, now.warned, now.members - owner)
            }
            plugin.plots.lease(plot, lease)
        }
        if (old == null) plugin.renewals.rented(plot)
        saved(plot)
        if (old == null) plugin.fire(PlotClaimedEvent.TYPE) { PlotClaimedEvent(owner, name, info(plot, false), 0.0) }
        return ChangeResult.DONE
    }

    /** Adds [millis] to a rented plot's time, free of charge; negative takes it away, down to now. */
    public fun extend(plot: Plot, millis: Long): ChangeResult {
        val done = locked(plugin.lock) {
            val lease = plot.lease ?: return@locked false
            val now = plugin.now()
            val expires = maxOf(now, lease.expires + millis)
            plugin.plots.lease(plot, lease.with(expires = expires, warned = Lease.warningFor(expires - now)))
            true
        }
        if (!done) return ChangeResult.NOT_RENTED
        saved(plot)
        return ChangeResult.DONE
    }

    /** The owner's name as last seen, from a join. */
    public fun rename(plot: Plot, name: String) {
        val changed = locked(plugin.lock) {
            val lease = plot.lease ?: return@locked false
            if (lease.ownerName == name) return@locked false
            plugin.plots.lease(plot, lease.with(ownerName = name))
            true
        }
        if (changed) saved(plot)
    }

    /** After any change to a lease: save it, show it, and time the next expiry. */
    public fun saved(plot: Plot) {
        plugin.store?.saveLease(plot)
        plugin.signs.update(plot)
        plugin.expiry.reschedule()
    }
}

/** Brings a plot's sign up to date once it's been put back: one class, not a lambda each time. */
internal class SignUpdate(private val plugin: XPlotsPlugin, private val plot: Plot) : () -> Unit {
    override fun invoke() = plugin.signs.update(plot)
}

/** The API's changes, in a class of their own, loaded with the first plugin to call one. */
internal object Api {
    private val QUIET = Teller { _, _ -> }

    private fun plot(plugin: XPlotsPlugin, id: String): Plot? = plugin.plots.plot(id.lowercase())

    fun setOwner(plugin: XPlotsPlugin, id: String, player: PlayerRef, name: String): Pending<ChangeResult> {
        if (!plugin.isReady) return pendingOf(ChangeResult.NOT_READY)
        val plot = plot(plugin, id) ?: return pendingOf(ChangeResult.NO_PLOT)
        return pendingOf(plugin.rentals.setOwner(plot, player, name))
    }

    fun evict(plugin: XPlotsPlugin, id: String): Pending<ChangeResult> {
        if (!plugin.isReady) return pendingOf(ChangeResult.NOT_READY)
        val plot = plot(plugin, id) ?: return pendingOf(ChangeResult.NO_PLOT)
        if (plot.isFree) return pendingOf(ChangeResult.NOT_RENTED)
        return pendingOf(if (plugin.rentals.end(plot, EndReason.EVICTED)) ChangeResult.DONE else ChangeResult.CANCELLED)
    }

    fun addMember(plugin: XPlotsPlugin, id: String, player: PlayerRef, name: String): Pending<ChangeResult> {
        if (!plugin.isReady) return pendingOf(ChangeResult.NOT_READY)
        val plot = plot(plugin, id) ?: return pendingOf(ChangeResult.NO_PLOT)
        return pendingOf(plugin.rentals.addMember(QUIET, null, plot, player, name, staff = true))
    }

    fun removeMember(plugin: XPlotsPlugin, id: String, player: PlayerRef): Pending<ChangeResult> {
        if (!plugin.isReady) return pendingOf(ChangeResult.NOT_READY)
        val plot = plot(plugin, id) ?: return pendingOf(ChangeResult.NO_PLOT)
        return pendingOf(plugin.rentals.removeMember(QUIET, null, plot, player, staff = true))
    }

    fun extend(plugin: XPlotsPlugin, id: String, millis: Long): Pending<ChangeResult> {
        if (!plugin.isReady) return pendingOf(ChangeResult.NOT_READY)
        val plot = plot(plugin, id) ?: return pendingOf(ChangeResult.NO_PLOT)
        return pendingOf(plugin.rentals.extend(plot, millis))
    }
}
