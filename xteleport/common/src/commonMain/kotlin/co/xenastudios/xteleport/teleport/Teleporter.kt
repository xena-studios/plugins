package co.xenastudios.xteleport.teleport

import co.xenastudios.core.api.Location
import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.isSettled
import co.xenastudios.core.api.newPending
import co.xenastudios.core.api.onSettled
import co.xenastudios.core.api.pendingFailure
import co.xenastudios.core.api.pendingOf
import co.xenastudios.core.api.resolve
import co.xenastudios.core.common.concurrentMap
import co.xenastudios.core.common.i18n.MessageKey
import co.xenastudios.core.common.locked
import co.xenastudios.core.common.scheduler.Task
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.scheduler.now
import co.xenastudios.xteleport.Economy
import co.xenastudios.xteleport.XTeleportMessages
import co.xenastudios.xteleport.XTeleportPlugin
import co.xenastudios.xteleport.api.TeleportResult
import co.xenastudios.xteleport.api.TeleportType
import co.xenastudios.xteleport.api.event.PreTeleportEvent
import co.xenastudios.xteleport.api.event.TeleportEvent
import co.xenastudios.xteleport.config.Travel
import co.xenastudios.xteleport.config.XTeleportConfig
import co.xenastudios.xteleport.tpa.Cooldowns
import kotlin.time.Duration.Companion.milliseconds

/**
 * One teleport's settings.
 *
 * @property warmup seconds to stand still, or -1 for the config's
 * @property payer who pays and has the cooldown, or `null` for the player teleporting: for
 *   `/tpahere` (and `/tpa`'s accept), whoever asked
 * @property arrived told to the player once they arrive, with [placeholders]
 */
public class Trip(
    public val type: TeleportType,
    public val cooldownKey: String,
    public val cooldown: Int,
    public val cost: Double,
    public val warmup: Int,
    public val checkSafe: Boolean,
    public val payer: PlayerRef? = null,
    public val arrived: MessageKey? = null,
    public val placeholders: Array<out Pair<String, Any>> = emptyArray(),
) {
    public fun paidBy(who: PlayerRef): Trip = Trip(type, cooldownKey, cooldown, cost, warmup, checkSafe, who, arrived, placeholders)

    public fun saying(message: MessageKey, vararg placeholders: Pair<String, Any>): Trip =
        Trip(type, cooldownKey, cooldown, cost, warmup, checkSafe, payer, message, placeholders)

    public companion object {
        /** A command's teleport, with its cooldown and cost. */
        public fun of(type: TeleportType, travel: Travel, checkSafe: Boolean, arrived: MessageKey?, vararg placeholders: Pair<String, Any>): Trip =
            Trip(type, travel.key, travel.cooldown, travel.cost, -1, checkSafe, null, arrived, placeholders)
    }
}

/** A destination that turned out not to exist, with what to tell the player instead of "the teleport didn't work". */
public class NoDestination(public val key: MessageKey) : RuntimeException(key.key)

/**
 * Every teleport xTeleport makes, and the ones other plugins ask for through the API, in this
 * order: the payer's cooldown and cost checked, the warmup (moving cancels it, and damage if the
 * config says so), the destination worked out, its chunk loaded (in the background where the
 * server can), the safety check on the destination's own thread, [PreTeleportEvent], the cost
 * taken, then the teleport. A teleport that fails after paying is refunded. Every teleport's
 * [Pending] settles, even when the plugin stops partway: then with [TeleportResult.CANCELLED].
 */
public class Teleporter(private val plugin: XTeleportPlugin) {
    private val server get() = plugin.server
    private val texts get() = plugin.texts
    private val scheduler get() = plugin.platform.scheduler

    public val cooldowns: Cooldowns = Cooldowns { plugin.now() }
    public val warmups: Warmups = Warmups(plugin)

