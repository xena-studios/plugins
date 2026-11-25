package co.xenastudios.core.testing

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.platform.Platform
import co.xenastudios.core.common.proxy.FrameSigner
import co.xenastudios.core.common.proxy.Peer
import co.xenastudios.core.common.proxy.ProxyChannel
import co.xenastudios.core.common.proxy.ProxyEndpoint
import co.xenastudios.core.common.proxy.ProxySide
import co.xenastudios.core.common.proxy.ProxyTransport
import co.xenastudios.core.common.proxy.Sent

/**
 * A proxy and its servers for tests, with plugin messages between them as a real network has
 * them: carried by players, so a server with nobody on it can neither send nor be reached; signed
 * with the network's secret; and able to be forged by a player's client, which a server must
 * ignore. Each end is a real [ProxyEndpoint] on the [Platform] it's given (usually a
 * [FakePlatform], [co.xenastudios.core.common.platform.Threading.PROXY] for the proxy), so what a
 * plugin's listeners do runs on that platform's scheduler: run it ([VirtualScheduler.runPending])
 * to deliver.
 *
 * ```kotlin
 * val network = FakeNetwork()
 * val proxy = FakePlatform(Threading.PROXY)
 * val lobby = FakePlatform(Threading.MAIN_THREAD)
 * val proxyChannel = network.proxyChannel(proxy, "zchat")
 * val lobbyChannel = network.serverChannel("lobby", lobby, "zchat")
 * network.join("lobby", steve)
 * lobbyChannel.send(Peer.Proxy, "chat", ProxyPayload.write { text("hi") })
 * proxy.scheduler.runPending()
 * ```
 *
 * The signatures are a test's own keyed checksum, not HMAC-SHA256 (core-jvm's), which needs the
 * JDK: they're enough to tell one secret from another, and the frame, direction, time and repeat
 * checks are the real ones.
 */
public class FakeNetwork(secret: String = "the network's secret") {
    private val signer = signer(secret)
    private val proxies = HashMap<String, ProxyEndpoint>()
    private val servers = HashMap<String, HashMap<String, ProxyEndpoint>>()
    private val online = HashMap<String, MutableSet<PlayerRef>>()

    /** Every message that crossed the network, as the frames' bytes, oldest first: what a player could capture, if they could see it. */
    public val wire: MutableList<ByteArray> = mutableListOf()

    /** The proxy's end of the plugin [id]'s channel, `xena:<id>`, on [platform]. */
    public fun proxyChannel(platform: Platform, id: String, secret: String? = null): ProxyChannel {
        val name = ProxyChannel.nameOf(id)
        val endpoint = ProxyEndpoint(name, ProxySide.PROXY, platform, secret?.let(::signer) ?: signer)
        endpoint.attach(ProxyEnd(name))
        proxies[name] = endpoint
        return endpoint
    }

    /** The end of the plugin [id]'s channel on the server the proxy calls [server], on [platform]; [secret] if that server has another. */
    public fun serverChannel(server: String, platform: Platform, id: String, secret: String? = null): ProxyChannel {
        val name = ProxyChannel.nameOf(id)
        val endpoint = ProxyEndpoint(name, ProxySide.SERVER, platform, secret?.let(::signer) ?: signer)
        endpoint.attach(ServerEnd(server, name))
        servers.getOrPut(server) { HashMap() }[name] = endpoint
        return endpoint
    }

    /** [player] is now on [server]: messages to and from it have someone to carry them. */
    public fun join(server: String, player: PlayerRef) {
        for (players in online.values) players -= player
        online.getOrPut(server) { LinkedHashSet() } += player
    }

    /** [player] left the network. */
    public fun leave(player: PlayerRef) {
        for (players in online.values) players -= player
    }

    /**
     * A player's client on [server] sends [bytes] on the plugin [id]'s channel, as a hacked client
     * can: the server gets them as if the proxy had sent them, as a real server does, and must
     * ignore what isn't signed with the network's secret.
     */
    public fun forge(server: String, id: String, bytes: ByteArray) {
        servers[server]?.get(ProxyChannel.nameOf(id))?.receive(Peer.Proxy, bytes)
    }

    /** Delivers [bytes] to the plugin [id]'s proxy end as if [server] had sent them: for replaying what's on the [wire]. */
    public fun replayToProxy(server: String, id: String, bytes: ByteArray) {
        proxies[ProxyChannel.nameOf(id)]?.receive(Peer.Server(server), bytes)
    }

    private fun carried(server: String): Boolean = online[server].orEmpty().isNotEmpty()

    private inner class ProxyEnd(private val name: String) : ProxyTransport {
        override fun send(to: Peer, frame: ByteArray): Sent = when (to) {
            is Peer.Server -> {
                val end = servers[to.name]?.get(name)
                when {
                    to.name !in servers && to.name !in online -> Sent.UNKNOWN_SERVER
                    !carried(to.name) -> Sent.NO_PLAYER
                    else -> {
                        wire += frame
                        end?.receive(Peer.Proxy, frame)
                        Sent.SENT
                    }
                }
            }
            Peer.AllServers -> {
                val reached = servers.filterKeys(::carried)
                for (end in reached.values) {
                    wire += frame
                    end[name]?.receive(Peer.Proxy, frame)
                }
                if (reached.isEmpty()) Sent.NO_PLAYER else Sent.SENT
            }
            Peer.Proxy -> Sent.WRONG_SIDE
        }

        override fun close() {
            proxies.remove(name)
        }
    }

    private inner class ServerEnd(private val server: String, private val name: String) : ProxyTransport {
        override fun send(to: Peer, frame: ByteArray): Sent {
            if (to != Peer.Proxy) return Sent.WRONG_SIDE
            if (!carried(server)) return Sent.NO_PLAYER
            wire += frame
            proxies[name]?.receive(Peer.Server(server), frame)
            return Sent.SENT
        }

        override fun close() {
            servers[server]?.remove(name)
        }
    }

    public companion object {
        /** A test's signer for [secret]: a keyed checksum, 16 bytes, different for every secret. */
        public fun signer(secret: String): FrameSigner {
            val key = secret.encodeToByteArray()
            return FrameSigner { data ->
                val out = ByteArray(16)
                var hash = -0x340d631b7bdddcdbL
                for (round in 0 until 16) {
                    for (b in key) hash = (hash xor (b.toLong() and 0xFF)) * 0x100000001b3L
                    for (b in data) hash = (hash xor (b.toLong() and 0xFF)) * 0x100000001b3L
                    out[round] = (hash ushr 24).toByte()
                }
                out
            }
        }
    }
}
