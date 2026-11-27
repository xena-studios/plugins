package co.xenastudios.xvanish.api

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.event.EventBus
import kotlin.concurrent.Volatile
import kotlin.jvm.JvmField
import kotlin.jvm.JvmStatic

/**
 * xVanish's developer API, the same on every platform: who's vanished, who can see whom, and
 * vanishing or showing a player. Players are [PlayerRef]s, so one API works everywhere.
 *
 * Get it with [XVanish.api]; on Bukkit it's also in the services manager. Every read answers
 * from memory, on any thread. A change returns a [Pending] (a `CompletableFuture` on the JVM)
 * that completes once the player is hidden or shown, on their own thread (Folia's entity
 * scheduler).
 *
 * @since 1.0.0
 */
public interface XVanishApi {
    /** xVanish's version, such as `1.0.0`. */
    public val version: String

    /** Where xVanish's events are heard ([co.xenastudios.xvanish.api.event]). On Bukkit they're Bukkit events too. */
    public val events: EventBus

    /** Whether [player] is vanished: online here, or (on a network) known vanished. From memory, any thread. */
    public fun isVanished(player: PlayerRef): Boolean

    /**
     * Whether [viewer] can see [target]: always themselves, anyone who isn't vanished, and a
     * vanished player only with `xvanish.see` and a level at least the target's. From memory,
     * any thread. Other plugins should ask this (or Bukkit's `canSee`) before naming a player.
     */
    public fun canSee(viewer: PlayerRef, target: PlayerRef): Boolean

    /** The vanished players online on this server, in no order. From memory, any thread. */
    public fun vanished(): List<PlayerRef>

    /** How many players are online here, less the vanished: what a player list should show. From memory, any thread. */
    public fun visibleOnlineCount(): Int

    /**
     * [player]'s vanish level: the highest `xvanish.level.<n>` they have, 1 without one. A
     * vanished player is seen only by `xvanish.see` holders of at least their level. From
     * memory: worked out as they join, vanish and on reload.
     */
    public fun level(player: PlayerRef): Int

    /**
     * Vanishes or shows online [player], as `/vanish` does, firing
     * [co.xenastudios.xvanish.api.event.VanishEvent] first (cancel it to refuse) and
     * [co.xenastudios.xvanish.api.event.VanishedEvent] or [co.xenastudios.xvanish.api.event.UnvanishedEvent]
     * after. [silent] leaves out the fake join or quit message. Any thread.
     *
     * @return `true` once the change is made; `false` if it was refused, [player] isn't online,
     *   or they already were as asked
     */
    public fun setVanished(player: PlayerRef, vanished: Boolean, silent: Boolean): Pending<Boolean>
}

/** Why a player is vanishing or showing, in the events. */
public enum class VanishCause {
    /** `/vanish`, by them or by staff. */
    COMMAND,

    /** Joining: vanished from before (a relog or a restart), or with `xvanish.join`. */
    JOIN,

    /** Another plugin, through [XVanishApi.setVanished]. */
    API,

    /** Another server of the network (network mode). */
    NETWORK,
}

/**
 * Where to get xVanish's API: `XVanish.api()`, from Kotlin and Java alike.
 *
 * @since 1.0.0
 */
public object XVanish {
    /** Sees vanished players (of a level up to the viewer's own). */
    public const val SEE_PERMISSION: String = "xvanish.see"

    /** `xvanish.level.<n>`: a vanish level. */
    public const val LEVEL_PERMISSION: String = "xvanish.level."

    /**
     * The metadata key on Bukkit players while they're vanished (`player.getMetadata("vanished")`,
     * `true`), which most plugins already check, as SuperVanish set it.
     */
    @JvmField
    public val METADATA_KEY: String = "vanished"

    @Volatile
    private var running: XVanishApi? = null

    /**
     * xVanish's API.
     *
     * @throws IllegalStateException if xVanish isn't installed and enabled: add it to your
     *   plugin's `depend` (or `softdepend`, and check [isEnabled] first)
     */
    @JvmStatic
    public fun api(): XVanishApi =
        running ?: throw IllegalStateException("xVanish isn't enabled. Add it to depend (or softdepend and check XVanish.isEnabled() first).")

    /** Whether xVanish is enabled, so [api] answers. */
    @JvmStatic
    public fun isEnabled(): Boolean = running != null

    /** Made available by xVanish as it's enabled, and taken away as it's disabled: not for other plugins. */
    @JvmStatic
    public fun register(api: XVanishApi?) {
        running = api
    }
}
