package co.xenastudios.core.sample

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.platform.Platform
import co.xenastudios.core.common.proxy.Peer
import co.xenastudios.core.common.proxy.ProxyChannel
import co.xenastudios.core.common.proxy.ProxyPayload
import co.xenastudios.core.common.proxy.Sent
import co.xenastudios.core.common.scheduler.Where
import kotlin.time.Duration.Companion.milliseconds

/**
 * xSample's network side, the same on every platform: when a player joins a server behind a
 * proxy, the server says `hello` to the proxy on `xena:xsample`, and the proxy answers that server
 * with `welcome`. Each side logs what it heard, which the proxy boot tests read
 * (`NETWORK HELLO`, `NETWORK PASS`); a real plugin does the same with its own topics (zChat's chat
 * lines, xBans' kicks).
 */
public object SampleNetwork {
    /** The `hello` topic: a server to the proxy, with the player who joined and their name. */
    public const val HELLO: String = "hello"

    /** The `welcome` topic: the proxy's answer, to the server the hello came from. */
    public const val WELCOME: String = "welcome"

    // The payload's layout, first in every message, so a later version can change it.
    private const val VERSION = 1

    // A player's connection carries plugin messages a moment after they join, once the proxy has
    // registered its channels for them: try for ten seconds.
    private val RETRY = 500.milliseconds
    private const val TRIES = 20

    /** A server's side: say hello for each player who joins with [joined], and log the proxy's welcome. */
    public class Server(private val channel: ProxyChannel, private val platform: Platform) {
        init {
            channel.listen(WELCOME) { message ->
                val read = ProxyPayload.read(message.payload) { Triple(int(), player(), text()) }
                if (read == null || read.first != VERSION) {
                    platform.log.warn("A welcome from the proxy in a layout this version doesn't read")
                    return@listen
                }
                platform.log.info("NETWORK PASS: the proxy welcomed ${read.third} on ${channel.name}")
            }
        }

        /** [player], called [name], joined this server: says hello to the proxy once their connection can carry it. */
        public fun joined(player: PlayerRef, name: String) {
            var tries = 0
            platform.scheduler.run(Where.Async, period = RETRY) { task ->
                when (channel.send(Peer.Proxy, HELLO, ProxyPayload.write { int(VERSION); player(player); text(name) })) {
                    Sent.NO_PLAYER -> if (++tries >= TRIES) task.cancel()
                    // Sent, or never will be: a server on its own has no secret, nor a proxy to say it to.
                    else -> task.cancel()
                }
            }
        }
    }

    /** The proxy's side: answer each server's hello with a welcome, and log it. */
    public class Proxy(private val channel: ProxyChannel, private val platform: Platform) {
        init {
            channel.listen(HELLO) { message ->
                val read = ProxyPayload.read(message.payload) { Triple(int(), player(), text()) }
                val from = message.from as? Peer.Server ?: return@listen
                if (read == null || read.first != VERSION) {
                    platform.log.warn("A hello from ${from.name} in a layout this version doesn't read")
                    return@listen
                }
                platform.log.info("NETWORK HELLO from ${from.name} for ${read.third}")
                val sent = channel.send(from, WELCOME, ProxyPayload.write { int(VERSION); player(read.second); text(read.third) })
                if (sent != Sent.SENT) platform.log.warn("Couldn't welcome ${read.third} on ${from.name}: $sent")
            }
        }
    }
}