    // Every teleport not yet finished, so stopping can finish them.
    private val unfinished = concurrentMap<Pending<TeleportResult>, Boolean>()

    @kotlin.concurrent.Volatile
    private var warnedEconomy = false

    /** Stops every warmup without a word, and settles every unfinished teleport with [TeleportResult.CANCELLED]. */
    public fun clear() {
        warmups.clear()
        for (result in unfinished.keys.toList()) result.resolve(TeleportResult.CANCELLED)
        unfinished.clear()
    }

    private fun payer(trip: Trip, player: PlayerRef): PlayerRef = trip.payer ?: player

    private fun cost(trip: Trip, player: PlayerRef): Double =
        if (trip.cost > 0 && !server.hasPermission(payer(trip, player), COST_BYPASS)) trip.cost else 0.0

    /** [amount] as the economy writes money, or the number. */
    public fun money(amount: Double, economy: Economy? = plugin.economy()): String =
        economy?.format(amount) ?: if (amount == kotlin.math.floor(amount) && amount < 1e15) amount.toLong().toString() else amount.toString()

    /**
     * The checks that can say no before anything starts: the payer's cooldown and whether they can
     * afford it, telling the payer why. On the player's thread. `null` if it can go ahead.
     */
    public fun precheck(player: PlayerRef, trip: Trip): TeleportResult? {
        val payer = payer(trip, player)
        if (onCooldown(payer, trip.cooldownKey)) return TeleportResult.ON_COOLDOWN
        val cost = cost(trip, player)
        if (cost > 0) {
            val economy = plugin.economy() ?: run {
                noEconomy(payer)
                return TeleportResult.CANT_AFFORD
            }
            if (!economy.has(payer, cost)) {
                texts.send(payer, XTeleportMessages.cantAfford, "cost" to money(cost, economy))
                return TeleportResult.CANT_AFFORD
            }
        }
        return null
    }

    /**
     * Whether a cooldown on [key] is still running for [player], telling them how long is left.
     * False for a blank key or with `xteleport.cooldown.bypass`.
     */
    public fun onCooldown(player: PlayerRef, key: String): Boolean {
        if (key.isEmpty() || server.hasPermission(player, COOLDOWN_BYPASS)) return false
        val left = cooldowns.remaining(player, key)
        if (left <= 0) return false
        // Rounded up to the second, as 1.x did: "1s", never "0s".
        texts.send(player, XTeleportMessages.cooldown, "time" to (((left + 999) / 1000) * 1000).milliseconds)
        return true
    }

    private fun noEconomy(payer: PlayerRef) {
        texts.send(payer, XTeleportMessages.noEconomy)
        if (!warnedEconomy) {
            warnedEconomy = true
            plugin.log.warn("A teleport has a cost, but there's no economy: install xMoney (or, on Bukkit, Vault and an economy plugin), or set the costs to 0.")
        }
    }

    /**
     * Teleports [player], step by step as the class says. Call on the player's thread.
     *
     * @param destination where to, worked out once the warmup is over (another player's spot at
     *   that moment); may settle on any thread, with `null` if there's nowhere to go any more, or
     *   fail with [NoDestination] to say why
     */
    public fun teleport(player: PlayerRef, trip: Trip, destination: (PlayerRef) -> Pending<Location?>): Pending<TeleportResult> {
        val result = newPending<TeleportResult>()
        precheck(player, trip)?.let { refused ->
            result.resolve(refused)
            return result
        }
        unfinished[result] = true
        result.onSettled { _, _ -> unfinished.remove(result) }
        val seconds = if (server.hasPermission(player, WARMUP_BYPASS)) 0 else if (trip.warmup >= 0) trip.warmup else plugin.config[XTeleportConfig.teleports.warmup]
        if (seconds > 0) texts.send(player, XTeleportMessages.warmup, "seconds" to seconds)
        val journey = Journey(player, trip, destination, result)
        warmups.start(player, seconds, plugin.config[XTeleportConfig.teleports.cancelOnDamage], journey)
        return result
    }

