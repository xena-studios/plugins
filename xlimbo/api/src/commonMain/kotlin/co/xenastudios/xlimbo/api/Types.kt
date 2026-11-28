package co.xenastudios.xlimbo.api

import co.xenastudios.core.api.PlayerRef

/**
 * How xLimbo sends players back: `send-back.via` in its config.
 *
 * @since 3.0.0
 */
public enum class ReturnRoute {
    /** Through the proxy (BungeeCord, Waterfall or Velocity), to `main-server`: xLimbo is the network's fallback server. */
    PROXY,

    /** A teleport on this server, to `send-back.destination`, through xTeleport when it's installed: xLimbo is a world on this server. */
    TELEPORT,
}

/**
 * Why a player is in limbo, as far as xLimbo can tell. Proxies don't tell the server they send a
 * player to why they did.
 *
 * @since 3.0.0
 */
public enum class EnterReason {
    /** Server-check says the main server is down. */
    MAIN_SERVER_DOWN,

    /** xLimbo sent them back moments ago and they're here again: the main server refused them (full, banned, kicked, or not ready). */
    REFUSED,

    /** Sent into limbo on this server: `/xlimbo send`, the API's [XLimboApi.sendToLimbo], or xTeleport's `xlimbo` destination. */
    SENT,

    /** Anything else: kicked to limbo by the proxy, sent on purpose, or joined it directly. */
    UNKNOWN,
}

/**
 * What's sending a player back.
 *
 * @since 3.0.0
 */
public enum class ReturnCause {
    /** The return queue: auto-join, server-check seeing the main server up, or [XLimboApi.sendBack] while the queue is on. */
    QUEUE,

    /** The player ran `/join`. Cancelling it doesn't use up their cooldown. */
    JOIN_COMMAND,

    /** Another plugin's [XLimboApi.sendBack] while the queue is off, so sent at once. */
    PLUGIN,

    /** Staff's `/xlimbo back`. */
    ADMIN,
}

/**
 * Keeps players in limbo, for an add-on ([XLimboApi.registerHold]): a login plugin holding players
 * until they've logged in, a queue plugin of its own. Asked just before each player would be sent
 * back, by the queue, `/join` and the API alike; a held player stays, and the queue asks again on
 * its next pass.
 *
 * @since 3.0.0
 */
public fun interface HoldHook {
    /** Whether [player] stays in limbo for now. Called on the server thread, often: answer from memory. */
    public fun holds(player: PlayerRef): Boolean
}

/**
 * Puts some players ahead in the return queue ([XLimboApi.registerPriority]), such as donors or
 * staff. Players with a higher number go first; equal numbers keep the order they joined the line.
 *
 * @since 3.0.0
 */
public fun interface PriorityHook {
    /** [player]'s priority: 0 for none, higher goes sooner. Called on the server thread, once a second while they wait: from memory. */
    public fun priority(player: PlayerRef): Int
}
