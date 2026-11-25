package co.xenastudios.core.common.proxy

import co.xenastudios.core.api.event.Subscription
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.isFatal
import co.xenastudios.core.common.locked
import co.xenastudios.core.common.platform.Platform
import co.xenastudios.core.common.scheduler.Where
import kotlin.concurrent.Volatile
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * How a platform moves a [ProxyChannel]'s bytes: plugin messages on Bukkit's messenger,
 * Velocity's channel registrar or BungeeCord's. The platform hands what arrives to
 * [ProxyEndpoint.receive].
 */
public interface ProxyTransport {
    /** Sends a signed frame to [to]: [Sent.SENT], or why it couldn't ([Sent.NO_PLAYER], [Sent.UNKNOWN_SERVER]). */
    public fun send(to: Peer, frame: ByteArray): Sent

    /** Stops sending and hearing on the platform's channel. */
    public fun close()
}

/**
 * The [ProxyChannel] every platform hands out: it signs, checks and routes; the platform's
 * [ProxyTransport] only moves bytes. A message is acted on only when it's well formed, signed
 * with this end's secret for this channel, from the other side of the network, sent within five
 * minutes of now by both clocks, and not already heard (DECISIONS.md, "The proxy layer").
 *
 * A refused message is dropped and the console told why, the first time and then at most once a
 * minute for each reason, so a player flooding the channel can't flood the log.
 *
 * The platform gives the [signer] from the network's secret, or `null` until one is set, when
 * nothing is sent or accepted; [secret] changes it, on a reload.
 */
public class ProxyEndpoint(
    override val name: String,
    override val side: ProxySide,
    private val platform: Platform,
    signer: FrameSigner?,
) : ProxyChannel {

    @Volatile
    private var signer: FrameSigner? = signer

    @Volatile
    private var transport: ProxyTransport? = null

    @Volatile
    private var closed = false

    private val lock = Any()

    // Copied on write: a delivery reads it without the lock.
    @Volatile
    private var listeners: Map<String, List<Entry>> = emptyMap()

    // The ids heard in the last window, oldest first, to drop a message heard twice.
    private val heard = LinkedHashMap<Long, Duration>()
    private val warned = HashMap<ProxyFrame.Problem, Duration>()
    private val refusedSince = HashMap<ProxyFrame.Problem, Int>()

    override val ready: Boolean get() = signer != null && !closed

    /** Sets what moves the bytes: the platform calls it once, as it makes the channel. */
    public fun attach(transport: ProxyTransport) {
        this.transport = transport
    }

    /** Changes the secret: a new [FrameSigner], or `null` to stop sending and accepting. */
    public fun secret(signer: FrameSigner?) {
        this.signer = signer
    }

    override fun send(to: Peer, topic: String, payload: ByteArray): Sent {
        if (closed) return Sent.CLOSED
        val allowed = when (side) {
            ProxySide.SERVER -> to == Peer.Proxy
            ProxySide.PROXY -> to != Peer.Proxy
        }
        if (!allowed) return Sent.WRONG_SIDE
        if (!ProxyFrame.isTopic(topic) || payload.size > ProxyChannel.MAX_PAYLOAD) return Sent.REFUSED
        val signer = signer ?: return Sent.NO_SECRET
        val transport = transport ?: return Sent.CLOSED
        val frame = ProxyFrame(side == ProxySide.PROXY, topic, platform.clock.now(), Random.nextLong(), payload)
        return transport.send(to, ProxyFrame.encode(name, frame, signer))
    }

    override fun listen(topic: String, listener: ProxyListener): Subscription {
        require(ProxyFrame.isTopic(topic)) { "\"$topic\" isn't a topic: 1 to 64 of a-z, 0-9, '.', '_' and '-'" }
        val entry = Entry(topic, listener)
        locked(lock) {
            if (!closed) listeners = listeners + (topic to (listeners[topic].orEmpty() + entry))
        }
        return entry
    }

    /**
     * What arrived on the channel [from] a peer: the platform calls it with every plugin message
     * on the channel from the other side. On a server, anything on the channel, which a player's
     * client could have sent; on the proxy, only what came from a server's connection, never a
     * player's (the platform drops those). Any thread; returns at once.
     */
    public fun receive(from: Peer, bytes: ByteArray) {
        if (closed) return
        val signer = signer ?: return refused(ProxyFrame.Problem.SIGNATURE, from)
        val frame = when (val decoded = ProxyFrame.decode(name, bytes, signer)) {
            is Outcome.Ok -> decoded.value
            is Outcome.Err -> return refused(decoded.error, from)
        }
        // A server hears only the proxy; the proxy only its servers.
        if (frame.fromProxy != (side == ProxySide.SERVER)) return refused(ProxyFrame.Problem.DIRECTION, from)
        val skew = platform.clock.now() - frame.sentAt
        if (skew.absoluteValue > WINDOW) return refused(ProxyFrame.Problem.STALE, from)
        if (!firstTime(frame.id)) return refused(ProxyFrame.Problem.REPEATED, from)
        val heard = listeners[frame.topic] ?: return
        val message = ProxyMessage(from, frame.topic, frame.payload, frame.sentAt)
        platform.scheduler.run(Where.Async) {
            for (entry in heard) {
                if (!entry.isActive) continue
                try {
                    entry.listener.received(message)
                } catch (e: Throwable) {
                    if (isFatal(e)) throw e
                    platform.log.error("A listener of $name's ${frame.topic} failed", e)
                }
            }
        }
    }

    override fun close() {
        val transport = locked(lock) {
            closed = true
            listeners = emptyMap()
            this.transport.also { this.transport = null }
        }
        transport?.close()
    }

    private fun firstTime(id: Long): Boolean = locked(lock) {
        val now = platform.clock.elapsed()
        val iterator = heard.entries.iterator()
        while (iterator.hasNext()) {
            val oldest = iterator.next()
            if (now - oldest.value <= WINDOW * 2 && heard.size < MAX_HEARD) break
            iterator.remove()
        }
        if (heard.containsKey(id)) false else {
            heard[id] = now
            true
        }
    }

    private fun refused(problem: ProxyFrame.Problem, from: Peer) {
        val now = platform.clock.elapsed()
        val (say, count) = locked(lock) {
            val count = (refusedSince[problem] ?: 0) + 1
            val last = warned[problem]
            if (last != null && now - last < 1.minutes) {
                refusedSince[problem] = count
                false to count
            } else {
                warned[problem] = now
                refusedSince[problem] = 0
                true to count
            }
        }
        if (!say) return
        val what = when (from) {
            Peer.Proxy -> "the proxy"
            is Peer.Server -> "server ${from.name}"
            Peer.AllServers -> "a server"
        }
        val times = if (count > 1) " ($count messages since the last line)" else ""
        platform.log.warn("Ignored a message on $name from $what$times: ${problem.reason}")
    }

    private inner class Entry(val topic: String, val listener: ProxyListener) : Subscription {
        @Volatile
        private var active = true

        override val isActive: Boolean get() = active && !closed

        override fun cancel() {
            locked(lock) {
                if (!active) return@locked
                active = false
                val left = listeners[topic].orEmpty().filter { it !== this }
                listeners = if (left.isEmpty()) listeners - topic else listeners + (topic to left)
            }
        }
    }

    public companion object {
        /** How far a message's time may be from now, either way, by the two machines' clocks. */
        public val WINDOW: Duration = 5.minutes

        // Ids kept to spot a repeat: a busy network's ten minutes of chat, then the oldest go.
        private const val MAX_HEARD = 50_000
    }
}