    /** Runs [work] on [player]'s thread: now if this is it. [gone] runs instead if they've left. */
    public fun onPlayer(player: PlayerRef, gone: () -> Unit = {}, work: () -> Unit) {
        scheduler.now(Where.on(player), retired = gone, action = work)
    }

    /** Where [player] is, read on their own thread; `null` if they've left. */
    public fun whereIs(player: PlayerRef): Pending<Location?> {
        val where = newPending<Location?>()
        onPlayer(player, { where.resolve(null) }) { where.resolve(server.location(player)) }
        return where
    }

    /** One teleport after its warmup: a class for its steps rather than a lambda each. */
    private inner class Journey(
        private val player: PlayerRef,
        private val trip: Trip,
        private val destination: (PlayerRef) -> Pending<Location?>,
        private val result: Pending<TeleportResult>,
    ) : Warmups.Then {
        private fun end(outcome: TeleportResult, message: MessageKey?, vararg placeholders: Pair<String, Any>) {
            if (message != null) onPlayer(player) { texts.send(player, message, *placeholders) }
            result.resolve(outcome)
        }

        override fun cancelled(reason: Warmups.Cancel) {
            when (reason) {
                Warmups.Cancel.MOVED -> texts.send(player, XTeleportMessages.movedCancelled)
                Warmups.Cancel.DAMAGED -> texts.send(player, XTeleportMessages.damageCancelled)
                else -> {}
            }
            result.resolve(TeleportResult.CANCELLED)
        }

        // After the warmup, on the player's thread.
        override fun done() {
            val where = try {
                destination(player)
            } catch (e: Exception) {
                pendingFailure(e)
            }
            where.onSettled { to, error ->
                when {
                    result.isSettled -> {}
                    error is NoDestination -> end(TeleportResult.FAILED, error.key)
                    error != null || to == null -> {
                        if (error != null) plugin.log.warn("A teleport's destination couldn't be worked out: $error")
                        end(TeleportResult.FAILED, XTeleportMessages.failed)
                    }
                    else -> load(to)
                }
            }
        }

        private fun load(to: Location) {
            val world = server.world(to.world.name)
            if (world == null) {
                end(TeleportResult.UNSAFE, XTeleportMessages.worldMissing, "world" to to.world.name)
                return
            }
            val at = if (world.ref == to.world) to else Location(world.ref, to.x, to.y, to.z, to.yaw, to.pitch)
            server.loadChunk(world.ref, at.chunkX, at.chunkZ, true).onSettled { loaded, _ ->
                if (loaded != true) {
                    end(TeleportResult.UNSAFE, XTeleportMessages.unsafe)
                } else if (!trip.checkSafe || !plugin.config[XTeleportConfig.teleports.checkSafe]) {
                    onPlayer(player, { result.resolve(TeleportResult.FAILED) }) { go(at) }
                } else {
                    scheduler.run(Where.At(at)) { check(world, at) }
                }
            }
        }

        // On the thread that owns the destination.
        private fun check(world: co.xenastudios.xteleport.WorldInfo, at: Location) {
            val safe = try {
                server.blocks(world.ref)?.let { Safety.arrival(it, world, at, plugin.config[XTeleportConfig.teleports.maxFall]) } == true
            } catch (e: Exception) {
                plugin.log.warn("Couldn't check a teleport's destination: $e")
                false
            }
            if (!safe) end(TeleportResult.UNSAFE, XTeleportMessages.unsafe)
            else onPlayer(player, { result.resolve(TeleportResult.FAILED) }) { go(at) }
        }

        // The last steps, on the player's thread: the event, payment, the teleport.
        private fun go(to: Location) {
            if (result.isSettled) return
            if (!server.isOnline(player)) {
                result.resolve(TeleportResult.FAILED)
                return
            }
            plugin.eventsReady()
            if (plugin.bus.hasListeners(PreTeleportEvent.TYPE)) {
                val event = plugin.bus.fire(PreTeleportEvent.TYPE, PreTeleportEvent(player, to, trip.type))
                if (event.isCancelled) {
                    result.resolve(TeleportResult.BLOCKED)
                    return
                }
            }
            val cost = cost(trip, player)
            val payer = payer(trip, player)
            if (cost <= 0) {
                move(to, 0.0, payer, null)
                return
            }
            val economy = plugin.economy()
            if (economy == null) {
                noEconomy(payer)
                result.resolve(TeleportResult.CANT_AFFORD)
                return
            }
            val taken = try {
                economy.withdraw(payer, cost, reason())
            } catch (e: Exception) {
                pendingOf(false)
            }
            taken.onSettled { ok, _ ->
                onPlayer(player, {
                    // They left while it was taken: give it back.
                    if (ok == true) economy.deposit(payer, cost, reason())
                    result.resolve(TeleportResult.FAILED)
                }) {
                    if (ok != true) {
                        texts.send(payer, XTeleportMessages.cantAfford, "cost" to money(cost, economy))
                        result.resolve(TeleportResult.CANT_AFFORD)
                    } else {
                        texts.send(payer, XTeleportMessages.paid, "cost" to money(cost, economy))
                        move(to, cost, payer, economy)
                    }
                }
            }
        }

        private fun reason() = "Teleport: ${trip.type.name.lowercase()}"

        private fun move(to: Location, cost: Double, payer: PlayerRef, economy: Economy?) {
            val from = server.location(player)
            try {
                server.teleport(player, to) { moved -> arrived(moved, from, to, cost, payer, economy) }
            } catch (e: Exception) {
                plugin.log.warn("A teleport failed: $e")
                arrived(false, from, to, cost, payer, economy)
            }
        }

        private fun arrived(moved: Boolean, from: Location?, to: Location, cost: Double, payer: PlayerRef, economy: Economy?) {
            if (moved) {
                cooldowns.start(payer, trip.cooldownKey, trip.cooldown)
                trip.arrived?.let { texts.send(player, it, *trip.placeholders) }
                if (from != null && plugin.bus.hasListeners(TeleportEvent.TYPE)) plugin.bus.fire(TeleportEvent.TYPE, TeleportEvent(player, from, to, trip.type))
                result.resolve(TeleportResult.TELEPORTED)
                return
            }
            if (cost > 0 && economy != null) {
                economy.deposit(payer, cost, "Refund: ${reason()}").onSettled { given, _ ->
                    if (given == true) texts.send(payer, XTeleportMessages.refunded, "cost" to money(cost, economy))
                }
            }
            texts.send(player, XTeleportMessages.failed)
            result.resolve(TeleportResult.FAILED)
        }
    }

