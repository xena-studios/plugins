package co.xenastudios.core.bungee

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.toPlayerRef
import co.xenastudios.core.common.command.CommandSource
import co.xenastudios.core.common.command.OnlinePlayers
import net.md_5.bungee.api.ProxyServer

/** The players on the proxy, for the `player` argument: everyone on it, whichever server they're on. */
internal class BungeePlayers(private val server: ProxyServer) : OnlinePlayers {
    override fun find(name: String, viewer: CommandSource): PlayerRef? = server.getPlayer(name)?.uniqueId?.toPlayerRef()

    override fun names(viewer: CommandSource): List<String> = server.players.map { it.name }
}
