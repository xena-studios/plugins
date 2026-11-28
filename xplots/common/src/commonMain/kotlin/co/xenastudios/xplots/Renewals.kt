package co.xenastudios.xplots

import co.xenastudios.core.api.Location
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.onSettled
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.concurrentMap
import co.xenastudios.core.common.locked
import co.xenastudios.core.common.module.Module
import co.xenastudios.core.common.module.ModuleContext
import co.xenastudios.core.common.scheduler.Task
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.xplots.api.RentKind
import co.xenastudios.xplots.api.event.RentPaidEvent
import co.xenastudios.xplots.api.event.RentPayEvent
import co.xenastudios.xplots.model.Facing
import co.xenastudios.xplots.model.Lease
import co.xenastudios.xplots.model.Plot
import co.xenastudios.xplots.model.info
import co.xenastudios.xplots.store.Renewal
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Rent that renews on its own (`plots.auto-renew`), from the owner's balance, online or not: an
 * hour before a plot's rent runs out, one period is charged through the economy and the lease
 * extended. Each renewal is charged once at most, even across a crash:
 *
 * 1. The charge is written down first, in the `renewal` table, as the expiry it extends from,
 *    and nothing is taken until that write has committed.
 * 2. The money is taken, with that expiry in the ledger's reason (`rent for box-12 #<expiry>`).
 * 3. The lease's new expiry and the cleared charge are written in one transaction.
 *
 * A start that finds a charge still written down (the server stopped between 1 and 3) asks the
 * economy's ledger whether that payment was made: if it was, or the economy keeps no ledger to
 * ask (Vault), the lease is extended without charging again; if it wasn't, the charge is dropped
 * and the renewal tried again in its time. A renewal the balance doesn't cover is tried once per
 * expiry; the owner is told, and the plot runs out as it would have.
 */
public class Renewals internal constructor(private val plugin: XPlotsPlugin) {
    private val rows = concurrentMap<Plot, Renewal>().also { it.putAll(plugin.storedRenewals) }
    private val charging = HashSet<Plot>()
    private val failedFor = concurrentMap<Plot, Long>()

    /** Renewals made while their owner was away, told when they next join. */
    private val waiting = concurrentMap<PlayerRef, List<Pair<String, Array<Pair<String, Any>>>>>()

    /** Whether [plot]'s field offers renewals at all: `auto-renew`, with an economy that can charge an owner who's away. */
    public fun offered(plot: Plot): Boolean = plugin.rentals.settings(plot).autoRenew && plugin.rentals.wallet.item() == null

    /** Whether [plot] renews on its own: offered, and its owner hasn't turned it off. */
    public fun active(plot: Plot): Boolean = !plot.isFree && offered(plot) && rows[plot]?.auto != false

    /** A plot was just rented: renewals start on, if its field offers them. */
    internal fun rented(plot: Plot) {
        rows.remove(plot)
        failedFor.remove(plot)
    }

    /** A plot's rent ended: its renewal goes with its lease (Store.saveLease deletes the row). */
    internal fun ended(plot: Plot) {
        rows.remove(plot)
        failedFor.remove(plot)
    }

    /** The owner turns renewing on or off for their plot. */
    public fun toggle(player: PlayerRef, plot: Plot) {
        if (plot.lease?.owner != player || !offered(plot)) return
        val on = !active(plot)
        val row = Renewal(on, rows[plot]?.charge)
        rows[plot] = row
        plugin.store?.saveRenewal(plot, row)
        plugin.texts.send(player, if (on) XPlotsMessages.renewOn else XPlotsMessages.renewOff, "plot" to plot.id)
        plugin.expiry.reschedule()
    }

    /** When [plot]'s renewal is due, or `null` if it isn't one that renews. */
    internal fun dueAt(plot: Plot): Long? {
        val lease = plot.lease ?: return null
        if (!active(plot) || failedFor[plot] == lease.expires || locked(charging) { plot in charging }) return null
        return lease.expires - Lease.HOUR
    }

