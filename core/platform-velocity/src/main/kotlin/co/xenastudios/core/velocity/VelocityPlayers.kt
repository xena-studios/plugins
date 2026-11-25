package co.xenastudios.core.velocity

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.toPlayerRef
import co.xenastudios.core.common.command.CommandSource
import co.xenastudios.core.common.command.OnlinePlayers
import com.velocitypowered.api.proxy.ProxyServer

/** The players on the proxy, for the `player` argument: everyone on it, whichever server they're on. */
internal class VelocityPlayers(private val server: ProxyServer) : OnlinePlayers {
    override fun find(name: String, viewer: CommandSource): PlayerRef? = server.getPlayer(name).orElse(null)?.uniqueId?.toPlayerRef()

    override fun names(viewer: CommandSource): List<String> = server.allPlayers.map { it.username }
}
