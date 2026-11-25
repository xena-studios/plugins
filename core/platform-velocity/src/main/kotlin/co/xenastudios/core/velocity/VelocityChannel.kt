package co.xenastudios.core.velocity

import co.xenastudios.core.common.proxy.Peer
import co.xenastudios.core.common.proxy.ProxyEndpoint
import co.xenastudios.core.common.proxy.ProxySide
import co.xenastudios.core.common.proxy.ProxyTransport
import co.xenastudios.core.common.proxy.Sent
import co.xenastudios.core.jvm.proxy.ProxySecret
import com.velocitypowered.api.event.EventHandler
import com.velocitypowered.api.event.connection.PluginMessageEvent
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ServerConnection
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier
import java.io.IOException
import java.nio.file.Paths
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * A plugin's `xena:<plugin>` channel on Velocity: registered with Velocity's channel registrar,
 * which also tells each server the proxy listens on it, so the servers can send there. Every
 * message on it is handled here and goes no further, whichever way it was going: a server's
 * never reaches a player, and a player's never reaches a server. A player's is dropped, with a
 * warning the first time that player does it: only servers send on these channels, so it's
 * someone trying to pass a message off as the proxy's (DECISIONS.md, "The proxy layer").
 */
internal class VelocityChannel private constructor(
    private val platform: VelocityPlatform,
    private val endpoint: ProxyEndpoint,
) : ProxyTransport {

    private val identifier = MinecraftChannelIdentifier.from(endpoint.name)
    private val warned = ConcurrentHashMap.newKeySet<UUID>()

    private val handler = EventHandler<PluginMessageEvent> { event ->
        if (event.identifier.id != endpoint.name) return@EventHandler
        event.result = PluginMessageEvent.ForwardResult.handled()
        when (val source = event.source) {
            is ServerConnection -> endpoint.receive(Peer.Server(source.serverInfo.name), event.data)
            is Player -> if (warned.add(source.uniqueId)) {
                platform.log.warn("${source.username} sent a message on ${endpoint.name}, where only servers send; it was dropped")
            }
        }
    }

    fun start() {
        platform.server.channelRegistrar.register(identifier)
        // First, so no other plugin forwards a message before it's marked handled.
        platform.server.eventManager.register(platform.bootstrap, PluginMessageEvent::class.java, Priority.FIRST, handler)
    }

    override fun send(to: Peer, frame: ByteArray): Sent = when (to) {
        is Peer.Server -> {
            val server = platform.server.getServer(to.name).orElse(null)
            when {
                server == null -> Sent.UNKNOWN_SERVER
                server.sendPluginMessage(identifier, frame) -> Sent.SENT
                else -> Sent.NO_PLAYER
            }
        }
        Peer.AllServers -> {
            var sent = false
            for (server in platform.server.allServers) {
                if (server.playersConnected.isNotEmpty()) sent = server.sendPluginMessage(identifier, frame) || sent
            }
            if (sent) Sent.SENT else Sent.NO_PLAYER
        }
        Peer.Proxy -> Sent.WRONG_SIDE
    }

    override fun close() {
        platform.server.eventManager.unregister(platform.bootstrap, handler)
        platform.server.channelRegistrar.unregister(identifier)
    }

    companion object {
        /** The channel [name], signed with the network's secret, found or made as [ProxySecret] says. */
        fun open(platform: VelocityPlatform, name: String): ProxyEndpoint {
            val secret = try {
                secret(platform)
            } catch (e: IOException) {
                platform.log.warn("$name can't sign messages, so it sends and accepts none: ${e.message}")
                null
            }
            val endpoint = ProxyEndpoint(name, ProxySide.PROXY, platform, secret?.signer())
            val transport = VelocityChannel(platform, endpoint)
            endpoint.attach(transport)
            transport.start()
            return endpoint
        }

        private fun secret(platform: VelocityPlatform): ProxySecret {
            val family = platform.familyPath
            ProxySecret.read(family)?.let { return it }
            // Velocity's working folder holds velocity.toml.
            ProxySecret.velocity(Paths.get("velocity.toml"))?.let { return it }
            val made = ProxySecret.create(family)
            platform.log.info(
                "Made ${made.source}, the secret that signs messages between this proxy and its servers: " +
                    "copy it to plugins/Xena/ (config/xena/ on Fabric) on every server behind this proxy",
            )
            return made
        }
    }
}
