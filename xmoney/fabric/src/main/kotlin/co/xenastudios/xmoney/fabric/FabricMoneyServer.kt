package co.xenastudios.xmoney.fabric

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.pendingOf
import co.xenastudios.core.common.family.PlayerCache
import co.xenastudios.core.common.text.Component
import co.xenastudios.core.fabric.FabricPlatform
import co.xenastudios.core.fabric.MinecraftRenderer
import co.xenastudios.core.fabric.player
import co.xenastudios.xmoney.KnownPlayer
import co.xenastudios.xmoney.MoneyServer

/**
 * What xMoney's shared code asks of a Fabric server, with Minecraft's own classes. Names come from
 * who's online and the family's player cache (every player who has joined since a Xena mod was
 * installed); messages go out on the server thread.
 */
internal class FabricMoneyServer(private val platform: FabricPlatform, private val cache: PlayerCache) : MoneyServer {
    override fun send(player: PlayerRef, message: Component) {
        val server = platform.server ?: return
        val text = MinecraftRenderer.draw(message)
        if (server.isSameThread) server.player(player)?.sendSystemMessage(text) else server.execute { server.player(player)?.sendSystemMessage(text) }
    }

    override fun isOnline(player: PlayerRef): Boolean = platform.server?.player(player) != null

    override fun onlineNames(): List<String> = platform.server?.playerList?.players?.map { it.scoreboardName }.orEmpty()

    override fun known(name: String): Pending<KnownPlayer?> {
        platform.server?.playerList?.getPlayerByName(name)?.let { return pendingOf(KnownPlayer(PlayerRef.parseOrNull(it.uuid.toString())!!, it.scoreboardName)) }
        return pendingOf(cache.find(name)?.let { KnownPlayer(it.player, it.name) })
    }

    override fun cachedName(player: PlayerRef): String? = platform.server?.player(player)?.scoreboardName ?: cache[player]?.name
}
