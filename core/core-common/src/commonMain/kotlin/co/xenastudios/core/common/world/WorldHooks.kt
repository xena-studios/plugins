package co.xenastudios.core.common.world

import co.xenastudios.core.api.event.Event
import co.xenastudios.core.api.event.EventListener
import co.xenastudios.core.api.event.EventPriority
import co.xenastudios.core.api.event.EventType
import co.xenastudios.core.api.event.Subscription
import co.xenastudios.core.common.event.SimpleEventBus
import co.xenastudios.core.common.locked
import co.xenastudios.core.common.platform.Log
import kotlin.concurrent.Volatile
import kotlin.jvm.JvmOverloads

/**
 * The kinds of world change a platform can be asked to hear: block changes by each
 * [ChangeCause], containers, and entities spawning, dying and changing. A [WorldHooks]
 * listens to each only while some listener wants it.
 */
public enum class WorldHook {
    BLOCK_PLAYER,
    BLOCK_EXPLOSION,
    BLOCK_FIRE,
    BLOCK_PISTON,
    BLOCK_FLUID,
    BLOCK_ENTITY,
    BLOCK_DECAY,
    CONTAINER,
    CONTAINER_HOPPER,
    ENTITY_SPAWN,
    ENTITY_DEATH,
    ENTITY_CHANGE,
    ;

    public companion object {
        /** The hook for block changes of [cause]. */
        public fun of(cause: ChangeCause): WorldHook = entries[cause.ordinal]
    }
}

/**
 * The world's changes, for one plugin: blocks placed, broken and changed by players,
 * explosions, fire, pistons, fluids and entities; containers filled and emptied; entities
 * spawning, dying and being tamed. What a block logger records and a limiter stops, the same on
 * every platform (DECISIONS.md, "The world hooks"). Each platform's adapter makes one on the
 * plugin's asking (`BukkitPlatform.world`, `FabricPlatform.world`, `FakeWorld` in tests).
 *
 * **Nothing listens until a plugin does.** The platform starts hearing a kind of change
 * ([WorldHook]) with its first listener and stops with its last: a plugin that logs only
 * players' blocks never hears a fluid flow, the busiest change there is. [close] stops all of
 * it, as the module that asked turns off.
 *
 * **Among other plugins**, [priority] is where the platform puts this plugin's listeners
 * (Bukkit's event priority of the same name); every listener here runs inside that slot, in
 * its own order. [EventPriority.MONITOR] listeners hear after every plugin on the server, once
 * it's decided whether the change happens: that's where a logger listens.
 *
 * **Cost.** A change nobody here listens for costs the platform's own event, or on Fabric one
 * check of a field. One that's heard costs one small event, and its [BlockData] works out its
 * text only when it's read.
 */