    /** Renews [plot] now, if it's due and isn't being charged already. On the global thread. */
    internal fun renew(plot: Plot) {
        val lease = plot.lease ?: return
        val from = lease.expires
        val economy = plugin.rentals.wallet.economy()
        if (economy == null) {
            plugin.log.warn("${plot.id} renews on its own, but there's no economy to charge (xMoney, or Vault with an economy plugin); it runs out as it would have.")
            failedFor[plot] = from
            return
        }
        val claimed = locked(charging) { if (plot in charging) false else charging.add(plot) }
        if (!claimed) return
        val price = plugin.rentals.price(lease.owner, plot, RentKind.RENEW, 1)
        if (plugin.fire(RentPayEvent.TYPE) { RentPayEvent(lease.owner, info(plot, false), RentKind.RENEW, 1, price) }?.isCancelled == true) {
            failedFor[plot] = from
            locked(charging) { charging.remove(plot) }
            return
        }
        val store = plugin.store
        if (store == null) {
            locked(charging) { charging.remove(plot) }
            return
        }
        val row = Renewal(true, from)
        rows[plot] = row
        store.saveRenewal(plot, row).onSettled { written, _ ->
            if (written !is Outcome.Ok) {
                // The charge couldn't be written down, so nothing is taken: tried again at the next check.
                locked(charging) { charging.remove(plot) }
                return@onSettled
            }
            if (price <= 0) return@onSettled settle(plot, lease.owner, from, price, true)
            economy.withdraw(lease.owner, price, "rent for ${plot.id} #$from").onSettled { taken, _ -> settle(plot, lease.owner, from, price, taken == true) }
        }
    }

    private fun settle(plot: Plot, owner: PlayerRef, from: Long, price: Double, paid: Boolean) {
        val extended = locked(plugin.lock) {
            val lease = plot.lease
            if (!paid || lease == null || lease.owner != owner || lease.expires != from) return@locked null
            val now = plugin.now()
            // From when it runs out, or from now if the server was off past that: a renewal always buys a whole period.
            val expires = maxOf(lease.expires, now) + plugin.rentals.settings(plot).period.inWholeMilliseconds
            lease.with(expires = expires, warned = Lease.warningFor(expires - now)).also { plugin.plots.lease(plot, it) }
        }
        val store = plugin.store
        when {
            extended != null -> {
                val row = Renewal(true, null)
                rows[plot] = row
                store?.saveLease(plot, extended, row)
                val shown = arrayOf<Pair<String, Any>>("plot" to plot.id, "price" to plugin.rentals.wallet.format(price), "time" to plugin.rentals.time(extended.remaining(plugin.now())))
                tellOrKeep(owner, XPlotsMessages.renewed.key, shown)
                plugin.fire(RentPaidEvent.TYPE) { RentPaidEvent(owner, info(plot, false), RentKind.RENEW, 1, price) }
            }
            paid -> {
                // Paid, but the plot ended or changed hands meanwhile: the money goes back.
                plugin.rentals.wallet.economy()?.deposit(owner, price, "refund for ${plot.id}")
                val row = rows[plot]
                if (row != null) {
                    val cleared = Renewal(row.auto, null)
                    rows[plot] = cleared
                    if (!plot.isFree) store?.saveRenewal(plot, cleared)
                }
            }
            else -> {
                failedFor[plot] = from
                val row = Renewal(true, null)
                rows[plot] = row
                if (!plot.isFree) store?.saveRenewal(plot, row)
                tellOrKeep(owner, XPlotsMessages.renewFailed.key, arrayOf("plot" to plot.id, "price" to plugin.rentals.wallet.format(price)))
            }
        }
        locked(charging) { charging.remove(plot) }
        plugin.signs.update(plot)
        plugin.expiry.reschedule()
    }

    private fun tellOrKeep(owner: PlayerRef, key: String, placeholders: Array<Pair<String, Any>>) {
        val message = XPlotsMessages.messages.first { it.key == key }
        if (plugin.server.isOnline(owner)) plugin.texts.send(owner, message, *placeholders)
        else waiting[owner] = (waiting[owner] ?: emptyList()) + (key to placeholders)
    }

    /** [player] joined: renewals made while they were away. */
    internal fun joined(player: PlayerRef) {
        val told = waiting.remove(player) ?: return
        for ((key, placeholders) in told) plugin.texts.send(player, XPlotsMessages.messages.first { it.key == key }, *placeholders)
    }