    public companion object {
        public const val WARMUP_BYPASS: String = "xteleport.warmup.bypass"
        public const val COOLDOWN_BYPASS: String = "xteleport.cooldown.bypass"
        public const val COST_BYPASS: String = "xteleport.cost.bypass"
    }
}

/**
 * Something that happens after standing still for a few seconds: moving half a block, or changing
 * worlds, cancels it, and so does taking damage when asked; the seconds left count down on the
 * action bar. Each waiting player is checked four times a second by a task on their own thread,
 * which ends with the warmup; the damage listener is there only while a warmup it cancels is
 * running. 1.x's warmups, from today's core.
 */
public class Warmups(private val plugin: XTeleportPlugin) {

    /** Why a warmup stopped without finishing. */
    public enum class Cancel { MOVED, DAMAGED, REPLACED, CANCELLED, GONE }

    /** What a warmup does then. */
    public interface Then {
        /** It finished, on the player's thread. */
        public fun done()

        /** It stopped early; [Cancel.GONE] may come on another thread. */
        public fun cancelled(reason: Cancel)
    }

    private val running = concurrentMap<PlayerRef, Running>()
    private val lock = Any()
    private var damageWatched = 0
    private var damage: AutoCloseable? = null

    /** Whether [player] is waiting out a warmup. Any thread. */
    public fun waiting(player: PlayerRef): Boolean = player in running

