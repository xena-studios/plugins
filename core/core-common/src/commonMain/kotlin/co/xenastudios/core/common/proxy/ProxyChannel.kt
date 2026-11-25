package co.xenastudios.core.common.proxy

import co.xenastudios.core.api.event.Subscription
import kotlin.time.Instant

/**
 * A plugin's line between its server side and its proxy side: the plugin messaging channel
 * `xena:<plugin>` (DECISIONS.md, "The proxy layer"). A message is a [topic] and a small payload
 * (up to [MAX_PAYLOAD] bytes), signed with the network's secret, so a server never acts on a
 * message a player made up: players can send plugin messages too, and on the server's side one
 * from the proxy and one from the player's client arrive on the same connection.
 *
 * Get one from the platform (`BukkitPlatform.proxyChannel`, `VelocityPlatform.proxyChannel`,
 * `BungeePlatform.proxyChannel`), or from core-testing's `FakeNetwork` in tests.
 *
 * **A plugin message travels on a player's connection.** A server sends to the proxy through any
 * player on it, and the proxy reaches a server through a player on that server, so with nobody
 * on a server nothing can be sent either way: [send] says [Sent.NO_PLAYER] and the message is
 * dropped, not queued (what's queued until someone joins is usually stale by then). For what
 * must arrive whether or not anyone is online, or reach a server that was down, use the change
 * feed in the family's shared database (`ChangeFeed`, network mode), which every server reads
 * every few seconds: plugin messages are for what's fast and only matters now, like a chat
 * line; the feed for what must not be lost, like a ban.
 *
 * Listeners run off the server's threads ([co.xenastudios.core.common.scheduler.Where.Async]),
 * so a listener that touches players schedules that work where it belongs.
 */
public interface ProxyChannel {
    /** The channel's name, `xena:<plugin>`. */
    public val name: String

    /** Which side of the network this end is on. */
    public val side: ProxySide

    /**
     * Whether a secret is set, so messages can be signed and checked. Without one nothing is
     * sent or accepted, and the console has said where the secret goes.
     */
    public val ready: Boolean

    /**
     * Sends [payload] under [topic] to [to]: [Peer.Proxy] from a server; a [Peer.Server] or
     * [Peer.AllServers] from the proxy. Returns at once, without waiting for an answer; any
     * thread. A topic is 1 to 64 of `a-z`, `0-9`, `.`, `_` and `-`.
     */
    public fun send(to: Peer, topic: String, payload: ByteArray): Sent

    /** [listener] hears every message under [topic], until the subscription is cancelled or the channel closed. */
    public fun listen(topic: String, listener: ProxyListener): Subscription

    /** Stops sending and hearing, as the plugin is disabled. */
    public fun close()

    public companion object {
        /** The most bytes a payload can have: what's left of the 32,767 bytes a server takes in one plugin message, after the frame. */
        public const val MAX_PAYLOAD: Int = 30_000

        /** The channel of the plugin with this [id]: `xena:zchat`. */
        public fun nameOf(id: String): String = "xena:" + id.lowercase()
    }
}

/** Which side of a network a [ProxyChannel] is on. */
public enum class ProxySide {
    /** A server behind the proxy: Paper, Spigot, Fabric. */
    SERVER,

    /** The proxy: Velocity, BungeeCord. */
    PROXY,
}

/** Where a message goes, or came from. */
public sealed class Peer {
    /** The proxy: where a server sends, and where a server's messages come from. */
    public data object Proxy : Peer()

    /** One server behind the proxy, by the name the proxy's config gives it (`lobby`). */
    public data class Server(public val name: String) : Peer()

    /** Every server behind the proxy that has a player on it now: only the proxy sends to it. */
    public data object AllServers : Peer()
}

/** What became of a message given to [ProxyChannel.send]. */
public enum class Sent {
    /** On its way. Plugin messages aren't acknowledged, so a listener that must know sends an answer. */
    SENT,

    /** Nobody is on the server, or behind the proxy, to carry it: see [ProxyChannel]. Nothing was sent. */
    NO_PLAYER,

    /** No secret is set ([ProxyChannel.ready] is false), so it couldn't be signed. Nothing was sent. */
    NO_SECRET,

    /** The payload is over [ProxyChannel.MAX_PAYLOAD], or the topic isn't one. Nothing was sent. */
    REFUSED,

    /** The proxy has no server of that name. */
    UNKNOWN_SERVER,

    /** Sent from the wrong side: a server can only send to [Peer.Proxy], the proxy only to servers. */
    WRONG_SIDE,

    /** The channel is closed: the plugin is stopping. */
    CLOSED,
}

/** A message that arrived on a [ProxyChannel], signed and checked. */
public class ProxyMessage(
    /** Who sent it: [Peer.Proxy] on a server, the [Peer.Server] it came from on the proxy. */
    public val from: Peer,
    /** Its topic. */
    public val topic: String,
    /** Its bytes, read with [ProxyPayload.reader]. Don't change them: every listener of the topic gets the same array. */
    public val payload: ByteArray,
    /** When the sender sent it, by the sender's clock. */
    public val sentAt: Instant,
)

/** Hears messages under one topic: see [ProxyChannel.listen]. */
public fun interface ProxyListener {
    public fun received(message: ProxyMessage)
}
