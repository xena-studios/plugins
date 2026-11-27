package co.xenastudios.xbans.api

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.event.EventBus
import kotlin.concurrent.Volatile
import kotlin.jvm.JvmStatic

/**
 * xBans' developer API, the same on every server and on the proxies: who's banned or muted (from
 * memory), punishing and pardoning as xBans' commands do, histories and alts (from the
 * database), and the staff layer other plugins build on (xVanish: who's staff, staff
 * notifications, silent punishments). Players are [PlayerRef]s, so one API works everywhere.
 *
 * Get it with [XBans.api]; on Bukkit it's also in the services manager, on Velocity and
 * BungeeCord through [XBans.api] too. Every method says how it runs: from memory on any thread,
 * or a [Pending] (a `CompletableFuture` on the JVM) that completes later, off the server's
 * threads. Nothing here waits for the database on the calling thread.
 *
 * @since 1.0.0
 */
public interface XBansApi {
    /** xBans' version, such as `2.0.0`. @since 2.0.0 */
    public val version: String

    /** Where xBans' events are heard ([co.xenastudios.xbans.api.event]). On Bukkit they're Bukkit events too. @since 2.0.0 */
    public val events: EventBus

    /** Whether [player]'s account is banned on this server now. From memory, any thread. */
    public fun isBanned(player: PlayerRef): Boolean = activeBan(player) != null

    /** Whether [player] is muted on this server now, by account or by the IP they're online on. From memory, any thread. */
    public fun isMuted(player: PlayerRef): Boolean = activeMute(player) != null

    /** The ban that keeps [player]'s account off this server, or `null`. From memory, any thread. @since 2.0.0 */
    public fun activeBan(player: PlayerRef): Punishment?

    /** The mute on [player] here, by account or IP, or `null`. From memory, any thread. @since 2.0.0 */
    public fun activeMute(player: PlayerRef): Punishment?

    /**
     * Makes a punishment the way xBans' commands do: replacing one it outlasts, refusing one that's
     * covered already, firing [co.xenastudios.xbans.api.event.PunishEvent] (which can cancel it),
     * then saving it, enforcing it (kicks, the player told) and announcing it, here and on the
     * network. A plugin has no staff limits. Completes off the server's threads.
     *
     * @since 2.0.0
     */
    public fun punish(request: PunishRequest): Pending<PunishResult>

    /**
     * Lifts [player]'s [type] ([PunishmentType.BAN], [PunishmentType.MUTE], or their newest
     * [PunishmentType.WARN]) on every scope, after [co.xenastudios.xbans.api.event.PardonEvent].
     * The punishment stays in their history, lifted by [staffName]. [PunishResult.Outcome.REFUSED]
     * if there's nothing to lift.
     *
     * @since 2.0.0
     */
    public fun pardon(player: PlayerRef, type: PunishmentType, staff: PlayerRef?, staffName: String, reason: String): Pending<PunishResult>

    /** One page of [player]'s history, newest first, [perPage] a page. From the database. */
    public fun history(player: PlayerRef, page: Int, perPage: Int): Pending<HistoryPage>

    /** One page of the punishments [staff] made (`null` for the console), newest first. From the database. */
    public fun staffHistory(staff: PlayerRef?, page: Int, perPage: Int): Pending<HistoryPage>

    /** Every other account that has shared an IP with [player], the most recent first. From the database. */
    public fun alts(player: PlayerRef): Pending<List<Alt>>

    // ---- the staff layer ----

    /**
     * Whether online [player] is staff here: has `xbans.staff` ([XBans.STAFF_PERMISSION]), which operators have, or any
     * permission to punish. From memory, any thread; `false` for a player who isn't online.
     *
     * @since 2.0.0
     */
    public fun isStaff(player: PlayerRef): Boolean

    /** Every online player [isStaff] is true for. Any thread. @since 2.0.0 */
    public fun onlineStaff(): List<PlayerRef>

    /**
     * Tells staff [message] (in xBans' markup, with the theme's tags), as xBans tells them of a
     * punishment: everyone with `xbans.notify.broadcast`, or with [silent] only those with
     * `xbans.notify.silent`, and the console. [co.xenastudios.xbans.api.event.StaffNotifyEvent]
     * comes first and can change who hears it. Any thread.
     *
     * @since 2.0.0
     */
    public fun notifyStaff(message: String, silent: Boolean)
}

/**
 * Where xBans' API is found.
 *
 * @since 2.0.0
 */
public object XBans {
    /** Staff in the sense of [XBansApi.isStaff]. Operators have it. */
    public const val STAFF_PERMISSION: String = "xbans.staff"

    @Volatile
    private var running: XBansApi? = null

    /**
     * xBans' API.
     *
     * @throws IllegalStateException if xBans isn't installed and enabled: add it to your
     *   plugin's `depend` (or `softdepend`, and check [isEnabled] first)
     */
    @JvmStatic
    public fun api(): XBansApi =
        running ?: throw IllegalStateException("xBans isn't enabled. Add it to depend (or softdepend and check XBans.isEnabled() first).")

    /** Whether xBans is enabled, so [api] answers. */
    @JvmStatic
    public fun isEnabled(): Boolean = running != null

    /** Made available by xBans as it's enabled, and taken away as it's disabled: not for other plugins. */
    @JvmStatic
    public fun register(api: XBansApi?) {
        running = api
    }
}
