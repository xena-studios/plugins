package co.xenastudios.core.bukkit

import co.xenastudios.core.common.proxy.Peer
import co.xenastudios.core.common.proxy.ProxyEndpoint
import co.xenastudios.core.common.proxy.ProxySide
import co.xenastudios.core.common.proxy.ProxyTransport
import co.xenastudios.core.common.proxy.Sent
import co.xenastudios.core.jvm.config.JvmYaml
import co.xenastudios.core.jvm.proxy.ProxySecret
import org.bukkit.plugin.messaging.PluginMessageListener
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Paths

/**
 * A plugin's `xena:<plugin>` channel on a Bukkit server behind a proxy, on Bukkit's messenger:
 * what arrives is handed to the [ProxyEndpoint], which acts only on what the network's secret
 * signed, since a player's client can send on the channel too; what's sent goes out on the
 * connection of a player the proxy listens on the channel for (the proxy registers it for each
 * player as they connect), or not at all when there's none: a server alone, or with nobody on.
 */
internal class BukkitChannel private constructor(
    private val platform: BukkitPlatform,
    private val endpoint: ProxyEndpoint,
) : ProxyTransport {

    private val plugin = platform.plugin
    private val listener = PluginMessageListener { channel, _, bytes ->
        if (channel == endpoint.name) endpoint.receive(Peer.Proxy, bytes)
    }

    fun start() {
        val messenger = plugin.server.messenger
        messenger.registerOutgoingPluginChannel(plugin, endpoint.name)
        messenger.registerIncomingPluginChannel(plugin, endpoint.name, listener)
    }

    override fun send(to: Peer, frame: ByteArray): Sent {
        if (to != Peer.Proxy) return Sent.WRONG_SIDE
        // Bukkit drops a message for a channel the player's connection hasn't registered.
        val carrier = plugin.server.onlinePlayers.firstOrNull { endpoint.name in it.listeningPluginChannels } ?: return Sent.NO_PLAYER
        carrier.sendPluginMessage(plugin, endpoint.name, frame)
        return Sent.SENT
    }

    override fun close() {
        val messenger = plugin.server.messenger
        messenger.unregisterIncomingPluginChannel(plugin, endpoint.name, listener)
        messenger.unregisterOutgoingPluginChannel(plugin, endpoint.name)
    }

    companion object {
        /**
         * The channel [name], signed with the network's secret: `plugins/Xena/proxy.secret`, or on
         * Paper behind Velocity, the modern forwarding secret in `config/paper-global.yml`. Without
         * either, the channel sends and accepts nothing, and says why when a message arrives.
         */
        fun open(platform: BukkitPlatform, name: String, family: Path): ProxyEndpoint {
            val secret = try {
                ProxySecret.read(family) ?: ProxySecret.paper(Paths.get("config", "paper-global.yml"), JvmYaml)
            } catch (e: IOException) {
                platform.log.warn("$name can't check messages from the proxy, so it accepts none: ${e.message}")
                null
            }
            val endpoint = ProxyEndpoint(name, ProxySide.SERVER, platform, secret?.signer())
            val transport = BukkitChannel(platform, endpoint)
            endpoint.attach(transport)
            transport.start()
            return endpoint
        }
    }
}
