package co.xenastudios.core.bungee

import co.xenastudios.core.common.proxy.Peer
import co.xenastudios.core.common.proxy.ProxyEndpoint
import co.xenastudios.core.common.proxy.ProxySide
import co.xenastudios.core.common.proxy.ProxyTransport
import co.xenastudios.core.common.proxy.Sent
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.jvm.proxy.ProxySecret
import net.md_5.bungee.api.connection.ProxiedPlayer
import net.md_5.bungee.api.connection.Server
import net.md_5.bungee.api.event.PluginMessageEvent
import net.md_5.bungee.api.plugin.Listener
import net.md_5.bungee.event.EventHandler
import net.md_5.bungee.event.EventPriority
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * A plugin's `xena:<plugin>` channel on BungeeCord: registered with the proxy, which tells each
 * server it listens there as a player connects, so servers can send on it. Every message on it is
 * cancelled here and goes no further, whichever way it was going: a server's never reaches a
 * player, and a player's never reaches a server, with a warning the first time a player sends one
 * (DECISIONS.md, "The proxy layer").
 */
internal class BungeeChannel private constructor(
    private val platform: BungeePlatform,
    private val endpoint: ProxyEndpoint,
) : ProxyTransport, Listener {

    private val warned = ConcurrentHashMap.newKeySet<UUID>()

    // First, so no other plugin forwards a message before it's cancelled.
    @EventHandler(priority = EventPriority.LOWEST)
    fun onMessage(event: PluginMessageEvent) {
        if (event.tag != endpoint.name) return
        event.isCancelled = true
        when (val sender = event.sender) {
            is Server -> endpoint.receive(Peer.Server(sender.info.name), event.data)
            is ProxiedPlayer -> if (warned.add(sender.uniqueId)) {
                platform.log.warn("${sender.name} sent a message on ${endpoint.name}, where only servers send; it was dropped")
            }
        }
    }

    fun start() {
        platform.server.registerChannel(endpoint.name)
        platform.server.pluginManager.registerListener(platform.plugin, this)
    }

    override fun send(to: Peer, frame: ByteArray): Sent = when (to) {
        is Peer.Server -> {
            val server = platform.server.getServerInfo(to.name)
            when {
                server == null -> Sent.UNKNOWN_SERVER
                // Not queued: a message held until someone joins is stale by then (ProxyChannel).
                server.sendData(endpoint.name, frame, false) -> Sent.SENT
                else -> Sent.NO_PLAYER
            }
        }
        Peer.AllServers -> {
            var sent = false
            for (server in platform.server.servers.values) {
                if (server.players.isNotEmpty()) sent = server.sendData(endpoint.name, frame, false) || sent
            }
            if (sent) Sent.SENT else Sent.NO_PLAYER
        }
        Peer.Proxy -> Sent.WRONG_SIDE
    }

    override fun close() {
        platform.server.pluginManager.unregisterListener(this)
        platform.server.unregisterChannel(endpoint.name)
    }

    companion object {
        /** The channel [name], signed with `plugins/Xena/proxy.secret`, made on the first start if there's none. */
        fun open(platform: BungeePlatform, name: String): ProxyEndpoint {
            val secret = try {
                ProxySecret.read(platform.familyPath) ?: ProxySecret.create(platform.familyPath).also {
                    platform.log.info(
                        "Made ${it.source}, the secret that signs messages between this proxy and its servers: " +
                            "copy it to plugins/Xena/ (config/xena/ on Fabric) on every server behind this proxy",
                    )
                }
            } catch (e: IOException) {
                platform.log.warn("$name can't sign messages, so it sends and accepts none: ${e.message}")
                null
            }
            val signer = secret?.signer()
            // The key's first use starts the JDK's crypto providers: on the async pool, now.
            signer?.let { warming -> platform.scheduler.run(Where.Async) { ProxySecret.warm(warming) } }
            val endpoint = ProxyEndpoint(name, ProxySide.PROXY, platform, signer)
            val transport = BungeeChannel(platform, endpoint)
            endpoint.attach(transport)
            transport.start()
            return endpoint
        }
    }
}
