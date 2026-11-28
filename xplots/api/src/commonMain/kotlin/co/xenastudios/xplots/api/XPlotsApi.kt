package co.xenastudios.xplots.api

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.WorldRef
import co.xenastudios.core.api.event.EventBus
import kotlin.concurrent.Volatile
import kotlin.jvm.JvmStatic

/**
 * xPlots' developer API, its first: read every field and plot from memory, rent plots out, end
 * rents and change members behind the same cancellable events xPlots' own commands fire, and the
 * seams add-ons hook into (prices, who may build, another region system). Players are
 * [PlayerRef]s and worlds [WorldRef]s, so one API works on every platform.
 *
 * Get it with [XPlots.api]; on Bukkit it's also in the services manager, and every event is
 * also a Bukkit event in `co.xenastudios.xplots.api.bukkit`.
 *
 * **Threads.** Reads answer from memory on any thread, with a copy ([PlotInfo]) that doesn't
 * change after. Changes return a [Pending] (a `CompletableFuture` on the JVM) that settles once
 * the change is made in memory and handed to the database writer; the sign and the world follow
 * on their own threads. Nothing here waits on the database.
 *
 * **Before the plots are read.** The database is read off the server's threads as xPlots starts;
 * until it has been, reads are empty and changes settle [ChangeResult.NOT_READY].
 *
 * @since 2.0.0
 */
public interface XPlotsApi {
    /** The running xPlots' version, such as `2.0.0`. */
    public val version: String

    /**
     * The events ([co.xenastudios.xplots.api.event]): before (cancellable) and after a plot is
     * rented, its rent ends, a member is added or removed, and rent is paid.
     */
    public val events: EventBus

    /** Whether the plots have been read from the database, so reads answer and changes can be made. */
    public val isReady: Boolean

    /** Every field, sorted by name. */
    public fun fields(): List<FieldInfo>

    /** The field called [name] in any case, or `null`. */
    public fun field(name: String): FieldInfo?

    /** The plot called [id] (`box-12`) in any case, or `null`. */
    public fun plot(id: String): PlotInfo?

    /** The plot at the block [x] [y] [z] of [world], or `null` outside every plot (a field's paths included). Arithmetic, not a search. */
    public fun plotAt(world: WorldRef, x: Int, y: Int, z: Int): PlotInfo?

    /** Whether the block at [x] [y] [z] of [world] is in a field: a plot, a path, or above or below one. */
    public fun isInField(world: WorldRef, x: Int, y: Int, z: Int): Boolean

    /** The plots [player] rents, in the order they rented them. */
    public fun plotsOf(player: PlayerRef): List<PlotInfo>

    /**
     * Whether [player] may change the block at [x] [y] [z] of [world] as xPlots decides: anywhere
     * outside a field, and inside one only in a plot they own or are a member of that isn't being
     * reset, after any [AccessRule]. Permissions (`xplots.bypass`) aren't counted: ask the platform.
     */
    public fun mayBuild(player: PlayerRef, world: WorldRef, x: Int, y: Int, z: Int): Boolean

    /**
     * Gives the plot [id] to [player] ([name] as they're shown): a free one rented to them for one
     * period at no cost, a rented one handed over with its time, slots and members, as
     * `/xplots plot setowner` does. Fires the claim events for a free plot.
     */
    public fun setOwner(id: String, player: PlayerRef, name: String): Pending<ChangeResult>

    /** Ends the plot's rent now ([EndReason.EVICTED]); it's put back if its field resets plots. */
    public fun evict(id: String): Pending<ChangeResult>

    /** Adds [player] as a member of the rented plot [id], ignoring its slots, as staff's `addmember` does. */
    public fun addMember(id: String, player: PlayerRef, name: String): Pending<ChangeResult>

    /** Removes [player] from the plot's members. */
    public fun removeMember(id: String, player: PlayerRef): Pending<ChangeResult>

    /**
     * Adds [millis] to the rented plot's time left, free of charge (a vote reward, a refund in
     * time), past `max-prepaid` if need be. Negative takes time away, down to now.
     */
    public fun extend(id: String, millis: Long): Pending<ChangeResult>

    /**
     * What [player] would pay for [kind] on [plot] now, with the field's settings and every
     * [PriceModifier]: one period for [RentKind.RENT] and [RentKind.RENEW], [periods] for
     * [RentKind.PAY], one slot for [RentKind.SLOT].
     */
    public fun price(player: PlayerRef, plot: String, kind: RentKind, periods: Int): Double?

    /** Asks [modifier] about every price from now on; [owner] is your plugin's name, for [unregisterAll]. */
    public fun registerPriceModifier(owner: String, modifier: PriceModifier)

    /** Asks [rule] about every block a player changes or uses in a field, from now on. */
    public fun registerAccessRule(owner: String, rule: AccessRule)

    /**
     * Tells [backend] about every field and plot made or deleted from now on, and at once about
     * every one there is, as `define` calls: another region system's side of xPlots' plots.
     */
    public fun registerRegionBackend(owner: String, backend: RegionBackend)

    /** Takes away every modifier, rule and backend [owner] registered, as it disables. */
    public fun unregisterAll(owner: String)
}

/**
 * Where xPlots' API is found, on every platform:
 *
 * ```java
 * XPlotsApi api = XPlots.api();
 * ```
 *
 * @since 2.0.0
 */
public object XPlots {
    @Volatile
    private var running: XPlotsApi? = null

    /**
     * xPlots' API.
     *
     * @throws IllegalStateException if xPlots isn't installed and enabled: add it to your
     *   plugin's `depend` (or `softdepend`, and check [isEnabled] first)
     */
    @JvmStatic
    public fun api(): XPlotsApi =
        running ?: throw IllegalStateException("xPlots isn't enabled. Add it to depend (or softdepend and check XPlots.isEnabled() first).")

    /** Whether xPlots is enabled, so [api] answers. */
    @JvmStatic
    public fun isEnabled(): Boolean = running != null

    /** Made available by xPlots as it's enabled, and taken away as it's disabled: not for other plugins. */
    @JvmStatic
    public fun register(api: XPlotsApi?) {
        running = api
    }
}
