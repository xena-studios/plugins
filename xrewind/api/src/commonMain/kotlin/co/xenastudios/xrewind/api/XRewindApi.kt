package co.xenastudios.xrewind.api

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.WorldRef
import co.xenastudios.core.api.event.EventBus
import kotlin.concurrent.Volatile
import kotlin.jvm.JvmStatic

/**
 * xRewind's developer API, the same on Paper, Folia, Spigot and Fabric: log the changes your
 * plugin makes, look the history up, plan, preview, apply and undo rollbacks and restores (in one
 * region only, for a plot plugin), and filter what's logged. Like CoreProtect's API, with
 * xRewind's threading.
 *
 * Get it with [XRewind.api]. On Bukkit it's also in the services manager while xRewind is enabled,
 * with helpers for Bukkit's own types in `co.xenastudios.xrewind.api.bukkit.BukkitRewind`.
 *
 * **Threads.** Logging may be called from any thread and costs about as much as xRewind's own
 * listeners: a record queued for the writer, which writes in batches on its own thread. Everything
 * that reads the database returns a [Pending] (a `CompletableFuture` on the JVM) that settles off
 * the server's threads: never wait on it on a server thread. Lookups and rollbacks use the same
 * background threads, per-tick budget and one-at-a-time rule as xRewind's commands.
 *
 * **Errors.** A mistake in the call (a query without a time where one's needed, a plan applied
 * twice) throws at once. Anything that depends on xRewind's state at the time (its database not
 * ready yet, another rollback running, a listener cancelling, xRewind stopping) fails the returned
 * [Pending] instead, so it can be handled where the result is.
 *
 * Block states are text, as Minecraft writes them: `minecraft:oak_stairs[facing=east,half=bottom]`.
 * Items are ids: `minecraft:diamond`.
 *
 * @since 2.0.0
 */
public interface XRewindApi {
    /** The running xRewind's version, such as `2.0.0`. */
    public val version: String

    /**
     * The events: [co.xenastudios.xrewind.api.event.LookupEvent] for every lookup (an audit log),
     * [co.xenastudios.xrewind.api.event.RollbackEvent] before a rollback, restore or undo
     * (cancellable), [co.xenastudios.xrewind.api.event.RollbackFinishedEvent] after. On Bukkit each
     * is also a Bukkit event in `co.xenastudios.xrewind.api.bukkit`.
     */
    public val events: EventBus

    /** Logs a block placed where there was air. Any thread; not logged in a world xRewind leaves out. */
    public fun logPlacement(actor: Actor, world: WorldRef, x: Int, y: Int, z: Int, placed: String)

    /**
     * Logs a block removed, leaving air. Any thread, as [logPlacement]. What the block held (a
     * chest's items, a sign's text) isn't logged with it; log a container's items with
     * [logContainerChange] first if they matter.
     */
    public fun logRemoval(actor: Actor, world: WorldRef, x: Int, y: Int, z: Int, removed: String)

    /**
     * Logs a block changed from [before] to [after], such as a plot reset: as a removal when
     * [after] is air, a placement otherwise. Any thread.
     */
    public fun logChange(actor: Actor, world: WorldRef, x: Int, y: Int, z: Int, before: String, after: String)

    /**
     * Logs [amount] of [item] put into a container at [x] [y] [z] (negative: taken out; not 0). The
     * item is a plain one of its type; on Bukkit, `BukkitRewind.logContainerChange` takes an
     * `ItemStack` with its name and enchantments. Any thread.
     */
    public fun logContainerChange(actor: Actor, world: WorldRef, x: Int, y: Int, z: Int, item: String, amount: Int)

    /**
     * The changes matching [query], newest first, up to its [Query.limit]. It first waits up to a
     * second for what was logged before the call to be written, so a change logged a moment ago is
     * usually included. Fires [co.xenastudios.xrewind.api.event.LookupEvent].
     */
    public fun lookup(query: Query): Pending<List<Entry>>

    /**
     * What rolling back the block and container changes [query] matches would do, without changing
     * anything: a preview to show, or to [apply]. A query [Query.Builder.area] and
     * [Query.Builder.filter] keep to one region: a plot.
     *
     * @throws IllegalArgumentException if the query has no time
     */
    public fun planRollback(query: Query): Pending<Plan>

    /** As [planRollback], for a restore: making rolled-back changes again. */
    public fun planRestore(query: Query): Pending<Plan>

    /**
     * Applies [plan] a few milliseconds of each tick (`rollback.ms-per-tick`), chunk by chunk on
     * the threads that own them, as `/xrewind rollback` does; one rollback, restore or undo runs at
     * a time, xRewind's commands included. Fires [co.xenastudios.xrewind.api.event.RollbackEvent]
     * first and [co.xenastudios.xrewind.api.event.RollbackFinishedEvent] after.
     *
     * A plan is applied once at most; to undo it, apply the result's [RollbackResult.undo]. One that
     * wasn't applied (another was running, or a listener cancelled it) can be applied again. Settles
     * once every change is made; fails with an `IllegalStateException` if another is running or
     * xRewind stops first, and with a `CancellationException` if a listener cancelled it.
     *
     * @throws IllegalArgumentException if the plan isn't one of this API's, such as an event's
     * @throws IllegalStateException if the plan was applied already, or is being applied
     */
    public fun apply(plan: Plan): Pending<RollbackResult>

    /**
     * Writes everything logged before the call. Any thread. Settles `true` once it's written and
     * writes are going through; `false` after 10 seconds, or if the database can't be reached.
     */
    public fun flush(): Pending<Boolean>

    /**
     * Asks [filter] about every change before it's logged, from now on: one it says no to isn't
     * logged. [owner] names it in the console. Called on the thread the change happens on, for
     * every change: keep it quick.
     */
    public fun registerFilter(owner: String, filter: LogFilter)

    /** Takes away every filter [owner] registered. */
    public fun unregisterAll(owner: String)
}

/**
 * Where xRewind's API is found, on every platform:
 *
 * ```java
 * XRewindApi api = XRewind.api();
 * ```
 *
 * @since 2.0.0
 */
public object XRewind {
    @Volatile
    private var running: XRewindApi? = null

    /**
     * xRewind's API.
     *
     * @throws IllegalStateException if xRewind isn't installed and enabled
     */
    @JvmStatic
    public fun api(): XRewindApi =
        running ?: throw IllegalStateException("xRewind isn't enabled. Add it to depend (or softdepend and check XRewind.isEnabled() first).")

    /** Whether xRewind is enabled, so [api] answers. */
    @JvmStatic
    public fun isEnabled(): Boolean = running != null

    /** Made available by xRewind as it's enabled, and taken away as it's disabled: not for other plugins. */
    @JvmStatic
    public fun register(api: XRewindApi?) {
        running = api
    }
}