    /**
     * Charges a restart cut short: each written down but not cleared is settled once, as the
     * economy's ledger says. Once, as the plots go live.
     */
    internal fun settleUnfinished() {
        for ((plot, row) in rows.entries.toList()) {
            val from = row.charge ?: continue
            val lease = plot.lease
            if (lease == null) {
                rows.remove(plot)
                continue
            }
            if (lease.expires != from) {
                // Already extended in the same transaction that should have cleared it: only the mark is left.
                val cleared = Renewal(row.auto, null)
                rows[plot] = cleared
                plugin.store?.saveRenewal(plot, cleared)
                continue
            }
            val economy = plugin.rentals.wallet.economy()
            if (economy == null) {
                plugin.log.warn("${plot.id}'s renewal was being charged when the server stopped, and there's no economy to ask whether it was paid; it stays as it is until one is installed.")
                continue
            }
            locked(charging) { charging.add(plot) }
            economy.charged(lease.owner, "${plot.id} #$from").onSettled { found, _ ->
                plugin.platform.scheduler.run(Where.Global, action = Settled(this, plot, lease.owner, from, found))
            }
        }
    }

    private class Settled(private val renewals: Renewals, private val plot: Plot, private val owner: PlayerRef, private val from: Long, private val found: Boolean?) : (Task) -> Unit {
        override fun invoke(task: Task) = renewals.settled(plot, owner, from, found)
    }

    private fun settled(plot: Plot, owner: PlayerRef, from: Long, found: Boolean?) {
        if (found == false) {
            plugin.log.info("${plot.id}'s renewal was cut short before it was paid; it's tried again in its time.")
            val row = Renewal(rows[plot]?.auto ?: true, null)
            rows[plot] = row
            plugin.store?.saveRenewal(plot, row)
            locked(charging) { charging.remove(plot) }
            plugin.expiry.reschedule()
            return
        }
        plugin.log.info("${plot.id}'s renewal was cut short after it was ${if (found == true) "paid" else "charged (the economy keeps no ledger to ask)"}; its rent is extended without charging again.")
        settle(plot, owner, from, plugin.rentals.price(owner, plot, RentKind.RENEW, 1), true)
    }

    /** For `/xplots plot info` and debug. */
    internal fun describe(plot: Plot): String = if (!offered(plot)) "no (its field doesn't renew)" else if (active(plot)) "yes" else "no (turned off by its owner)"
}

/**
 * One timer, set for whatever's due next: a lease running out, its owner's warning a day or an
 * hour before, or a renewal. Nothing polls. When it fires it handles everything due, then sets
 * itself for the next, and never waits longer than 20 minutes, so lag or a changed clock can't
 * put it far off. Leases that ran out while the server was off are handled when it starts. A
 * warning due while its owner is offline is sent when they join instead. On the global thread.
 */
public class Expiry internal constructor(private val plugin: XPlotsPlugin) {
    @Volatile
    private var task: Task? = null

    @Volatile
    private var due = Long.MAX_VALUE

    @Volatile
    private var firing = false

    @Volatile
    private var on = false

    internal fun start() {
        on = true
        fire()
    }

    internal fun stop() {
        on = false
        task?.cancel()
        task = null
        due = Long.MAX_VALUE
    }

    /** Sets the timer for whatever's due next. Cheap: one pass over the rented plots. */
    public fun reschedule() {
        if (firing || !on) return
        val now = plugin.now()
        var next = Long.MAX_VALUE
        for (plot in plugin.plots.rented()) {
            val lease = plot.lease ?: continue
            next = minOf(next, lease.expires)
            val day = lease.expires - Lease.DAY
            val hour = lease.expires - Lease.HOUR
            if (lease.warned < Lease.WARNED_DAY && day > now) next = minOf(next, day)
            if (lease.warned < Lease.WARNED_HOUR && hour > now) next = minOf(next, hour)
            plugin.renewals.dueAt(plot)?.let { next = minOf(next, maxOf(it, now)) }
        }
        val old = task
        if (next == Long.MAX_VALUE) {
            old?.cancel()
            task = null
            due = Long.MAX_VALUE
            return
        }
        val at = minOf(next, now + LONGEST_WAIT)
        if (old != null && !old.isCancelled && at == due) return
        old?.cancel()
        due = at
        task = plugin.platform.scheduler.run(Where.Global, delay = maxOf(0L, at - now).milliseconds, action = Fire(this))
    }

    private class Fire(private val expiry: Expiry) : (Task) -> Unit {
        override fun invoke(task: Task) = expiry.fire()
    }