    /** Starts a warmup, replacing any the player has. On the player's thread. Zero seconds runs at once. */
    public fun start(player: PlayerRef, seconds: Int, cancelOnDamage: Boolean, then: Then) {
        running[player]?.stop(Cancel.REPLACED)
        if (seconds <= 0) {
            then.done()
            return
        }
        val start = plugin.server.location(player) ?: return then.cancelled(Cancel.GONE)
        val now = Running(player, then, start, seconds * CHECKS, cancelOnDamage)
        running[player] = now
        if (cancelOnDamage) watchDamage(true)
        now.show(seconds)
        now.task = plugin.platform.scheduler.run(Where.on(player), delay = PERIOD, period = PERIOD, retired = { now.stop(Cancel.GONE) }, action = now)
        if (running[player] !== now) now.task?.cancel()
    }

    /** Cancels [player]'s warmup, if any. */
    public fun cancel(player: PlayerRef) {
        running[player]?.stop(Cancel.CANCELLED)
    }

    /** Stops every warmup without telling anyone, as the plugin stops. */
    public fun clear() {
        for (warmup in running.values.toList()) warmup.end()
    }

    internal val listening: Boolean get() = locked(lock) { damageWatched > 0 }

    private fun watchDamage(watch: Boolean) {
        locked(lock) {
            damageWatched += if (watch) 1 else -1
            if (watch && damageWatched == 1) {
                damage = plugin.server.watchDamage { hurt -> running[hurt]?.takeIf { it.cancelOnDamage }?.stop(Cancel.DAMAGED) }
            } else if (!watch && damageWatched == 0) {
                damage?.close()
                damage = null
            }
        }
    }

    private inner class Running(
        val player: PlayerRef,
        val then: Then,
        val start: Location,
        var checks: Int,
        val cancelOnDamage: Boolean,
    ) : (Task) -> Unit {
        @kotlin.concurrent.Volatile
        var task: Task? = null

        // On the player's thread, four times a second.
        override fun invoke(task: Task) {
            val now = plugin.server.location(player)
            if (now == null) {
                stop(Cancel.GONE)
                return
            }
            if (now.distanceSquared(start) > MOVED) {
                stop(Cancel.MOVED)
                return
            }
            if (--checks <= 0) {
                if (end()) then.done()
            } else if (checks % CHECKS == 0) {
                show(checks / CHECKS)
            }
        }

        // The countdown is a nicety: one that fails never stops the teleport.
        fun show(seconds: Int) {
            if (!plugin.server.features.actionBar) return
            try {
                plugin.texts.render(player, XTeleportMessages.countdown, "seconds" to seconds)?.let { plugin.server.actionBar(player, it) }
            } catch (e: Throwable) {
                // A server missing what its countdown needs (NoSuchMethodError) too: not a fatal error.
                if (co.xenastudios.core.common.isFatal(e)) throw e
                plugin.log.warn("Couldn't show the warmup's countdown: $e")
            }
        }

        fun stop(reason: Cancel) {
            if (end()) then.cancelled(reason)
        }

        /** Takes it out of the running; `true` for whoever did, so it ends once. */
        fun end(): Boolean {
            val removed = locked(lock) { if (running[player] === this) running.remove(player) != null else false }
            if (!removed) return false
            task?.cancel()
            if (cancelOnDamage) watchDamage(false)
            return true
        }
    }

    private companion object {
        const val CHECKS = 4
        val PERIOD = 250.milliseconds

        // Half a block, squared. Another world is infinitely far.
        const val MOVED = 0.25
    }
}
