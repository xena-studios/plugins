package co.xenastudios.xlimbo

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.config.Config
import co.xenastudios.core.common.text.Component

/**
 * What xLimbo needs from the server it runs on: written once per platform (on Paper,
 * `BukkitLimboServer`). Everything here runs on the server thread unless it says otherwise; xLimbo
 * is Paper only, where that's the main thread.
 *
 * The world itself (making it, its floor, keeping it as the config says, its protections) is the
 * adapter's: [sync] hands it the config, [listen] each switch that's on.
 */
public interface LimboServer {
    /** The players online now. Any thread. */
    public fun online(): List<PlayerRef>

    /** An online player's name, or `null` if they aren't online. Any thread. */
    public fun name(player: PlayerRef): String?

    /** Whether online [player] is in the limbo world. Any thread. */
    public fun isInLimbo(player: PlayerRef): Boolean

    /** Whether the limbo world exists, so players can be put in it. Any thread. */
    public fun isReady(): Boolean

    /** Whether online [player] has [node]. */
    public fun hasPermission(player: PlayerRef, node: String): Boolean

    /** Sends [message] to online [player]. Any thread. */
    public fun send(player: PlayerRef, message: Component)

    /** Shows [message] on online [player]'s action bar. */
    public fun actionBar(player: PlayerRef, message: Component)

    /** Clears [player]'s chat (`join.clear-chat-on-join`). */
    public fun clearChat(player: PlayerRef)

    /** Creative and flight, as [config] says: on arrival, and on reload for those already here. */
    public fun equip(player: PlayerRef, config: Config)

    /** Asks the proxy to move [player] to [server], a name from the proxy's config. */
    public fun connect(player: PlayerRef, server: String)

    /**
     * Teleports [player] out of limbo to [destination] (`previous`, `spawn`, `warp:<name>`, or a
     * destination's name in xTeleport), through xTeleport when it's installed. Completes `true`
     * once they're there.
     */
    public fun teleportBack(player: PlayerRef, destination: String): Pending<Boolean>

    /** Teleports [player] into limbo, keeping where they were for `previous`. Completes `true` once they're there. */
    public fun enterLimbo(player: PlayerRef): Pending<Boolean>

    /** Makes the limbo world match [config]: creating, rebuilding, switching or adjusting it. On the server thread. */
    public fun sync(config: Config)

    /** Turns on [switch] ([Switch]) until the returned handle is closed. */
    public fun listen(switch: Int, plugin: XLimboPlugin): AutoCloseable

    /**
     * Whether [target] is hidden from [viewer] (the console for `null`) by a vanish plugin: xVanish's
     * levels when it's installed, otherwise the family's player cache. Not xLimbo's own
     * `hide-others`, which hides players from each other only so nobody tracks anyone. Any thread.
     */
    public fun vanishedFrom(viewer: PlayerRef?, target: PlayerRef): Boolean
}

/** The switches an adapter turns on with [LimboServer.listen], each its own module. */
public object Switch {
    /** Where arrivals land, what they're given, the join, quit and death messages: always on. */
    public const val ARRIVALS: Int = 0
    public const val HIDE_OTHERS: Int = 1
    public const val FLOOR: Int = 2
    public const val VOID: Int = 3
    public const val VOID_DAMAGE: Int = 4
    public const val PORTALS: Int = 5
    public const val LIQUIDS: Int = 6
    public const val GRAVITY: Int = 7
    public const val FIRE: Int = 8
    public const val EXPLOSIONS: Int = 9
    public const val MOBS: Int = 10
    public const val REDSTONE: Int = 11
    public const val ITEMS: Int = 12
    public const val ENTITY_ITEMS: Int = 13
    public const val BUILD_GUARD: Int = 14
    public const val CLEANUP: Int = 15

    /** Each switch's module key, as `/xlimbo debug` names it. */
    public val NAMES: List<String> = listOf(
        "arrivals", "hide-others", "protect.floor", "protect.void", "protect.void-damage", "protect.portals", "protect.liquids",
        "protect.gravity", "protect.fire", "protect.explosions", "protect.mobs", "protect.redstone", "protect.items",
        "protect.entities", "protect.build-guard", "protect.cleanup",
    )
}