public abstract class WorldHooks(
    /** Where this plugin's listeners sit among other plugins'. */
    public val priority: EventPriority,
    /** Where a listener that throws is logged. */
    protected val log: Log,
) : AutoCloseable {

    private val bus = SimpleEventBus(log)
    private val lock = Any()
    private val listeners = IntArray(WorldHook.entries.size)

    // One bit per WorldHook that has a listener, for the platform's hot paths.
    @Volatile
    private var heard: Int = 0

    @Volatile
    private var closed = false

    /** The kinds of change this platform can hear. The others' listeners are taken and never called. */
    public abstract val supported: Set<WorldHook>

    /**
     * [listener] hears every block changed for one of [causes], at [order] among this plugin's
     * listeners; if [ignoreCancelled], not a change a plugin already stopped. Until the
     * subscription is cancelled or the hooks closed.
     */
    @JvmOverloads
    public fun onBlockChange(
        causes: Set<ChangeCause>,
        order: EventPriority = EventPriority.NORMAL,
        ignoreCancelled: Boolean = true,
        listener: EventListener<BlockChange>,
    ): Subscription {
        val subscriptions = causes.map { subscribe(WorldHook.of(it), BlockChange.type(it), order, ignoreCancelled, listener) }
        return if (subscriptions.size == 1) subscriptions[0] else Many(subscriptions)
    }

    /**
     * [listener] hears what players put into and take out of containers, once they have; with
     * [hoppers], what hoppers and droppers move too, which on a farm is most of it.
     */
    @JvmOverloads
    public fun onContainerChange(hoppers: Boolean = false, listener: EventListener<ContainerChange>): Subscription {
        val players = subscribe(WorldHook.CONTAINER, ContainerChange.TYPE, EventPriority.MONITOR, false, listener)
        if (!hoppers) return players
        return Many(listOf(players, subscribe(WorldHook.CONTAINER_HOPPER, ContainerChange.HOPPER_TYPE, EventPriority.MONITOR, false, listener)))
    }

    /** [listener] hears every entity about to spawn, at [order]; if [ignoreCancelled], not one a plugin already stopped. */
    @JvmOverloads
    public fun onEntitySpawn(
        order: EventPriority = EventPriority.NORMAL,
        ignoreCancelled: Boolean = true,
        listener: EventListener<EntitySpawn>,
    ): Subscription = subscribe(WorldHook.ENTITY_SPAWN, EntitySpawn.TYPE, order, ignoreCancelled, listener)

    /** [listener] hears every living entity that dies, once it has. */
    public fun onEntityDeath(listener: EventListener<EntityDeath>): Subscription =
        subscribe(WorldHook.ENTITY_DEATH, EntityDeath.TYPE, EventPriority.MONITOR, false, listener)

    /** [listener] hears every entity about to be changed (tamed), at [order]. */
    @JvmOverloads
    public fun onEntityChange(
        order: EventPriority = EventPriority.NORMAL,
        ignoreCancelled: Boolean = true,
        listener: EventListener<EntityChange>,
    ): Subscription = subscribe(WorldHook.ENTITY_CHANGE, EntityChange.TYPE, order, ignoreCancelled, listener)

    /** Whether the hooks are still on. */
    public val isOpen: Boolean get() = !closed

    /** Which kinds of change the platform hears now, for `/<plugin> debug` and tests. */
    public val listening: Set<WorldHook> get() = WorldHook.entries.filterTo(LinkedHashSet()) { heard and (1 shl it.ordinal) != 0 }

    /** For the platform: whether anything listens for [hook], the first check on every hot path. */
    protected fun hears(hook: WorldHook): Boolean = heard and (1 shl hook.ordinal) != 0

    /** For the platform: whether anything listens for block changes of [cause]. */
    protected fun hears(cause: ChangeCause): Boolean = heard and (1 shl cause.ordinal) != 0

    /** For the platform: tells the listeners about [event], and returns it. */
    protected fun fire(event: BlockChange): BlockChange = bus.fire(BlockChange.type(event.cause), event)

    /** For the platform: tells the listeners about [event]: a hopper's move if its [ContainerChange.source] is set and no player is. */
    protected fun fire(event: ContainerChange): ContainerChange =
        bus.fire(if (event.player == null) ContainerChange.HOPPER_TYPE else ContainerChange.TYPE, event)

    /** For the platform: tells the listeners about [event], and returns it. */
    protected fun fire(event: EntitySpawn): EntitySpawn = bus.fire(EntitySpawn.TYPE, event)

    /** For the platform: tells the listeners about [event]. */
    protected fun fire(event: EntityDeath): EntityDeath = bus.fire(EntityDeath.TYPE, event)

    /** For the platform: tells the listeners about [event], and returns it. */
    protected fun fire(event: EntityChange): EntityChange = bus.fire(EntityChange.TYPE, event)

    /** For the platform: start hearing [hook], which now has its first listener. Called holding a lock. */
    protected abstract fun start(hook: WorldHook)

    /** For the platform: stop hearing [hook], whose last listener left. Called holding a lock. */
    protected abstract fun stop(hook: WorldHook)

    /** Stops hearing everything: the platform takes its listeners away. Calling it again does nothing. */
    override fun close() {
        locked(lock) {
            if (closed) return
            closed = true
            for (hook in WorldHook.entries) {
                if (listeners[hook.ordinal] > 0) {
                    listeners[hook.ordinal] = 0
                    heard = heard and (1 shl hook.ordinal).inv()
                    if (hook in supported) stopQuietly(hook)
                }
            }
        }
    }

    private fun <E : Event> subscribe(
        hook: WorldHook,
        type: EventType<E>,
        order: EventPriority,
        ignoreCancelled: Boolean,
        listener: EventListener<in E>,
    ): Subscription = locked(lock) {
        check(!closed) { "These world hooks are closed" }
        val subscription = bus.subscribe(type, order, ignoreCancelled, listener)
        if (listeners[hook.ordinal]++ == 0 && hook in supported) {
            start(hook)
            heard = heard or (1 shl hook.ordinal)
        }
        One(hook, subscription)
    }

    private fun unsubscribed(hook: WorldHook) = locked(lock) {
        if (closed || listeners[hook.ordinal] == 0) return@locked
        if (--listeners[hook.ordinal] == 0) {
            heard = heard and (1 shl hook.ordinal).inv()
            if (hook in supported) stopQuietly(hook)
        }
    }

    private fun stopQuietly(hook: WorldHook) {
        try {
            stop(hook)
        } catch (e: Exception) {
            log.warn("Couldn't stop hearing $hook", e)
        }
    }

    private inner class One(private val hook: WorldHook, private val subscription: Subscription) : Subscription {
        override val isActive: Boolean get() = subscription.isActive && !closed

        override fun cancel() {
            if (!subscription.isActive) return
            subscription.cancel()
            unsubscribed(hook)
        }
    }

    private class Many(private val subscriptions: List<Subscription>) : Subscription {
        override val isActive: Boolean get() = subscriptions.any { it.isActive }

        override fun cancel() = subscriptions.forEach { it.cancel() }
    }

    public companion object {
        /** Every cause, for a listener that wants every block change. */
        public val ALL_CAUSES: Set<ChangeCause> = ChangeCause.entries.toSet()
    }
}