    /** Handles everything due now, then sets the timer for the next. */
    public fun fire() {
        task = null
        due = Long.MAX_VALUE
        if (!on || !plugin.isReady) return
        val now = plugin.now()
        firing = true
        try {
            for (plot in plugin.plots.rented()) {
                val lease = plot.lease ?: continue
                val renewal = plugin.renewals.dueAt(plot)
                if (renewal != null && renewal <= now) {
                    plugin.renewals.renew(plot)
                } else if (lease.expires <= now) {
                    plugin.rentals.expire(plot)
                } else if (plugin.server.isOnline(lease.owner)) {
                    warn(lease.owner, plot, now)
                }
            }
        } finally {
            firing = false
        }
        reschedule()
    }

    /** A player joined: their name kept, warnings and renewals that came due while they were away. */
    public fun joined(player: PlayerRef, name: String) {
        if (!plugin.isReady) return
        val now = plugin.now()
        for (plot in plugin.plots.owned(player)) {
            plugin.rentals.rename(plot, name)
            warn(player, plot, now)
        }
        plugin.renewals.joined(player)
    }

    private fun warn(owner: PlayerRef, plot: Plot, now: Long) {
        val lease = plot.lease ?: return
        // A plot that renews on its own isn't about to be lost: no warning while its renewal is still to come.
        if (plugin.renewals.dueAt(plot) != null) return
        val level = Lease.warningFor(lease.remaining(now))
        if (level <= lease.warned) return
        val changed = locked(plugin.lock) {
            val current = plot.lease
            if (current !== lease) return@locked false
            plugin.plots.lease(plot, lease.with(warned = level))
            true
        }
        if (!changed) return
        plugin.store?.saveLease(plot)
        plugin.texts.send(owner, XPlotsMessages.expiresSoon, "plot" to plot.id, "time" to plugin.rentals.time(lease.remaining(now)))
    }

    private companion object {
        val LONGEST_WAIT = 20.minutes.inWholeMilliseconds
    }
}

/** The sign refresh and the expiry timer: the only timers, and only while xPlots runs with its plots read. */
internal class Timers(private val plugin: XPlotsPlugin) : Module, (Task) -> Unit {
    override fun enable(context: ModuleContext) {
        // Signs in chunks loaded before xPlots started are checked a second in, then once a minute.
        context.scheduler.run(Where.Global, 1.seconds, 1.minutes, action = this)
        plugin.expiry.start()
        context.onDisable(Stop(plugin))
    }

    override fun invoke(task: Task) = plugin.signs.refreshAll()

    private class Stop(private val plugin: XPlotsPlugin) : () -> Unit {
        override fun invoke() = plugin.expiry.stop()
    }
}

/** Plot homes in xTeleport's `/homes`, while xTeleport 2.0 is installed: each plot its owner rents, at its front. */
internal class HomesModule(private val plugin: XPlotsPlugin) : Module, (PlayerRef) -> List<PlotHome> {
    override fun enable(context: ModuleContext) {
        val handle = plugin.hooks.registerHomes(this) ?: return
        context.onDisable(Close(handle))
    }

    override fun invoke(player: PlayerRef): List<PlotHome> = plugin.plots.owned(player).map { PlotHome(it.id, front(it)) }

    private class Close(private val handle: AutoCloseable) : () -> Unit {
        override fun invoke() = handle.close()
    }
}

/**
 * Where a player stands to face [plot]: by its sign, on the path side, looking at the plot. A
 * sign inside the plot's edge (a path too narrow for it) puts them just outside it.
 */
public fun front(plot: Plot): Location {
    val sign = plot.sign
    var x = sign.x
    var z = sign.z
    val stepX = Facing.stepX(sign.facing)
    val stepZ = Facing.stepZ(sign.facing)
    if (plot.box.containsColumn(x, z)) {
        x += stepX
        z += stepZ
    }
    val base = Location(plot.field.worldRef, x + 0.5, sign.y.toDouble(), z + 0.5)
    if (stepX == 0 && stepZ == 0) return base
    // Looking back at the plot: the opposite of the sign's facing.
    val yaw = (kotlin.math.atan2(stepX.toDouble(), -stepZ.toDouble()) * 180 / kotlin.math.PI).toFloat()
    return base.facing(yaw, 0f)
}
