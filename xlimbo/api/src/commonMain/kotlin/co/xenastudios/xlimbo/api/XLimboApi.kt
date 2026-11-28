package co.xenastudios.xlimbo.api

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.event.EventBus
import kotlin.concurrent.Volatile
import kotlin.jvm.JvmStatic

/**
 * xLimbo's developer API: whether the main server is up, who's in limbo and waiting to go back,
 * sending players back (through the queue, so the main server isn't flooded) and into limbo, and
 * the hooks add-ons use to hold players or put some ahead. Players are [PlayerRef]s, so the API
 * is the same on every platform xLimbo runs on.
 *
 * Get it with [XLimbo.api]; on Paper it's also in the services manager. Every read answers from
 * memory, on any thread. A change returns a [Pending] (a `CompletableFuture` on the JVM) that
 * completes once it's done, on the server thread.
 *
 * @since 3.0.0
 */
public interface XLimboApi {
    /** xLimbo's version, such as `3.0.0`. */
    public val version: String

    /** Where xLimbo's events are heard ([co.xenastudios.xlimbo.api.event]). On Paper they're Bukkit events too. */
    public val events: EventBus

    /** How players go back: through the proxy or by a teleport on this server (`send-back.via`). */
    public fun route(): ReturnRoute

    /** Whether `server-check` is on, so [isMainServerUp] means something. Through the proxy only. */
    public fun isServerCheckEnabled(): Boolean

    /**
     * Whether the main server answered xLimbo's last status check. The check runs in the
     * background, every `server-check.interval-seconds`.
     *
     * @return `true` if it answered; `false` if it didn't, hasn't been asked yet, or server-check is off
     */
    public fun isMainServerUp(): Boolean

    /** Whether online [player] is in limbo (in its world). */
    public fun isInLimbo(player: PlayerRef): Boolean

    /** Everyone in limbo now, vanished players among them, in no order. */
    public fun playersInLimbo(): List<PlayerRef>

    /** How many are in limbo, less the vanished (xVanish): what a count shown to players should say. */
    public fun visibleCount(): Int

    /** [player]'s place in the return queue, from 1; 0 if they aren't waiting. A snapshot, updated every second. */
    public fun queuePosition(player: PlayerRef): Int

    /** How many are waiting to go back; 0 while the queue is off. A snapshot, updated every second. */
    public fun queueSize(): Int

    /**
     * Sends [player] back: while the queue is on (auto-join or server-check), they join the end of
     * it and go in turn, a few a second; otherwise at once. While server-check says the main server
     * is down, nobody is sent: everyone goes back when it comes up. Fires
     * [co.xenastudios.xlimbo.api.event.PreReturnEvent] just before they go. Any thread.
     *
     * @return `true` once they're sent or queued; `false` if they aren't in limbo, a hold or a
     *   listener kept them, or the main server is down
     */
    public fun sendBack(player: PlayerRef): Pending<Boolean>

    /** [sendBack] for everyone in limbo. Any thread. */
    public fun sendBackAll()

    /**
     * Sends online [player] into limbo on this server, as `/xlimbo send` does: they're teleported
     * onto the floor, and with `send-back.via: teleport`, where they were is kept so
     * `destination: previous` takes them back there. Fires
     * [co.xenastudios.xlimbo.api.event.PreEnterLimboEvent] first. Any thread.
     *
     * @return `true` once they're there; `false` if they already were, it was cancelled, or there's no limbo world yet
     */
    public fun sendToLimbo(player: PlayerRef): Pending<Boolean>

    /** Adds a [HoldHook] that can keep players in limbo. [owner] is the plugin's name; [unregisterAll] takes an owner's away. */
    public fun registerHold(owner: String, hook: HoldHook)

    /** Adds a [PriorityHook] that puts some players ahead in the return queue. The highest any hook says wins. */
    public fun registerPriority(owner: String, hook: PriorityHook)

    /** Takes away every hook [owner] registered, as it disables. */
    public fun unregisterAll(owner: String)
}

/**
 * Where xLimbo's API is found:
 *
 * ```java
 * XLimboApi api = XLimbo.api();
 * ```
 *
 * @since 3.0.0
 */
public object XLimbo {
    @Volatile
    private var running: XLimboApi? = null

    /**
     * xLimbo's API.
     *
     * @throws IllegalStateException if xLimbo isn't installed and enabled: add it to your plugin's
     *   `depend` (or `softdepend`, and check [isEnabled] first)
     */
    @JvmStatic
    public fun api(): XLimboApi =
        running ?: throw IllegalStateException("xLimbo isn't enabled. Add it to depend (or softdepend and check XLimbo.isEnabled() first).")

    /** Whether xLimbo is enabled, so [api] answers. */
    @JvmStatic
    public fun isEnabled(): Boolean = running != null

    /** Made available by xLimbo as it's enabled, and taken away as it's disabled: not for other plugins. */
    @JvmStatic
    public fun register(api: XLimboApi?) {
        running = api
    }

    /** The name of the destination xLimbo adds to xTeleport: `XTeleport.api().teleport(player, "xlimbo", options)` sends a player into limbo. */
    public const val DESTINATION: String = "xlimbo"
}
