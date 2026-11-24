package co.xenastudios.xutilities

import co.xenastudios.core.api.Location
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.event.SimpleEventBus
import co.xenastudios.core.common.module.Module
import co.xenastudios.core.common.module.ModuleContext
import co.xenastudios.xutilities.api.event.BackRecordEvent
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Where each player was before their last teleport, or where they died, for `/back`. In memory
 * only, and only while `/back` is on: a player's spot is forgotten when they leave, and every
 * spot when `/back` turns off. Nothing is kept on disk, so there's nothing for
 * `/xutilities forget` to delete.
 *
 * Players on different Folia regions teleport at once, so the spots are one immutable map
 * swapped whole; a teleport or a death copies it, which is rare next to reading it.
 */
@OptIn(ExperimentalAtomicApi::class)
public class BackSpots(private val events: SimpleEventBus) {
    private val spots = AtomicReference<Map<PlayerRef, Location>>(emptyMap())

    /** Whether `/back` is on, so spots are remembered. Read by the API from any thread. */
    @Volatile
    public var enabled: Boolean = false
        internal set

    /** Where `/back` takes [player], or `null`. */
    public operator fun get(player: PlayerRef): Location? = if (enabled) spots.load()[player] else null

    /**
     * Saves [location] for [player], unless a listener of [BackRecordEvent] cancels it; the event
     * is made only when something listens. On the player's thread.
     */
    public fun record(player: PlayerRef, location: Location, reason: BackRecordEvent.Reason) {
        if (!enabled) return
        if (events.hasListeners(BackRecordEvent.TYPE) && events.fire(BackRecordEvent.TYPE, BackRecordEvent(player, location, reason)).isCancelled) return
        put(player, location)
    }

    /** Sets [player]'s spot without asking anyone, for the API. Does nothing while `/back` is off. */
    public fun put(player: PlayerRef, location: Location): Boolean {
        if (!enabled) return false
        update { it + (player to location) }
        return true
    }

    /** Forgets [player]'s spot. */
    public fun forget(player: PlayerRef) {
        if (spots.load().containsKey(player)) update { it - player }
    }

    internal fun clear() {
        spots.store(emptyMap())
    }

    private inline fun update(change: (Map<PlayerRef, Location>) -> Map<PlayerRef, Location>) {
        while (true) {
            val now = spots.load()
            if (spots.compareAndSet(now, change(now))) return
        }
    }
}

/**
 * `/back`'s listener, a module that's on only while `/back` is: it asks the platform to hear
 * teleports, deaths and quits, and the platform stops when it's turned off.
 */
internal class BackModule(private val server: Server, private val spots: BackSpots) : Module {
    override fun enable(context: ModuleContext) {
        val watching = server.watch(object : MoveListener {
            override fun teleported(player: PlayerRef, from: Location) = context.guard {
                // A plugin teleporting someone as they quit (spawn on quit, combat log) mustn't
                // leave a spot behind that nothing removes.
                if (server.isOnline(player)) spots.record(player, from, BackRecordEvent.Reason.TELEPORT)
            }

            override fun died(player: PlayerRef, at: Location) = context.guard {
                // Combat-log plugins kill players as they quit; that death isn't one to go back to.
                if (server.isOnline(player)) spots.record(player, at, BackRecordEvent.Reason.DEATH)
            }

            override fun quit(player: PlayerRef) = context.guard { spots.forget(player) }
        })
        spots.enabled = true
        context.onDisable {
            spots.enabled = false
            watching.close()
            spots.clear()
        }
    }

    // The listener reads nothing from the config, so a reload keeps it, and every spot.
    override fun reload(context: ModuleContext): Boolean = true
}
